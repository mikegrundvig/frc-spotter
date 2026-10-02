# shellcheck shell=bash disable=SC2034 # the scripts that source this use its values
# Shared by the image scripts: the names, sizes, and paths every step agrees on, and the readers of
# the coprocessor table and the version lock. Sourced, never run.
#
# provision.sh sources this inside the image's chroot, where there's no yq: only the functions that
# say so need it.

# ---------------------------------------------------------------------------------------------
# Inputs from the rest of the repository, relative to its root. One place, so a moved file is a
# one-line change.
# ---------------------------------------------------------------------------------------------
COPROC_TABLE=coprocessors/coprocessors.yaml       # the team's computers
COPROC_SETTINGS_ROOT=coprocessors                 # <root>/<computer>/settings/: committed rows
COPROC_AUTHORIZED_KEYS=coprocessors/authorized_keys  # the team's SSH public keys (optional)
COPROC_LOCK=coprocessor/photonvision.lock          # PhotonVision's version, jar, base images
COPROC_VENDORDEP=vendordeps/photonlib.json         # the version the robot code is built against
# The recipe hash is the build's: ./gradlew -q coprocessorRecipeHash (RecipeHash, coprocessor/common).
COPROC_RECIPE_HASH_FILE=build/coprocessor/recipe-hash.txt
# The health agent, built by :coprocessor-agent, and its systemd unit.
COPROC_AGENT_JAR=coprocessor/agent/build/libs/coprocessor-agent.jar
COPROC_AGENT_UNIT=coprocessor/agent/coprocessor-agent.service

# Where values sit in the lock (yq paths; yq reads YAML, and JSON as YAML). BOARD is the board.
LOCK_VERSION_PATH='.version'
LOCK_JAR_URL_PATH='.jar.url'
LOCK_JAR_SHA256_PATH='.jar.sha256'
LOCK_IMAGE_URL_PATH='.images[strenv(BOARD)].url'
LOCK_IMAGE_SHA256_PATH='.images[strenv(BOARD)].sha256'

# ---------------------------------------------------------------------------------------------
# The drive's layout, the same on every board (the board files hold what differs).
# ---------------------------------------------------------------------------------------------
COPROC_LABEL=COPROC          # small FAT partition: a copy of the stamp, readable on Windows
COPROC_MIB=32
DATA_LABEL=coproc-data       # ext4: everything that's written while the computer runs
DATA_MIB=${COPROC_DATA_MIB:-8192}  # the tests set COPROC_DATA_MIB to keep their images small
ALIGN_MIB=16                 # partitions start on 16 MiB, as Armbian's own do

# On the image.
IMG_DATA=/data
IMG_STAMP=/etc/coprocessor/stamp.json
IMG_PV_DIR=/opt/photonvision
IMG_PV_JAR=/opt/photonvision/photonvision.jar
IMG_PV_CONFIG=/opt/photonvision/photonvision_config  # PhotonVision's folder, relative to its working directory
IMG_JOURNAL=/var/log/journal
IMG_AGENT_JAR=/opt/coprocessor/coprocessor-agent.jar
IMG_AGENT_UNIT=/etc/systemd/system/coprocessor-agent.service
IMG_SSH_USER=photon             # PhotonVision's image's login
# The account the health agent runs as: its unit's User=, or its unit's name with DynamicUser=yes.
# The soft-off rule (files/coprocessor-agent.rules) lets this account, and no other, stop
# PhotonVision and power the board off.
IMG_AGENT_USER=coprocessor-agent
IMG_CONNECTION=/etc/NetworkManager/system-connections/robot.nmconnection
# On /data.
DATA_PV_CONFIG=photonvision_config
DATA_JOURNAL=journal
DATA_SSH=ssh

# The boards an image can be built for: one file each in boards/.
BOARDS="orangepi-5 orangepi-5b orangepi-5-plus orangepi-5-pro orangepi-5-max"

# The robot network (docs.wpilib.org, "IP Configurations"): static addresses for on-robot
# devices are 10.TE.AM.6 to 10.TE.AM.19, netmask 255.255.255.0, gateway 10.TE.AM.4.
ADDRESS_MIN=6
ADDRESS_MAX=19
NETMASK_BITS=24
GATEWAY_OCTET=4
DEFAULT_AGENT_PORT=5808

# ---------------------------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------------------------
die() {
  printf '%s: %s\n' "${0##*/}" "$*" >&2
  exit 1
}

say() {
  printf '%s: %s\n' "${0##*/}" "$*" >&2
}

# Fails unless yq is mikefarah's yq, version 4 (GitHub's Ubuntu runners have it). Ubuntu's own
# "yq" package is a different program with a different language.
require_yq() {
  command -v yq >/dev/null 2>&1 || die "needs yq (mikefarah's, version 4): https://github.com/mikefarah/yq"
  case "$(yq --version 2>&1)" in
    *mikefarah*" v4."* | *mikefarah*" version 4."*) ;;
    *) die "needs mikefarah's yq version 4, found: $(yq --version 2>&1 | head -1)" ;;
  esac
}

