#!/usr/bin/env bash
# provision.sh: makes PhotonVision's image for a board into the common coprocessor image.
#
# Runs as root inside a chroot of PhotonVision's official image for the board. In CI that's
# PhotonVision's photon-image-runner, which bind-mounts the repository at /tmp/build and runs from
# there. Safe to rerun: every step sets a state (writes a whole file, masks a unit, replaces its own
# block of /etc/fstab) rather than adding to one.
#
# In order, it:
#   - installs the packages it adds, at pinned versions: nvme-cli (the drive's health) and polkitd
#     (the agent's soft-off);
#   - installs the pinned PhotonVision jar, and runs PhotonVision with -n, so it leaves the network
#     alone;
#   - leaves the network to NetworkManager alone, with one profile, the robot's static address,
#     which stamping writes;
#   - makes the root read-only: /data (ext4, errors=remount-ro, checked at boot) bind-mounted at
#     PhotonVision's config folder and at /var/log/journal; tmpfs for /tmp, /var/tmp, /var/log, and
#     NetworkManager's and systemd's state; the journal kept and synced every 10 s; Armbian's RAM
#     logging, first-boot resize, and first-run tasks off;
#   - closes the base image's logins: no console autologin, no console at all, and no password
#     that works (root's and photon's were public defaults);
#   - makes SSH take keys only, with a host key made per drive;
#   - installs the health agent and its unit, and the root helper that reads the drive's health
#     for it;
#   - runs PhotonVision's --smoketest, which also makes an empty settings database, and leaves
#     its native libraries extracted where PhotonVision looks for them (the root is read-only
#     after this);
#   - and last, checks itself: no usable password, no autologin, and (in the chroot) sshd's
#     effective settings.
#
# The partitions themselves are added to the image file afterwards, outside the chroot, by
# layout.sh: this writes what inside the root refers to them (by label).
#
# Usage, from the repository's root:
#   coprocessor/image/provision.sh --board BOARD \
#       --photonvision-jar PATH [--photonvision-sha256 HEX] \
#       [--agent-jar PATH] [--agent-unit PATH] [--smoketest-dir DIR]
#
#   --smoketest-dir   where the smoke test runs, and so where its photonvision_config/ (with the
#                     empty photon.sqlite stamping starts from) is left; outside the image, so the
#                     image stays clean. Without it, a temporary folder, removed afterwards.
#
# For the tests: --target DIR applies the changes to a directory tree instead of /, and --offline
# leaves out what runs the image's own programs (apt-get, useradd, java).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
# shellcheck source=lib/common.sh
. "$here/lib/common.sh"

board=""
pv_jar=""
pv_sha=""
agent_jar="$repo/$COPROC_AGENT_JAR"
agent_unit="$repo/$COPROC_AGENT_UNIT"
smoke_dir=""
target=/
offline=no

# The packages it adds, pinned to Debian 13 (trixie)'s versions. A new version on the mirror
# fails the build here, loudly, until the pin is updated: the image changes only by a commit.
PACKAGES=(nvme-cli=2.13-2 polkitd=126-2)

usage() {
  sed -n '/^# Usage/,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//' >&2
  exit 2
}

while (($#)); do
  case $1 in
    --board) board=${2:?}; shift 2 ;;
    --photonvision-jar) pv_jar=${2:?}; shift 2 ;;
    --photonvision-sha256) pv_sha=${2:?}; shift 2 ;;
    --agent-jar) agent_jar=${2:?}; shift 2 ;;
    --agent-unit) agent_unit=${2:?}; shift 2 ;;
    --smoketest-dir) smoke_dir=${2:?}; shift 2 ;;
    --target) target=${2:?}; shift 2 ;;
    --offline) offline=yes; shift ;;
    -h | --help) usage ;;
    *) say "unknown option: $1"; usage ;;
  esac
done

