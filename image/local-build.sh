#!/usr/bin/env bash
# local-build.sh: builds the release's images on a mentor's Linux machine, the way CI does, in a
# privileged container (README.md, "Building on your own Linux machine").
#
#   coprocessor/image/local-build.sh --agent-jar PATH --agent-unit PATH \
#       --settings-tool COMMAND [--recipe-hash HEX] [--release NAME] [--out DIR] \
#       [--outside-container]
#
# The recipe hash is the build's, made beside the agent's jar (./gradlew :coprocessor-agent:jar
# coprocessorRecipeHash): by default it's read from build/coprocessor/recipe-hash.txt.
#
# It mounts images and bind-mounts the machine's /dev into a chroot, so it refuses to run outside
# a container unless told --outside-container.
#
# From the repository's root, as root, with: bash, curl, xz, sfdisk, losetup, mount, e2fsck,
# resize2fs, mkfs.ext4, mkfs.fat, yq (mikefarah's, version 4), git, and the settings tool's Java.
# On a machine that isn't arm64, the chroot's ARM programs also need qemu-user-static registered
# in the kernel's binfmt_misc with its "F" flag (README.md says how).
#
# The same steps as the workflow: plan.sh's checks; per board, the base image and the jar checked
# against the lock, the root grown, provision.sh run in a chroot of it; per computer, layout.sh
# and stamp-image.sh; then manifest.sh. Downloads are kept in OUT/downloads, by checksum.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo=$(cd "$here/../.." && pwd)
# shellcheck source=lib/common.sh
. "$here/lib/common.sh"

agent_jar="" agent_unit="" settings_tool="" release="" out="$repo/build/coprocessor-image/local"
recipe_hash=""
outside_container=no
while (($#)); do
  case $1 in
    --agent-jar) agent_jar=$(realpath "${2:?}"); shift 2 ;;
    --agent-unit) agent_unit=$(realpath "${2:?}"); shift 2 ;;
    --settings-tool) settings_tool=${2:?}; shift 2 ;;
    --release) release=${2:?}; shift 2 ;;
    --recipe-hash) recipe_hash=${2:?}; shift 2 ;;
    --out) out=${2:?}; shift 2 ;;
    --outside-container) outside_container=yes; shift ;;
    *) die "unknown option: $1" ;;
  esac
done
((EUID == 0)) || die "needs root (in a privileged container): it mounts images"
if [[ $outside_container == no && ! -e /run/.containerenv && ! -e /.dockerenv ]]; then
  die "not in a container (Docker or Podman): run it in one (README.md), or pass --outside-container"
fi
[[ -f $agent_jar && -f $agent_unit ]] || die "--agent-jar and --agent-unit: the built agent and its unit"
[[ -n $settings_tool ]] || die "--settings-tool is required"
for tool in curl xz sfdisk losetup mount e2fsck resize2fs mkfs.ext4 git; do
  command -v "$tool" >/dev/null || die "needs $tool"
done
require_yq
if [[ $(uname -m) != aarch64 ]] && ! grep -qs '^flags:.*F' /proc/sys/fs/binfmt_misc/qemu-aarch64; then
  die "this machine isn't arm64, and qemu-aarch64 isn't registered in binfmt_misc with the F flag"
fi
mkdir -p "$out/downloads"
out=$(cd "$out" && pwd)
cd "$repo"

# The workflow's plan, as shell variables.
if [[ -z $recipe_hash ]]; then
  [[ -f $repo/$COPROC_RECIPE_HASH_FILE ]] ||
    die "no $COPROC_RECIPE_HASH_FILE: run ./gradlew coprocessorRecipeHash first, or pass --recipe-hash"
  recipe_hash=$(head -c 64 "$repo/$COPROC_RECIPE_HASH_FILE")
fi
plan=$(GITHUB_OUTPUT='' "$here/plan.sh" --recipe-hash "$recipe_hash" --release "$release" \
  --sha "$(git rev-parse HEAD)")
get() {
  sed -n "s/^$1=//p" <<<"$plan"
}
team=$(get team) version=$(get version) recipe_hash=$(get recipe-hash) release=$(get release)
jar_url=$(get jar-url) jar_sha=$(get jar-sha256) boards=$(get boards) computers=$(get computers)

# Downloads a URL once, into OUT/downloads, and checks it.
fetch() {
  local url=$1 sum=$2 file="$out/downloads/$2-${1##*/}"
  if [[ ! -f $file ]]; then
    curl --fail --location --silent --show-error --retry 3 --output "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  echo "$sum  $file" | sha256sum --check --strict --quiet || die "$url doesn't match the lock's sha256"
  printf '%s' "$file"
}

