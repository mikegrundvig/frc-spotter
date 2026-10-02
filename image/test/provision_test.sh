# shellcheck shell=bash
# provision.sh, offline, on a tree shaped like PhotonVision's Armbian image: what it changes, that a
# second run changes nothing, and what it refuses. (The package install and the smoke test run only
# in the image's chroot, in CI.)

setup_provision() {
  need systemctl
  make_image_root
}

test_provision_makes_the_root_read_only_and_data_writable() {
  setup_provision
  run_provision
  local fstab=$TMP/root/etc/fstab
  grep -Eq '^UUID=0b1c2d3e-0000-4000-8000-000000000000 / ext4 ro,noatime 0 1$' "$fstab" ||
    fail "the root's line isn't read-only: $(grep ' / ' "$fstab")"
  assert_contains "$fstab" 'LABEL=coproc-data /data ext4 noatime,errors=remount-ro,nofail,x-systemd.device-timeout=10s 0 2'
  assert_contains "$fstab" '/data/photonvision_config /opt/photonvision/photonvision_config none bind'
  assert_contains "$fstab" '/data/journal /var/log/journal none bind'
  local point
  for point in /tmp /var/tmp /var/log /var/lib/systemd /var/lib/NetworkManager; do
    grep -Eq "^tmpfs $point tmpfs " "$fstab" || fail "$point isn't on tmpfs"
  done
  # The base image's own /tmp line is replaced; lines for other mount points stay.
  assert_eq "$(grep -c ' /tmp ' "$fstab")" 1 "lines for /tmp"
  assert_contains "$fstab" 'proc /proc proc defaults 0 0'
  [[ -d $TMP/root/data && -d $TMP/root/opt/photonvision/photonvision_config ]] || fail "no mount points"
}

test_provision_keeps_the_journal_and_syncs_it_often() {
  setup_provision
  run_provision
  local conf=$TMP/root/etc/systemd/journald.conf.d/70-coprocessor.conf
  assert_contains "$conf" 'Storage=persistent'
  assert_contains "$conf" 'SystemMaxUse=256M'
  assert_contains "$conf" 'SyncIntervalSec=10s'
}

test_provision_turns_off_armbians_ram_logging_resize_and_first_run() {
  setup_provision
  run_provision
  assert_contains "$TMP/root/etc/default/armbian-ramlog" 'ENABLED=false'
  assert_file "$TMP/root/root/.no_rootfs_resize"
  local unit
  for unit in armbian-ramlog.service armbian-resize-filesystem.service armbian-firstrun.service \
    apt-daily.timer logrotate.timer; do
    assert_masked "$TMP/root" "$unit"
  done
  assert_contains "$TMP/root/etc/e2fsck.conf" 'broken_system_clock = 1'
  [[ ! -s $TMP/root/etc/machine-id ]] || fail "the common image carries a machine ID"
}

test_provision_turns_off_photonvisions_network_management() {
  setup_provision
  run_provision
  local dropin=$TMP/root/etc/systemd/system/photonvision.service.d/10-coprocessor.conf
  assert_contains "$dropin" 'ExecStart=/usr/bin/java -Xmx512m -jar /opt/photonvision/photonvision.jar -n'
  assert_contains "$dropin" 'RequiresMountsFor=/opt/photonvision/photonvision_config'
  assert_eq "$(grep -c '^ExecStart=$' "$dropin")" 1 "ExecStart resets"
  # Soft-off waits for PhotonVision to stop, so its stop is bounded.
  grep -qx 'TimeoutStopSec=15s' "$dropin" || fail "PhotonVision's stop isn't bounded"
  cmp -s "$TMP/root/opt/photonvision/photonvision.jar" "$TMP/photonvision.jar" || fail "the jar wasn't installed"
}

test_provision_leaves_the_network_to_the_stamped_profile() {
  setup_provision
  run_provision
  assert_contains "$TMP/root/etc/NetworkManager/conf.d/90-coprocessor.conf" 'no-auto-default=*'
  assert_no_file "$TMP/root/etc/netplan/10-dhcp-all-interfaces.yaml"
  assert_file "$TMP/root/etc/netplan.disabled/10-dhcp-all-interfaces.yaml"
  assert_no_file "$TMP/root/etc/NetworkManager/system-connections/old.nmconnection"
  assert_masked "$TMP/root" systemd-networkd.service
}

