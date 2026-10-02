# shellcheck shell=bash
# Helpers and fixtures for the image scripts' tests; run.sh sources this into each test. $IMAGE is
# the image scripts' folder, $TMP the test's own temporary directory.

fail() {
  printf 'FAILED: %s\n' "$*" >&2
  exit 1
}

# Ends the test as skipped, saying why.
skip() {
  printf '%s\n' "$*"
  exit 77
}

need() {
  local tool
  for tool; do
    command -v "$tool" >/dev/null 2>&1 || skip "needs $tool"
  done
}

need_yq() {
  need yq
  case "$(yq --version 2>&1)" in
    *mikefarah*" v4."* | *mikefarah*" version 4."*) ;;
    *) skip "needs mikefarah's yq, version 4" ;;
  esac
}

assert_eq() {
  [[ $1 == "$2" ]] || fail "${3:-value}: expected '$2', got '$1'"
}

assert_file() {
  [[ -f $1 ]] || fail "no file $1"
}

assert_no_file() {
  [[ ! -e $1 && ! -L $1 ]] || fail "$1 exists"
}

assert_contains() {
  grep -qF -- "$2" "$1" || fail "$1 doesn't contain '$2'; it holds:$(printf '\n'; cat "$1")"
}

assert_not_contains() {
  if grep -qF -- "$2" "$1"; then
    fail "$1 contains '$2'"
  fi
}

assert_mode() {
  local mode
  mode=$(stat -c %a "$1")
  [[ $mode == "$2" ]] || fail "$1 has mode $mode, expected $2"
}

# A symlink to /dev/null: a masked unit.
assert_masked() {
  [[ $(readlink "$1/etc/systemd/system/$2") == /dev/null ]] || fail "$2 isn't masked"
}

# Runs a command that must fail, saying something that contains TEXT.
assert_fails() {
  local text=$1 output
  shift
  if output=$("$@" 2>&1); then
    fail "expected to fail: $*"
  fi
  [[ $output == *"$text"* ]] || fail "failed, but without '$text': $output"
}

# Every path in a tree with its type, mode, symlink target, and content's sha256: equal digests
# mean equal trees, whatever the files' times.
tree_digest() {
  (
    cd "$1" || exit 1
    find . -printf '%p %y %m %l\n' | LC_ALL=C sort
    find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum
  )
}

# A repository with the coprocessor inputs: the table (team 1234, two computers on two boards),
# a lock in the format the scripts read, the vendordep, settings for one computer, an SSH key, and
# the recipe's paths, committed. Prints nothing; the repository is $TMP/repo.
make_repo() {
  local repo=$TMP/repo
  mkdir -p "$repo/coprocessors/vision-front/settings/cameras" "$repo/coprocessor/image" \
    "$repo/vendordeps"
  cat >"$repo/coprocessors/coprocessors.yaml" <<'EOF'
team: 1234
agentPort: 5808
computers:
  - name: vision-front
    address: 11
    board: orangepi-5
    cameras: [front-left, front-right]
  - name: vision-back
    address: 12
    board: orangepi-5-plus
    cameras: [back]
    agentPort: 5809
EOF
  echo '{"name": "front-left", "calibration": [1, 2, 3]}' \
    >"$repo/coprocessors/vision-front/settings/cameras/front-left.json"
  echo 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyForTestsOnly team-laptop' \
    >"$repo/coprocessors/authorized_keys"
  cat >"$repo/coprocessor/photonvision.lock" <<'EOF'
version: dev-v2027.0.0-alpha-2-69-g71416112
jar:
  url: https://github.com/example/robot/releases/download/photonvision/photonvision-dev-v2027.0.0-alpha-2-69-g71416112-linuxarm64.jar
  sha256: 1111111111111111111111111111111111111111111111111111111111111111
images:
  orangepi-5:
    url: https://github.com/PhotonVision/photon-image-modifier/releases/download/v2027.2.3/photonvision_opi5.img.xz
    sha256: edf2bda3032579d759de46aab0e8094cfd3de3586ba764b663470d2b80351cb7
  orangepi-5-plus:
    url: https://github.com/PhotonVision/photon-image-modifier/releases/download/v2027.2.3/photonvision_opi5plus.img.xz
    sha256: 8fd9ae7f2056a47452ab1600c118ff1d873ea48cfccef548031eca1f682afd88
EOF
  echo '{"fileName": "photonlib.json", "version": "dev-v2027.0.0-alpha-2-69-g71416112"}' \
    >"$repo/vendordeps/photonlib.json"
  echo 'echo provision' >"$repo/coprocessor/image/provision.sh"
  echo 'how to build' >"$repo/coprocessor/image/README.md"
  git_commit "$repo" "the fixture"
}