[[ -n $board ]] || die "--board is required (one of: $BOARDS)"
is_board "$board" || die "unknown board '$board' (one of: $BOARDS)"
[[ -n $pv_jar ]] || die "--photonvision-jar is required"
if [[ $offline == no && $EUID -ne 0 ]]; then
  die "run as root, inside the image's chroot"
fi
target=$(cd "$target" && pwd)
board_file="$here/boards/$board.env"
# shellcheck source=boards/orangepi-5.env
. "$board_file"

# A path inside the target.
t() {
  printf '%s%s' "${target%/}" "$1"
}

# Writes stdin to a file inside the target, with a mode, replacing what was there.
put() {
  local dest
  dest=$(t "$1")
  mkdir -p "$(dirname "$dest")"
  cat >"$dest.coproc-new"
  chmod "$2" "$dest.coproc-new"
  mv -f "$dest.coproc-new" "$dest"
}

# Copies one of files/ into the target.
put_file() {
  put "$2" "$3" <"$here/files/$1"
}

# systemctl on the target's unit files; no running systemd is involved.
units() {
  systemctl --root="$target" --quiet "$@"
}

check_base() {
  [[ -f $(t /etc/os-release) ]] || die "no /etc/os-release in $target: not an image's root"
  [[ -f $(t "$BOARD_BOOT_PATH") ]] ||
    die "no $BOARD_BOOT_PATH in the image: not the boot layout $board_file describes"
  pv_unit=$(t /etc/systemd/system/photonvision.service)
  [[ -f $pv_unit ]] || pv_unit=$(t /lib/systemd/system/photonvision.service)
  [[ -f $pv_unit ]] || die "PhotonVision's unit isn't installed: is this PhotonVision's image?"
  [[ -x $(t /usr/sbin/NetworkManager) ]] ||
    die "NetworkManager isn't installed: the robot network's profile needs it"
  grep -Eq '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config\.d/' "$(t /etc/ssh/sshd_config)" ||
    die "sshd_config doesn't read sshd_config.d/, so SSH's settings here wouldn't apply"
  # A fixed Ethernet address in Armbian's boot settings would be shared by every board flashed
  # from this image. Armbian's first-run task replaces one; that task is off here.
  if grep -Eq '^eth1?addr=' "$(t /boot/armbianEnv.txt)" 2>/dev/null; then
    die "/boot/armbianEnv.txt sets a MAC address, which every board flashed from this image would share"
  fi
  # ifupdown owning an Ethernet port would make NetworkManager leave it alone.
  if grep -Eq '^[[:space:]]*(auto|allow-hotplug|iface)[[:space:]]+(e|eth)' \
    "$(t /etc/network/interfaces)" 2>/dev/null; then
    die "/etc/network/interfaces configures an Ethernet port; NetworkManager must own it"
  fi
}