test_provision_makes_ssh_key_only_with_a_host_key_per_drive() {
  setup_provision
  run_provision
  local conf=$TMP/root/etc/ssh/sshd_config.d/10-coprocessor.conf
  assert_contains "$conf" 'PasswordAuthentication no'
  assert_contains "$conf" 'KbdInteractiveAuthentication no'
  assert_contains "$conf" 'HostKey /data/ssh/ssh_host_ed25519_key'
  assert_no_file "$TMP/root/etc/ssh/ssh_host_ed25519_key"
  assert_no_file "$TMP/root/etc/ssh/ssh_host_ed25519_key.pub"
  [[ -L $TMP/root/etc/systemd/system/ssh.service.wants/coprocessor-ssh-hostkey.service ]] ||
    fail "the host key service isn't enabled"
}

test_provision_installs_the_agent() {
  setup_provision
  run_provision
  cmp -s "$TMP/root/opt/coprocessor/coprocessor-agent.jar" "$TMP/coprocessor-agent.jar" ||
    fail "the agent's jar wasn't installed"
  cmp -s "$TMP/root/etc/systemd/system/coprocessor-agent.service" "$TMP/coprocessor-agent.service" ||
    fail "the agent's unit wasn't installed"
  local wants=$TMP/root/etc/systemd/system/multi-user.target.wants
  [[ -L $wants/coprocessor-agent.service ]] || fail "the agent isn't enabled"
  [[ -L $wants/coprocessor-facts.service ]] || fail "the facts helper isn't enabled"
  [[ -L $TMP/root/etc/systemd/system/timers.target.wants/coprocessor-facts.timer ]] ||
    fail "the facts timer isn't enabled"
  assert_mode "$TMP/root/usr/local/lib/coprocessor/coprocessor-facts.sh" 755
}

test_provision_can_be_rerun() {
  setup_provision
  run_provision
  local first
  first=$(tree_digest "$TMP/root")
  run_provision
  assert_eq "$(tree_digest "$TMP/root")" "$first" "the tree after a second run"
}

test_provision_refuses_a_jar_that_isnt_the_locks() {
  setup_provision
  assert_fails "refusing it" run_provision \
    --photonvision-sha256 0000000000000000000000000000000000000000000000000000000000000000
  run_provision --photonvision-sha256 "$(sha256sum <"$TMP/photonvision.jar" | cut -c1-64)"
}

test_provision_refuses_an_agent_unit_that_runs_another_jar() {
  setup_provision
  sed -i 's|/opt/coprocessor/coprocessor-agent.jar|/opt/agent.jar|' "$TMP/coprocessor-agent.service"
  assert_fails "doesn't run /opt/coprocessor/coprocessor-agent.jar" run_provision
}

test_provision_refuses_a_fixed_mac_address() {
  setup_provision
  echo 'ethaddr=02:00:00:00:00:01' >>"$TMP/root/boot/armbianEnv.txt"
  assert_fails "MAC address" run_provision
}

test_provision_refuses_an_image_that_isnt_photonvisions() {
  setup_provision
  rm "$TMP/root/etc/systemd/system/photonvision.service"
  assert_fails "PhotonVision's unit isn't installed" run_provision
}

test_provision_refuses_another_boot_layout() {
  setup_provision
  rm "$TMP/root/boot/boot.scr"
  assert_fails "not the boot layout" run_provision
}

test_provision_refuses_an_image_without_networkmanager() {
  setup_provision
  rm "$TMP/root/usr/sbin/NetworkManager"
  assert_fails "NetworkManager isn't installed" run_provision
}