git_commit() {
  git -C "$1" init -q 2>/dev/null || true
  git -C "$1" add -A
  git -C "$1" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
    commit -q -m "$2"
}

# The settings tool's stand-in: copies the empty database, appends the rows (so a test can see
# they arrived), logs its arguments to $TMP/tool.log, and prints a hash of the rows.
make_settings_tool() {
  cat >"$TMP/settings-tool" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
rows=$1 empty=$2 out=$3
printf '%s\n' "$@" >"${TOOL_LOG:?}"
cp "$empty" "$out"
rows_text() {
  if [[ -d $rows ]]; then
    (cd "$rows" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r cat)
  fi
}
rows_text >>"$out"
rows_text | sha256sum | cut -c1-64
EOF
  chmod +x "$TMP/settings-tool"
  printf 'SQLite format 3 (empty, for the tests)\n' >"$TMP/empty.sqlite"
}

# Runs stamp.sh for a computer of make_repo's table into $TMP/out/{root,coproc,data}, with any
# extra options after the name.
run_stamp() {
  local name=$1
  shift
  mkdir -p "$TMP/out/root" "$TMP/out/coproc" "$TMP/out/data"
  TOOL_LOG=$TMP/tool.log "$IMAGE/stamp.sh" --computer "$name" --repo "$TMP/repo" \
    --root "$TMP/out/root" --coproc "$TMP/out/coproc" --data "$TMP/out/data" \
    --release coprocessors-1 \
    --recipe-hash 2222222222222222222222222222222222222222222222222222222222222222 \
    --photonvision-version dev-v2027.0.0-alpha-2-69-g71416112 \
    --empty-db "$TMP/empty.sqlite" --settings-tool "$TMP/settings-tool" "$@"
}

