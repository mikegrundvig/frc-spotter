#!/usr/bin/env bash
# stamp.sh: writes one computer's identity, stamp, and settings into an image's filesystems.
#
#   coprocessor/image/stamp.sh --computer NAME \
#       --root DIR --coproc DIR --data DIR \
#       --release NAME --recipe-hash HEX --photonvision-version VERSION \
#       [--empty-db FILE --settings-tool COMMAND] \
#       [--repo DIR] [--table FILE] [--stamp-out FILE]
#
# --root, --coproc, and --data are the image's three filesystems, as mounted (stamp-image.sh does
# that in CI), or any directories: it only writes files, so the tests run it without root. Into
# them it writes, from the table (coprocessors/coprocessors.yaml):
#   root    /etc/hostname, /etc/hosts (every computer in the table), /etc/machine-id, the robot
#           network's NetworkManager profile (a static address, 10.TE.AM.<address>/24), the
#           team's SSH public keys (coprocessors/authorized_keys, if the repository has one,
#           without their comments), and /etc/coprocessor/stamp.json
#   coproc  stamp.json again, and a README.txt, for anyone holding the drive
#   data    the folders the root's bind mounts need, and PhotonVision's photon.sqlite built from
#           the computer's committed settings (coprocessors/<computer>/settings/), if it has any
#
# The settings database comes from the settings tool, the robot code's own canonicalizer (its
# command line in the workflow):
#   COMMAND ROWS_DIR EMPTY_DB OUT_DB
# builds OUT_DB from EMPTY_DB (the pinned PhotonVision's own empty database, from its smoke test)
# and the committed rows in ROWS_DIR, and prints the settings hash, lowercase hex, as its only
# output. A computer with no committed settings gets no database (PhotonVision starts with its
# defaults) and an empty settings hash, and needs neither the tool nor the empty database.
#
# Needs bash and yq (mikefarah's, version 4). Stamping the same inputs twice writes the same bytes.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/common.sh
. "$here/lib/common.sh"

repo=$(cd "$here/../.." && pwd)
computer="" root="" coproc="" data="" release="" recipe_hash="" pv_version=""
empty_db="" settings_tool="" table="" stamp_out=""

while (($#)); do
  case $1 in
    --computer) computer=${2:?}; shift 2 ;;
    --root) root=${2:?}; shift 2 ;;
    --coproc) coproc=${2:?}; shift 2 ;;
    --data) data=${2:?}; shift 2 ;;
    --release) release=${2:?}; shift 2 ;;
    --recipe-hash) recipe_hash=${2:?}; shift 2 ;;
    --photonvision-version) pv_version=${2:?}; shift 2 ;;
    --empty-db) empty_db=${2:?}; shift 2 ;;
    --settings-tool) settings_tool=${2:?}; shift 2 ;;
    --repo) repo=${2:?}; shift 2 ;;
    --table) table=${2:?}; shift 2 ;;
    --stamp-out) stamp_out=${2:?}; shift 2 ;;
    -h | --help) sed -n '2,29p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
    *) die "unknown option: $1" ;;
  esac
done

require_yq
table=${table:-$repo/$COPROC_TABLE}
for dir in "$root" "$coproc" "$data"; do
  [[ -n $dir && -d $dir ]] || die "--root, --coproc, and --data must be existing directories"
