#!/usr/bin/env bash
# stamp-image.sh: mounts a laid-out image's three partitions and runs stamp.sh into them.
#
#   sudo coprocessor/image/stamp-image.sh --board BOARD IMAGE.img -- STAMP_OPTIONS...
#
# STAMP_OPTIONS are stamp.sh's, less --root, --coproc, and --data, which this supplies. Needs root
# and loop devices: CI runs it; the tests run stamp.sh itself.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/common.sh
. "$here/lib/common.sh"

board=""
image=""
while (($#)); do
  case $1 in
    --board) board=${2:?}; shift 2 ;;
    --) shift; break ;;
    -*) die "unknown option: $1" ;;
    *) image=$1; shift ;;
  esac
done
is_board "$board" || die "--board must be one of: $BOARDS"
[[ -f $image ]] || die "no image file at '$image'"
((EUID == 0)) || die "needs root, to mount the image's partitions"
# shellcheck source=boards/orangepi-5.env
. "$here/boards/$board.env"

mnt=$(mktemp -d)
loop=""
cleanup() {
  local dir
  for dir in data coproc root; do
    if mountpoint -q "$mnt/$dir"; then
      umount "$mnt/$dir"
    fi
  done
  if [[ -n $loop ]]; then
    losetup --detach "$loop"
  fi
  rm -rf "$mnt"
}
trap cleanup EXIT

loop=$(losetup --find --show --partscan "$image")
udevadm settle 2>/dev/null || true
root_dev="${loop}p$BOARD_ROOT_PARTITION"
coproc_dev="${loop}p$((BOARD_ROOT_PARTITION + 1))"
data_dev="${loop}p$((BOARD_ROOT_PARTITION + 2))"
[[ $(blkid -o value -s LABEL "$coproc_dev") == "$COPROC_LABEL" ]] ||
  die "partition $((BOARD_ROOT_PARTITION + 1)) isn't $COPROC_LABEL: run layout.sh first"
[[ $(blkid -o value -s LABEL "$data_dev") == "$DATA_LABEL" ]] ||
  die "partition $((BOARD_ROOT_PARTITION + 2)) isn't $DATA_LABEL: run layout.sh first"

mkdir -p "$mnt/root" "$mnt/coproc" "$mnt/data"
mount "$root_dev" "$mnt/root"
mount "$coproc_dev" "$mnt/coproc"
mount "$data_dev" "$mnt/data"
"$here/stamp.sh" --root "$mnt/root" --coproc "$mnt/coproc" --data "$mnt/data" "$@"
sync
umount "$mnt/data" "$mnt/coproc" "$mnt/root"
# Each filesystem checked, unmounted, without changing it: a stamp that left one damaged fails here.
e2fsck -fn "$root_dev" >/dev/null
fsck.fat -n "$coproc_dev" >/dev/null
e2fsck -fn "$data_dev" >/dev/null
say "stamped $image"