# A root shaped like PhotonVision's Armbian image, as far as provision.sh looks at it, in
# $TMP/root; and the agent's jar and unit, and a PhotonVision jar, in $TMP.
make_image_root() {
  local root=$TMP/root
  mkdir -p "$root"/{boot,root,usr/lib,var/lib/dbus,etc/default,etc/netplan,etc/ssh/sshd_config.d} \
    "$root/etc/NetworkManager/system-connections" "$root/etc/systemd/system" "$root/lib/systemd/system"
  printf 'PRETTY_NAME="Armbian (fixture)"\nID=debian\n' >"$root/etc/os-release"
  mkdir -p "$root/usr/sbin"
  printf '#!/bin/sh\n' >"$root/usr/sbin/NetworkManager"
  chmod +x "$root/usr/sbin/NetworkManager"
  : >"$root/boot/boot.scr"
  printf 'verbosity=1\nrootdev=UUID=0b1c2d3e-0000-4000-8000-000000000000\nrootfstype=ext4\n' \
    >"$root/boot/armbianEnv.txt"
  cat >"$root/etc/systemd/system/photonvision.service" <<'EOF'
[Unit]
Description=Service that runs PhotonVision
After=network.target

[Service]
WorkingDirectory=/opt/photonvision
Nice=-10
AllowedCPUs=4-7
ExecStart=/usr/bin/java -Xmx512m -jar /opt/photonvision/photonvision.jar
Type=simple
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
  printf 'Include /etc/ssh/sshd_config.d/*.conf\nKbdInteractiveAuthentication no\nUsePAM yes\n' \
    >"$root/etc/ssh/sshd_config"
  echo 'not a real key' >"$root/etc/ssh/ssh_host_ed25519_key"
  echo 'not a real key' >"$root/etc/ssh/ssh_host_ed25519_key.pub"
  cat >"$root/etc/fstab" <<'EOF'
# <file system> <mount point> <type> <options> <dump> <pass>
UUID=0b1c2d3e-0000-4000-8000-000000000000 / ext4 defaults,noatime,commit=120,errors=remount-ro 0 1
tmpfs /tmp tmpfs defaults,nosuid 0 0
proc /proc proc defaults 0 0
EOF
  printf 'ENABLED=true\nSIZE=50M\nUSE_RSYNC=true\n' >"$root/etc/default/armbian-ramlog"
  printf 'network:\n  version: 2\n  renderer: networkd\n  ethernets:\n    all-eth:\n      match:\n        name: "e*"\n      dhcp4: yes\n' \
    >"$root/etc/netplan/10-dhcp-all-interfaces.yaml"
  printf '[connection]\nid=Wired connection 1\ntype=ethernet\n' \
    >"$root/etc/NetworkManager/system-connections/old.nmconnection"
  echo 0123456789abcdef0123456789abcdef >"$root/etc/machine-id"
  # The logins, as PhotonVision's Orange Pi image ships them: the console logs root in with no
  # password, and root and photon have the public default passwords (stand-in hashes here).
  local getty
  for getty in getty serial-getty; do
    mkdir -p "$root/etc/systemd/system/$getty@.service.d"
    # shellcheck disable=SC2016 # $TERM is the unit's, as the base image writes it
    printf '[Service]\nExecStart=\nExecStart=-/sbin/agetty --noissue --autologin root %%I $TERM\nType=idle\n' \
      >"$root/etc/systemd/system/$getty@.service.d/override.conf"
  done
  mkdir -p "$root/etc/systemd/system/getty.target.wants" "$root/etc/sudoers.d"
  ln -s /lib/systemd/system/getty@.service "$root/etc/systemd/system/getty.target.wants/getty@tty1.service"
  ln -s /lib/systemd/system/serial-getty@.service \
    "$root/etc/systemd/system/getty.target.wants/serial-getty@ttyFIQ0.service"
  cat >"$root/etc/shadow" <<'EOF'
root:$y$j9T$fixture$NotARealHashRoot:20718:0:99999:7:::
daemon:*:20697:0:99999:7:::
sshd:!*:20697::::::
photon:$y$j9T$fixture$NotARealHashPhoton:20718:0:99999:7:::
EOF
  cp "$root/etc/shadow" "$root/etc/shadow-"
  chmod 0640 "$root/etc/shadow" "$root/etc/shadow-"
  echo 'photon ALL=(ALL) NOPASSWD: ALL' >"$root/etc/sudoers.d/010_photon-nopasswd"

  echo 'photonvision jar' >"$TMP/photonvision.jar"
  echo 'agent jar' >"$TMP/coprocessor-agent.jar"
  cat >"$TMP/coprocessor-agent.service" <<'EOF'
[Unit]
Description=Coprocessor health agent

[Service]
ExecStart=/usr/bin/java -Xmx64m -XX:+UseSerialGC -jar /opt/coprocessor/coprocessor-agent.jar
User=coprocessor-agent
Group=coprocessor-agent
SupplementaryGroups=systemd-journal
Nice=10
AllowedCPUs=0-3

[Install]
WantedBy=multi-user.target
EOF
}

# Runs provision.sh on make_image_root's tree, offline, with any extra options.
run_provision() {
  "$IMAGE/provision.sh" --board orangepi-5 --target "$TMP/root" --offline \
    --photonvision-jar "$TMP/photonvision.jar" \
    --agent-jar "$TMP/coprocessor-agent.jar" --agent-unit "$TMP/coprocessor-agent.service" "$@"
}