done
[[ -n $computer ]] || die "--computer is required"
[[ $release =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "--release '$release': letters, digits, '.', '_', '-'"
is_sha256 "$recipe_hash" || die "--recipe-hash must be a sha256 (64 lowercase hex digits)"
[[ $pv_version =~ ^[A-Za-z0-9._+-]+$ ]] || die "--photonvision-version '$pv_version' isn't a version string"

check_table "$table"
export NAME=$computer
[[ $(yq -r '[.computers[] | select(.name == strenv(NAME))] | length' "$table") == 1 ]] ||
  die "no computer named '$computer' in $table"
team=$(table_get '.team' "$table")
prefix=$(team_prefix "$team")
address="$prefix.$(yq -r '.computers[] | select(.name == strenv(NAME)) | .address' "$table")"
gateway="$prefix.$GATEWAY_OCTET"

# Writes stdin to a file, with a mode, replacing what was there.
put() {
  mkdir -p "$(dirname "$1")"
  cat >"$1.stamp-new"
  chmod "$2" "$1.stamp-new"
  mv -f "$1.stamp-new" "$1"
}

# --- Settings first: the stamp carries their hash. ---
mkdir -p "$data/$DATA_PV_CONFIG" "$data/$DATA_JOURNAL" "$data/$DATA_SSH"
chmod 0755 "$data/$DATA_PV_CONFIG"
chmod 2755 "$data/$DATA_JOURNAL"
chmod 0700 "$data/$DATA_SSH"
rows="$repo/$COPROC_SETTINGS_ROOT/$computer/settings"
db="$data/$DATA_PV_CONFIG/photon.sqlite"
rm -f "$db"
if [[ -d $rows && -n $(find "$rows" -type f -print -quit) ]]; then
  [[ -n $settings_tool ]] || die "$computer has committed settings: --settings-tool is required"
  [[ -f $empty_db ]] || die "$computer has committed settings: --empty-db needs PhotonVision's empty database"
  read -r -a tool <<<"$settings_tool"
  output=$("${tool[@]}" "$rows" "$empty_db" "$db") || die "the settings tool failed for $computer"
  # The hash is its last line. Anything before it is passed on, not taken for the hash: a JVM
  # prints its own warnings to standard output (one about cgroups, in some containers).
  settings_hash=$(tail -n 1 <<<"$output")
  if [[ $output == *$'\n'* ]]; then
    say "the settings tool also said: $(sed '$d' <<<"$output")"
  fi
  is_sha256 "$settings_hash" || die "the settings tool printed '$settings_hash', not a sha256"
  [[ -f $db ]] || die "the settings tool made no database at $db"
  chmod 0644 "$db"
else
  settings_hash=""
  say "$computer has no committed settings: PhotonVision starts with its defaults"
fi

# --- Identity. ---
put "$root/etc/hostname" 0644 <<<"$computer"
{
  cat <<EOF
# Written at stamping (coprocessor/image/stamp.sh), from $COPROC_TABLE.
127.0.0.1	localhost
::1	localhost ip6-localhost ip6-loopback
ff02::1	ip6-allnodes
ff02::2	ip6-allrouters

# The robot's coprocessors. This computer's own name resolves to its robot address.
EOF
  yq -r '.computers[] | (.address | tostring) + " " + .name' "$table" | while read -r last name; do
    printf '%s.%s\t%s\n' "$prefix" "$last" "$name"
  done
} | put "$root/etc/hosts" 0644
# A fixed machine ID. With the root read-only, systemd would otherwise make a new one at every
# boot, treat every boot as the first, and start the journal afresh under the new ID, leaving the
# boot before a power cut out of `journalctl -b -1`. Derived from the computer, so a stamp repeats.
put "$root/etc/machine-id" 0444 <<<"$(derived_hex "coprocessor machine-id/$team/$computer")"
put "$root$IMG_CONNECTION" 0600 <<EOF
# Written at stamping (coprocessor/image/stamp.sh): $computer's address on the robot network.
# FRC's static range for on-robot devices is 10.TE.AM.6 to .19, netmask 255.255.255.0, gateway
# 10.TE.AM.4 (docs.wpilib.org, "IP Configurations"). Any Ethernet port takes it.
[connection]
id=robot
uuid=$(derived_uuid "coprocessor connection/$team/$computer")
type=ethernet
autoconnect=true
autoconnect-priority=100
autoconnect-retries=0

[ethernet]

[ipv4]
method=manual
address1=$address/$NETMASK_BITS,$gateway
# Duplicate-address detection: a second drive stamped for this computer stays off the address.
dad-timeout=3000
may-fail=false

[ipv6]
method=disabled
EOF

# --- SSH: the team's public keys, if the repository has any, without their comments (often a
# name or an email address, and the images are public). Root-owned and read-only, which sshd
# accepts; no key means no SSH login. ---
keys="$repo/$COPROC_AUTHORIZED_KEYS"
ssh_dir="$root/home/$IMG_SSH_USER/.ssh"
if [[ -f $keys ]]; then
  if grep -q 'PRIVATE KEY' "$keys"; then
    die "$COPROC_AUTHORIZED_KEYS holds a private key: only public keys belong there (it ships in every image)"
  fi
  if grep -Ev '^[[:space:]]*(#|$)' "$keys" | grep -Evq '^(ssh-(ed25519|rsa)|ecdsa-sha2-nistp(256|384|521)|sk-(ssh-ed25519|ecdsa-sha2-nistp256)@openssh\.com) '; then
    die "$COPROC_AUTHORIZED_KEYS has a line that isn't an SSH public key"
  fi
  mkdir -p "$ssh_dir"
  chmod 0755 "$ssh_dir"
  awk '!/^[[:space:]]*(#|$)/ { print $1, $2 }' "$keys" | put "$ssh_dir/authorized_keys" 0644
else
  rm -f "$ssh_dir/authorized_keys"
  say "no $COPROC_AUTHORIZED_KEYS: $computer takes no SSH logins"
fi

# --- The stamp. ---
# shellcheck disable=SC2016 # $c is yq's variable, not the shell's
stamp=$(
  ADDRESS=$address RELEASE=$release RECIPE_HASH=$recipe_hash PV_VERSION=$pv_version \
    SETTINGS_HASH=$settings_hash DEFAULT_PORT=$DEFAULT_AGENT_PORT \
    yq -o=json -I=2 '
      (.computers[] | select(.name == strenv(NAME))) as $c |
      {
        "name": $c.name,
        "team": .team,
        "address": strenv(ADDRESS),
        "board": $c.board,
        "cameras": (($c.cameras // []) | map(tostring)),
        "release": strenv(RELEASE),
        "recipeHash": strenv(RECIPE_HASH),
        "photonvisionVersion": strenv(PV_VERSION),
        "settingsHash": strenv(SETTINGS_HASH),
        "agentPort": ($c.agentPort // .agentPort // env(DEFAULT_PORT))
      }' "$table"
)
put "$root$IMG_STAMP" 0644 <<<"$stamp"
put "$coproc/stamp.json" 0644 <<<"$stamp"
put "$coproc/README.txt" 0644 <<EOF
This drive is $computer, $address on team $team's robot.

Release $release, PhotonVision $pv_version. stamp.json says exactly what's on it.
Put it only in $computer's board: two drives with the same image would share an address.
EOF
if [[ -n $stamp_out ]]; then
  put "$stamp_out" 0644 <<<"$stamp"
fi
say "stamped $computer: $address, settings ${settings_hash:-none}"