step_packages() {
  # nvme-cli reads the drive's health for the agent (files/coprocessor-facts.sh); polkitd applies
  # the agent's soft-off rule (files/coprocessor-agent.rules).
  local package missing=()
  for package in "${PACKAGES[@]}"; do
    if [[ $(dpkg-query -W -f='${Status} ${Version}' "${package%%=*}" 2>/dev/null) != "install ok installed ${package#*=}" ]]; then
      missing+=("$package")
    fi
  done
  if ((${#missing[@]})); then
    say "installing ${missing[*]}"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends "${missing[@]}"
    apt-get clean
    rm -rf /var/lib/apt/lists/*
  fi
}

step_photonvision() {
  local actual exec_line
  [[ -f $pv_jar ]] || die "no PhotonVision jar at $pv_jar"
  if [[ -n $pv_sha ]]; then
    actual=$(sha256sum <"$pv_jar" | cut -c1-64)
    [[ $actual == "$pv_sha" ]] ||
      die "$pv_jar's sha256 is $actual, not the lock's $pv_sha: refusing it"
  fi
  mkdir -p "$(t "$IMG_PV_DIR")"
  install -m 0644 "$pv_jar" "$(t "$IMG_PV_JAR")"
  # PhotonVision's own command line, from its unit, with -n added (and only once).
  exec_line=$(sed -n 's/^ExecStart=//p' "$pv_unit" | tail -1)
  [[ -n $exec_line ]] || die "no ExecStart in $pv_unit"
  exec_line=$(printf '%s\n' "$exec_line" | sed -E 's/ (-n|--disable-networking)( |$)/\2/g; s/[[:space:]]+$//')
  put /etc/systemd/system/photonvision.service.d/10-coprocessor.conf 0644 <<EOF
# Written by coprocessor/image/provision.sh.
[Unit]
# PhotonVision's folder is /data's, bind-mounted.
RequiresMountsFor=$IMG_PV_CONFIG

[Service]
# PhotonVision's own command, with -n: the network is the image's (robot.nmconnection, written at
# stamping), never PhotonVision's.
ExecStart=
ExecStart=$exec_line -n
# Soft-off says it's safe to switch the board off once PhotonVision's port has closed, so its stop
# is bounded: systemd's default would wait 90 s before killing it. 15 s is a proposal, to confirm
# on the bench (bench-procedures.md, B3).
TimeoutStopSec=15s
EOF
}

step_network() {
  local f
  put_file NetworkManager.conf /etc/NetworkManager/conf.d/90-coprocessor.conf 0644
  # The robot's profile, written at stamping, is the only one. Armbian's netplan files (DHCP on
  # every port) and any profile in the base image move aside, kept for reference.
  if [[ -d $(t /etc/netplan) ]]; then
    for f in "$(t /etc/netplan)"/*.yaml; do
      [[ -e $f ]] || continue
      mkdir -p "$(t /etc/netplan.disabled)"
      mv -f "$f" "$(t /etc/netplan.disabled)/"
    done
  fi
  for f in "$(t /etc/NetworkManager/system-connections)"/*; do
    [[ -e $f && $f != "$(t "$IMG_CONNECTION")" ]] || continue
    mkdir -p "$(t /etc/NetworkManager/system-connections.disabled)"
    mv -f "$f" "$(t /etc/NetworkManager/system-connections.disabled)/"
  done
  units mask systemd-networkd.service systemd-networkd.socket systemd-networkd-wait-online.service
}

# The root's line made read-only, and this script's block (from an earlier run, or new) replaced.
# Any other line for a mount point the block owns is dropped.
fstab_rewrite() {
  local managed="$IMG_DATA $IMG_PV_CONFIG $IMG_JOURNAL /tmp /var/tmp /var/log /var/lib/systemd /var/lib/NetworkManager"
  awk -v managed="$managed" '
    BEGIN { n = split(managed, m, " "); for (i = 1; i <= n; i++) owned[m[i]] = 1 }
    /^# --- coprocessor image/ { skip = 1; next }
    skip && /^# --- end coprocessor image/ { skip = 0; next }
    skip { next }
    /^[[:space:]]*#/ || NF < 4 { print; next }
    $2 in owned { next }
    $2 == "/" { $4 = "ro,noatime"; root = 1 }
    { print }
    END { if (!root) exit 3 }
  '
  cat <<EOF
# --- coprocessor image: storage (coprocessor/image/provision.sh) ---
# The root is read-only. What's written while the computer runs goes to /data or to RAM.
LABEL=$DATA_LABEL $IMG_DATA ext4 noatime,errors=remount-ro,nofail,x-systemd.device-timeout=10s 0 2
$IMG_DATA/$DATA_PV_CONFIG $IMG_PV_CONFIG none bind,nofail,x-systemd.requires-mounts-for=$IMG_DATA 0 0
$IMG_DATA/$DATA_JOURNAL $IMG_JOURNAL none bind,nofail,x-systemd.requires-mounts-for=$IMG_DATA 0 0
tmpfs /tmp tmpfs mode=1777,nosuid,nodev,size=256M 0 0
tmpfs /var/tmp tmpfs mode=1777,nosuid,nodev,size=64M 0 0
tmpfs /var/log tmpfs mode=0755,nosuid,nodev,noexec,size=32M 0 0
tmpfs /var/lib/systemd tmpfs mode=0755,nosuid,nodev,noexec,size=16M 0 0
tmpfs /var/lib/NetworkManager tmpfs mode=0700,nosuid,nodev,noexec,size=8M 0 0
# --- end coprocessor image ---
EOF
}

step_storage() {
  local fstab ramlog
  fstab=$(t /etc/fstab)
  [[ -f $fstab ]] || die "no /etc/fstab in the image"
  if ! fstab_rewrite <"$fstab" >"$fstab.coproc-new"; then
    rm -f "$fstab.coproc-new"
    die "/etc/fstab has no line for the root (/)"
  fi
  chmod 0644 "$fstab.coproc-new"
  mv -f "$fstab.coproc-new" "$fstab"
  # Mount points on the root.
  mkdir -p "$(t "$IMG_DATA")" "$(t "$IMG_PV_CONFIG")" "$(t "$IMG_JOURNAL")" "$(t /var/lib/NetworkManager)"
  put_file journald.conf /etc/systemd/journald.conf.d/70-coprocessor.conf 0644
  put_file e2fsck.conf /etc/e2fsck.conf 0644
  # Armbian: RAM logging (keeps /var/log in RAM, and would hide the journal's bind mount), the
  # first-boot resize (the drive's free space stays unpartitioned), and the first-run tasks (they
  # write to the root: new SSH keys, a random MAC). Its own off switches, and the units masked.
  ramlog=$(t /etc/default/armbian-ramlog)
  if [[ -f $ramlog ]]; then
    sed -i 's/^ENABLED=.*/ENABLED=false/' "$ramlog"
  fi
  : >"$(t /root/.no_rootfs_resize)"
  units mask armbian-ramlog.service armbian-resize-filesystem.service armbian-firstrun.service \
    armbian-led-state.service
  # Timers that only write to the root (package lists, man pages, log rotation, dpkg backups).
  units mask apt-daily.timer apt-daily-upgrade.timer man-db.timer logrotate.timer dpkg-db-backup.timer
  # The journal is the log: rsyslog would only copy it into /var/log, in RAM. fake-hwclock saves the
  # clock to the root every hour and at shutdown, which a read-only root refuses; it still loads
  # the build's time at boot.
  units mask rsyslog.service syslog.socket fake-hwclock-save.timer fake-hwclock-save.service
  # The machine ID is written at stamping, one per computer; the common image carries none.
  put /etc/machine-id 0444 </dev/null
  if [[ -d $(t /var/lib/dbus) ]]; then
    ln -sfn /etc/machine-id "$(t /var/lib/dbus/machine-id)"
  fi
  # With no clock battery, systemd starts the clock no earlier than this file's time: the build's.
  touch "$(t /usr/lib/clock-epoch)"
}

# The base image's logins: its console logs root in with no password (Armbian's autologin
# overrides, left behind because PhotonVision's image skips Armbian's first-login script), and
# root and photon have public default passwords. No console here at all: with no password that
# works, a login prompt could never be used, and boot messages still reach the screen and the
# serial port. People log in over SSH, with the team's key, as photon.
step_logins() {
  local f
  rm -f "$(t /etc/systemd/system/getty@.service.d/override.conf)" \
    "$(t /etc/systemd/system/serial-getty@.service.d/override.conf)"
  rmdir "$(t /etc/systemd/system/getty@.service.d)" "$(t /etc/systemd/system/serial-getty@.service.d)" \
    2>/dev/null || true
  units mask getty@.service serial-getty@.service autovt@.service console-getty.service
  put /etc/systemd/logind.conf.d/70-coprocessor.conf 0644 <<'LOGIND'
# Written by coprocessor/image/provision.sh: no logins on the board's own screen or keyboard.
[Login]
NAutoVTs=0
ReserveVT=0
LOGIND
  # "*" matches no password, so nothing logs in with one; a key still logs in over SSH ("!" would
  # lock the account, which sshd's PAM account check refuses even for a key). photon keeps sudo
  # without a password: with no password left, it's how the team's key holder gets root for
  # maintenance (bench-procedures.md's checks use it).
  for f in "$(t /etc/shadow)" "$(t /etc/shadow-)"; do
    [[ -f $f ]] || continue
    awk -F: -v OFS=: -v user="$IMG_SSH_USER" '$1 == "root" || $1 == user { $2 = "*" } { print }' \
      "$f" >"$f.coproc-new"
    chmod --reference="$f" "$f.coproc-new"
    chown --reference="$f" "$f.coproc-new"
    mv -f "$f.coproc-new" "$f"
  done
}

step_ssh() {
  put_file sshd.conf /etc/ssh/sshd_config.d/10-coprocessor.conf 0644
  # The base image's host keys: the same in every copy of PhotonVision's image, and published.
  rm -f "$(t /etc/ssh)"/ssh_host_*
  put_file coprocessor-ssh-hostkey.service /etc/systemd/system/coprocessor-ssh-hostkey.service 0644
  units enable coprocessor-ssh-hostkey.service
}

step_agent() {
  local user
  [[ -f $agent_jar ]] || die "no agent jar at $agent_jar: build it first (:coprocessor-agent)"
  [[ -f $agent_unit ]] || die "no agent unit at $agent_unit"
  grep -qF "$IMG_AGENT_JAR" "$agent_unit" ||
    die "$agent_unit doesn't run $IMG_AGENT_JAR, where the agent is installed"
  mkdir -p "$(dirname "$(t "$IMG_AGENT_JAR")")"
  install -m 0644 "$agent_jar" "$(t "$IMG_AGENT_JAR")"
  put "$IMG_AGENT_UNIT" 0644 <"$agent_unit"
  # Never root. Its account is its User=, or with DynamicUser=yes and no User=, the unit's name;
  # it must be the one the soft-off rule names.
  local dynamic=no unit_name=${IMG_AGENT_UNIT##*/}
  if grep -Eiq '^DynamicUser=(yes|true|on|1)[[:space:]]*$' "$agent_unit"; then
    dynamic=yes
  fi
  user=$(sed -n 's/^User=//p' "$agent_unit" | tail -1)
  if [[ -z $user && $dynamic == no ]]; then
    die "$agent_unit would run the agent as root: give it User= or DynamicUser=yes"
  fi
  user=${user:-${unit_name%.service}}
  if [[ $user == root || $user == 0 ]]; then
    die "$agent_unit would run the agent as root"
  fi
  [[ $user == "$IMG_AGENT_USER" ]] ||
    die "$agent_unit runs the agent as '$user', but the soft-off rule is for '$IMG_AGENT_USER' (IMG_AGENT_USER, lib/common.sh)"
  # A fixed account (not DynamicUser) is made here, with its own group, able to read the journal.
  if [[ $dynamic == no && $offline == no ]]; then
    if ! getent passwd "$user" >/dev/null; then
      useradd --system --user-group --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin "$user"
    fi
    usermod -a -G systemd-journal "$user"
  fi
  units enable "$unit_name"
  # Soft-off: the agent's account may stop PhotonVision and power the board off, nothing else.
  sed "s/@AGENT_USER@/$IMG_AGENT_USER/" "$here/files/coprocessor-agent.rules" |
    put /etc/polkit-1/rules.d/60-coprocessor-agent.rules 0644
  # The root helper: the drive's health and the SPI bootloader's version, into /run for the agent.
  put /usr/local/lib/coprocessor/coprocessor-facts.sh 0755 <"$here/files/coprocessor-facts.sh"
  put_file coprocessor-facts.service /etc/systemd/system/coprocessor-facts.service 0644
  put_file coprocessor-facts.timer /etc/systemd/system/coprocessor-facts.timer 0644
  units enable coprocessor-facts.service coprocessor-facts.timer
}

step_smoketest() {
  local dir temporary=no
  command -v java >/dev/null || die "no java in the image"
  if [[ -n $smoke_dir ]]; then
    mkdir -p "$smoke_dir"
    dir=$(cd "$smoke_dir" && pwd)
  else
    dir=$(mktemp -d /tmp/coproc-smoketest.XXXXXX)
    temporary=yes
  fi
  # A fresh database every run. PhotonVision writes its settings relative to its working
  # directory, so they land outside the image. Its native libraries it extracts under the user's
  # home (WPILib's loader: ~/.wpilib/nativecache), so with root's real home they land in the
  # image, where PhotonVision, run as root on a read-only root, finds them instead of failing to
  # write them (to confirm on a board).
  rm -rf "$dir/photonvision_config"
  mkdir -p "$dir/tmp"
  say "running PhotonVision's smoke test"
  (cd "$dir" && java -Djava.io.tmpdir="$dir/tmp" \
    -jar "$IMG_PV_JAR" --smoketest -n --platform="$BOARD_PV_PLATFORM")
  [[ -f $dir/photonvision_config/photon.sqlite ]] ||
    die "the smoke test passed, but made no photonvision_config/photon.sqlite in $dir"
  [[ -d /root/.wpilib/nativecache ]] ||
    die "the smoke test left no /root/.wpilib/nativecache: PhotonVision would try to write it on the read-only root"
  rm -rf "${dir:?}/tmp"
  if [[ $temporary == yes ]]; then
    rm -rf "$dir"
  fi
}

# The image checks itself before it's packed: whatever the steps above did or missed, a build
# with a usable password or a console autologin fails here.
check_result() {
  local f names dir found=() key settings setting
  for f in "$(t /etc/shadow)" "$(t /etc/shadow-)"; do
    [[ -f $f ]] || continue
    names=$(awk -F: '$2 !~ /^[*!]/ { print $1 }' "$f" | paste -sd ' ')
    [[ -z $names ]] || die "${f#"${target%/}"} leaves a usable password (or none) for: $names"
  done
  for dir in /etc/systemd/system /usr/lib/systemd/system /lib/systemd/system; do
    [[ -d $(t "$dir") ]] || continue
    while IFS= read -r f; do
      found+=("${f#"${target%/}"}")
    done < <(find -H "$(t "$dir")" -path '*getty*' -type f -exec grep -l -e '--autologin' {} +)
  done
  ((${#found[@]} == 0)) || die "a console logs in by itself: ${found[*]}"
  # sshd's effective settings, as it will run them: in the image's chroot only.
  if [[ $offline == no && -x /usr/sbin/sshd ]]; then
    key=$(mktemp -d /tmp/coproc-sshd.XXXXXX)
    ssh-keygen -q -t ed25519 -N "" -f "$key/key"
    mkdir -p /run/sshd
    settings=$(/usr/sbin/sshd -T -o "HostKey=$key/key")
    rm -rf "$key"
    for setting in "passwordauthentication no" "kbdinteractiveauthentication no" \
      "permitrootlogin no" "pubkeyauthentication yes" "x11forwarding no"; do
      grep -qx "$setting" <<<"$settings" || die "sshd's effective settings lack '$setting'"
    done
  fi
}

check_base
if [[ $offline == no ]]; then
  step_packages
fi
step_photonvision
step_network
step_storage
step_logins
step_ssh
step_agent
if [[ $offline == no ]]; then
  step_smoketest
fi
check_result
say "provisioned for $BOARD_TITLE"
if [[ -n $smoke_dir && $offline == no ]]; then
  say "the empty settings database is $smoke_dir/photonvision_config/photon.sqlite"
fi