test_provision_closes_the_base_images_logins() {
  setup_provision
  run_provision
  local root=$TMP/root
  assert_no_file "$root/etc/systemd/system/getty@.service.d/override.conf"
  assert_no_file "$root/etc/systemd/system/serial-getty@.service.d/override.conf"
  local unit
  for unit in getty@.service serial-getty@.service autovt@.service; do
    assert_masked "$root" "$unit"
  done
  assert_contains "$root/etc/systemd/logind.conf.d/70-coprocessor.conf" 'NAutoVTs=0'
  # No password works for root or photon; everything else in the file is as it was.
  local f
  for f in shadow shadow-; do
    assert_eq "$(cat "$root/etc/$f")" 'root:*:20718:0:99999:7:::
daemon:*:20697:0:99999:7:::
sshd:!*:20697::::::
photon:*:20718:0:99999:7:::' "/etc/$f"
    assert_mode "$root/etc/$f" 640
  done
  # photon's sudo stays: with no password, it's the key holder's way to root.
  assert_file "$root/etc/sudoers.d/010_photon-nopasswd"
}

test_provision_fails_if_any_account_keeps_a_password() {
  setup_provision
  # shellcheck disable=SC2016 # a stand-in hash, literally
  echo 'pi:$y$j9T$fixture$NotARealHash:20718:0:99999:7:::' >>"$TMP/root/etc/shadow"
  assert_fails "usable password (or none) for: pi" run_provision
}

test_provision_fails_if_an_account_has_no_password_at_all() {
  setup_provision
  echo 'guest::20718:0:99999:7:::' >>"$TMP/root/etc/shadow-"
  assert_fails "for: guest" run_provision
}

test_provision_fails_if_a_console_still_logs_in_by_itself() {
  setup_provision
  mkdir -p "$TMP/root/usr/lib/systemd/system/getty@tty1.service.d"
  printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --autologin photon %%I\n' \
    >"$TMP/root/usr/lib/systemd/system/getty@tty1.service.d/autologin.conf"
  assert_fails "a console logs in by itself" run_provision
}

test_provision_makes_ssh_refuse_x11_and_root() {
  setup_provision
  run_provision
  local conf=$TMP/root/etc/ssh/sshd_config.d/10-coprocessor.conf
  assert_contains "$conf" 'PermitRootLogin no'
  assert_contains "$conf" 'X11Forwarding no'
  assert_contains "$conf" 'PubkeyAuthentication yes'
}

test_provision_makes_the_host_key_whole_or_not_at_all() {
  setup_provision
  run_provision
  local unit=$TMP/root/etc/systemd/system/coprocessor-ssh-hostkey.service
  local keygen move
  keygen=$(grep -n 'ssh-keygen' "$unit" | cut -d: -f1)
  move=$(grep -n 'mv -f /data/ssh/ssh_host_ed25519_key.new /data/ssh/ssh_host_ed25519_key$' "$unit" | cut -d: -f1)
  if [[ -z $keygen || -z $move ]] || ((keygen > move)); then
    fail "the key isn't made aside and moved into place"
  fi
  assert_contains "$unit" '-f /data/ssh/ssh_host_ed25519_key.new'
}

test_provision_masks_rsyslog_and_the_clock_save() {
  setup_provision
  run_provision
  local unit
  for unit in rsyslog.service syslog.socket fake-hwclock-save.timer fake-hwclock-save.service; do
    assert_masked "$TMP/root" "$unit"
  done
}

test_provision_sandboxes_the_facts_helper() {
  setup_provision
  run_provision
  local unit=$TMP/root/etc/systemd/system/coprocessor-facts.service line
  for line in ProtectSystem=strict RuntimeDirectory=coprocessor RuntimeDirectoryPreserve=yes \
    PrivateNetwork=yes NoNewPrivileges=yes DevicePolicy=closed 'DeviceAllow=/dev/nvme0 r' \
    'DeviceAllow=/dev/mtd0 r'; do
    grep -qx "$line" "$unit" || fail "the facts helper's unit lacks $line"
  done
}

test_provision_refuses_an_agent_that_would_run_as_root() {
  setup_provision
  sed -i '/^User=/d; /^Group=/d' "$TMP/coprocessor-agent.service"
  assert_fails "would run the agent as root" run_provision
  printf '[Service]\nUser=root\n' >>"$TMP/coprocessor-agent.service"
  assert_fails "would run the agent as root" run_provision
}