is_sha256() {
  [[ $1 =~ ^[0-9a-f]{64}$ ]]
}

is_board() {
  local b
  for b in $BOARDS; do
    [[ $b == "$1" ]] && return 0
  done
  return 1
}

# A computer's name becomes its hostname: lowercase letters, digits, and hyphens, starting and
# ending with a letter or digit.
is_hostname() {
  [[ $1 =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]]
}

# "10.TE.AM" for a team number: TE is all but the last two digits, AM the last two.
team_prefix() {
  printf '10.%d.%d' $((10#$1 / 100)) $((10#$1 % 100))
}

# 32 hex digits derived from text: stable, so stamping the same computer twice writes the same files.
derived_hex() {
  printf '%s' "$1" | sha256sum | cut -c1-32
}

# A UUID (version 5 layout) derived from text.
derived_uuid() {
  local h variant
  h=$(printf '%s' "$1" | sha256sum | cut -c1-32)
  variant=$(printf '%x' $(((16#${h:16:1} & 3) | 8)))
  printf '%s-%s-5%s-%s%s-%s' "${h:0:8}" "${h:8:4}" "${h:13:3}" "$variant" "${h:17:3}" "${h:20:12}"
}

# Reads a value from the table (needs yq). Prints nothing for a missing value.
table_get() {
  yq -r "$1 // \"\"" "$2"
}

# Reads a value from the lock (needs yq). The board, for per-board values, comes in BOARD.
lock_get() {
  yq -p yaml -o yaml -r "$1 // \"\"" "$2"
}

# A port number: 1 to 65535, written without leading zeros.
is_port() {
  [[ $1 =~ ^[1-9][0-9]{0,4}$ ]] || return 1
  ((10#$1 <= 65535))
}

# Checks the whole table (needs yq): the team, and every computer's name, address, board, port,
# and cameras. Numbers must be written plainly (no leading zeros, which bash would read as
# octal), and are compared as numbers. Prints nothing; dies on the first problem, naming it.
check_table() {
  local table=$1 team port count i name address board kind cameras
  local -A names=() addresses=()
  [[ -f $table ]] || die "no table at $table"
  team=$(table_get '.team' "$table")
  if ! [[ $team =~ ^[1-9][0-9]{0,4}$ ]] || ((10#$team > 25599)); then
    die "$table: team '$team' isn't a team number (1 to 25599, no leading zeros); set your team's"
  fi
  port=$(table_get '.agentPort' "$table")
  [[ -z $port ]] || is_port "$port" || die "$table: agentPort '$port' isn't a port (1 to 65535)"
  [[ $(yq -r '.computers | tag' "$table") == '!!seq' ]] || die "$table: computers must be a list"
  count=$(yq -r '.computers | length' "$table")
  ((count > 0)) || die "$table: no computers listed"
  for ((i = 0; i < count; i++)); do
    name=$(table_get ".computers[$i].name" "$table")
    address=$(table_get ".computers[$i].address" "$table")
    board=$(table_get ".computers[$i].board" "$table")
    port=$(table_get ".computers[$i].agentPort" "$table")
    is_hostname "$name" ||
      die "$table: '$name' can't be a computer's name: lowercase letters, digits, and hyphens only"
    if ! [[ $address =~ ^[1-9][0-9]?$ ]] || ((10#$address < ADDRESS_MIN || 10#$address > ADDRESS_MAX)); then
      die "$table: $name's address '$address' is outside FRC's range for on-robot devices, $ADDRESS_MIN to $ADDRESS_MAX (no leading zeros)"
    fi
    is_board "$board" || die "$table: $name's board '$board' isn't one of: $BOARDS"
    [[ -z $port ]] || is_port "$port" || die "$table: $name's agentPort '$port' isn't a port (1 to 65535)"
    kind=$(yq -r ".computers[$i].cameras | tag" "$table")
    if [[ $kind != '!!null' ]]; then
      [[ $kind == '!!seq' ]] || die "$table: $name's cameras must be a list of names"
      cameras=$(yq -r "[.computers[$i].cameras[] | select(tag == \"!!map\" or tag == \"!!seq\" or tag == \"!!null\" or (tostring | test(\"^\$|/\")) or (tostring | length) > 64)] | length" "$table")
      ((cameras == 0)) || die "$table: $name's cameras must each be a name: not empty, no '/', at most 64 characters"
    fi
    [[ -z ${names[$name]:-} ]] || die "$table: two computers are named $name"
    names[$name]=1
    [[ -z ${addresses[$((10#$address))]:-} ]] ||
      die "$table: two computers have the address $((10#$address)) ($name and ${addresses[$((10#$address))]})"
    addresses[$((10#$address))]=$name
  done
}