mnt="" loop=""
# Unmounts the chroot and detaches the image. Fails, saying what's left, if it can't: the next
# step would otherwise work on an image that's still mounted.
cleanup() {
  local status=0
  if [[ -n $mnt ]]; then
    if [[ -e $mnt/etc/resolv.conf.coproc-saved ]]; then
      mv -f "$mnt/etc/resolv.conf.coproc-saved" "$mnt/etc/resolv.conf" || status=1
    fi
    if mountpoint -q "$mnt"; then
      umount --recursive "$mnt" || { say "couldn't unmount $mnt"; status=1; }
    fi
    if ((status == 0)); then
      rmdir "$mnt" || status=1
    fi
  fi
  if [[ -n $loop ]] && ((status == 0)); then
    losetup --detach "$loop" || { say "couldn't detach $loop"; status=1; }
  fi
  if ((status == 0)); then
    mnt="" loop=""
  fi
  return "$status"
}
on_exit() {
  cleanup || say "left mounted: ${mnt:-nothing}; attached: ${loop:-nothing}. Unmount by hand before rerunning"
}
trap on_exit EXIT

pv_jar=$(fetch "$jar_url" "$jar_sha")
while IFS= read -r row <&3; do
  board=$(yq -p json -o yaml -r '.board' <<<"$row")
  # shellcheck source=boards/orangepi-5.env
  . "$here/boards/$board.env"
  common="$out/common-$board.img"
  say "$board: the common image"
  base=$(fetch "$(yq -p json -o yaml -r '.url' <<<"$row")" "$(yq -p json -o yaml -r '.sha256' <<<"$row")")
  xz -dc "$base" >"$common"
  # The root grown by the headroom PhotonVision's own build gives it.
  truncate -s "+$(yq -p json -o yaml -r '.minimumFreeMb' <<<"$row")M" "$common"
  if [[ $(sfdisk --dump "$common" | sed -n 's/^label: *//p') == gpt ]]; then
    sfdisk --quiet --relocate gpt-bak-std "$common"
  fi
  echo ', +' | sfdisk --quiet --no-reread --no-tell-kernel -N "$BOARD_ROOT_PARTITION" "$common"
  loop=$(losetup --find --show --partscan "$common")
  root_dev="${loop}p$BOARD_ROOT_PARTITION"
  e2fsck -pf "$root_dev"
  resize2fs "$root_dev"
  # A chroot of the image's root, as photon-image-runner makes one: the repository at /tmp/build.
  mnt=$(mktemp -d)
  mount "$root_dev" "$mnt"
  mount -t proc proc "$mnt/proc"
  mount -t sysfs sys "$mnt/sys"
  mount --rbind /dev "$mnt/dev"
  # So unmounting the chroot's /dev never reaches the machine's own.
  mount --make-rslave "$mnt/dev"
  mount -t tmpfs tmpfs "$mnt/run"
  mkdir -p "$mnt/tmp/build"
  mount --bind "$repo" "$mnt/tmp/build"
  mv -f "$mnt/etc/resolv.conf" "$mnt/etc/resolv.conf.coproc-saved"
  cp /etc/resolv.conf "$mnt/etc/resolv.conf"
  mkdir -p "$repo/build/coprocessor-image/local-inputs"
  cp "$pv_jar" "$repo/build/coprocessor-image/local-inputs/photonvision.jar"
  cp "$agent_jar" "$repo/build/coprocessor-image/local-inputs/coprocessor-agent.jar"
  cp "$agent_unit" "$repo/build/coprocessor-image/local-inputs/coprocessor-agent.service"
  chroot "$mnt" bash -c "cd /tmp/build && bash coprocessor/image/provision.sh --board $board \
    --photonvision-jar build/coprocessor-image/local-inputs/photonvision.jar \
    --photonvision-sha256 $jar_sha \
    --agent-jar build/coprocessor-image/local-inputs/coprocessor-agent.jar \
    --agent-unit build/coprocessor-image/local-inputs/coprocessor-agent.service \
    --smoketest-dir build/coprocessor-image/local-smoketest"
  cp "$repo/build/coprocessor-image/local-smoketest/photonvision_config/photon.sqlite" \
    "$out/empty-photon-$board.sqlite"
  # Zeros where nothing is, so the image compresses as CI's does.
  cat /dev/zero >"$mnt/coproc-zeros" 2>/dev/null || true
  rm -f "$mnt/coproc-zeros"
  cleanup || die "couldn't unmount $board's common image: stopping"
done 3< <(yq -p json -o=json -I=0 '.[]' <<<"$boards")

mkdir -p "$out/release"
while IFS= read -r row <&3; do
  name=$(yq -p json -o yaml -r '.name' <<<"$row")
  board=$(yq -p json -o yaml -r '.board' <<<"$row")
  image="$out/$team-$name-pv$version-$release.img"
  say "$name: stamping"
  cp --sparse=always "$out/common-$board.img" "$image"
  "$here/layout.sh" --board "$board" "$image"
  "$here/stamp-image.sh" --board "$board" "$image" -- \
    --computer "$name" --release "$release" --recipe-hash "$recipe_hash" \
    --photonvision-version "$version" --empty-db "$out/empty-photon-$board.sqlite" \
    --settings-tool "$settings_tool" --stamp-out "$out/release/$(basename "$image" .img).stamp.json"
  xz -T0 -c "$image" >"$out/release/${image##*/}.xz"
  rm -f "$image"
done 3< <(yq -p json -o=json -I=0 '.[]' <<<"$computers")
"$here/manifest.sh" "$out/release"
say "the release's files are in $out/release"