test_provision_refuses_an_agent_account_the_soft_off_rule_doesnt_name() {
  setup_provision
  sed -i 's/^User=coprocessor-agent$/User=someone-else/' "$TMP/coprocessor-agent.service"
  assert_fails "but the soft-off rule is for 'coprocessor-agent'" run_provision
}

test_provision_accepts_a_dynamic_account_named_after_the_unit() {
  setup_provision
  sed -i 's/^User=coprocessor-agent$/DynamicUser=yes/; /^Group=/d' "$TMP/coprocessor-agent.service"
  run_provision
}

# The agent's own unit, from coprocessor/agent/, as the image installs it.
test_provision_accepts_the_agents_own_unit() {
  setup_provision
  local unit=$IMAGE/../agent/coprocessor-agent.service
  [[ -f $unit ]] || skip "no coprocessor/agent/coprocessor-agent.service in this checkout"
  run_provision --agent-unit "$unit"
  cmp -s "$TMP/root/etc/systemd/system/coprocessor-agent.service" "$unit" || fail "the agent's unit wasn't installed"
}

test_provision_installs_the_soft_off_rule_for_the_agents_account() {
  setup_provision
  run_provision
  local rule=$TMP/root/etc/polkit-1/rules.d/60-coprocessor-agent.rules
  assert_contains "$rule" 'var agentUser = "coprocessor-agent";'
  assert_not_contains "$rule" '@AGENT_USER@'
}

# The rule, run as polkit would run it, against the requests it must allow and refuse.
test_soft_off_rule_allows_stopping_photonvision_and_powering_off_only() {
  setup_provision
  need node
  run_provision
  cat >"$TMP/rule-test.js" <<'EOF'
const fs = require("fs");
const vm = require("vm");
let rule = null;
const polkit = {
  Result: { YES: "yes", NO: "no", NOT_HANDLED: null },
  addRule: (f) => { rule = f; },
};
vm.runInNewContext(fs.readFileSync(process.argv[2], "utf8"), { polkit });
const ask = (user, id, details) =>
  String(rule({ id, lookup: (k) => (details || {})[k] }, { user }));
const cases = [
  ["coprocessor-agent", "org.freedesktop.login1.power-off", {}, "yes"],
  ["coprocessor-agent", "org.freedesktop.systemd1.manage-units", { unit: "photonvision.service", verb: "stop" }, "yes"],
  ["coprocessor-agent", "org.freedesktop.systemd1.manage-units", { unit: "photonvision.service", verb: "restart" }, "no"],
  ["coprocessor-agent", "org.freedesktop.systemd1.manage-units", { unit: "photonvision.service", verb: "start" }, "no"],
  ["coprocessor-agent", "org.freedesktop.systemd1.manage-units", { unit: "ssh.service", verb: "stop" }, "no"],
  ["coprocessor-agent", "org.freedesktop.systemd1.manage-unit-files", {}, "no"],
  ["coprocessor-agent", "org.freedesktop.login1.reboot", {}, "no"],
  ["coprocessor-agent", "org.freedesktop.login1.power-off-multiple-sessions", {}, "yes"],
  ["coprocessor-agent", "org.freedesktop.login1.power-off-ignore-inhibit", {}, "no"],
  ["photon", "org.freedesktop.login1.power-off", {}, "null"],
  ["root", "org.freedesktop.systemd1.manage-units", { unit: "photonvision.service", verb: "stop" }, "null"],
];
let failed = 0;
for (const [user, id, details, expected] of cases) {
  const got = ask(user, id, details);
  if (got !== expected) {
    console.log(`${user} ${id} ${JSON.stringify(details)}: expected ${expected}, got ${got}`);
    failed++;
  }
}
process.exit(failed ? 1 : 0);
EOF
  node "$TMP/rule-test.js" "$TMP/root/etc/polkit-1/rules.d/60-coprocessor-agent.rules" ||
    fail "the soft-off rule decides wrongly"
}
