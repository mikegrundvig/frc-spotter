#!/usr/bin/env bash
# layout.sh: adds the COPROC and data partitions to a provisioned image file, after its root.
#
#   coprocessor/image/layout.sh --board BOARD IMAGE.img
#
# The drive then holds, in order: the read-only root (the board's base image's own partition); COPROC,
# a small FAT partition for a copy of the stamp, which Windows can open; and the data partition
# (ext4, about 8 GB), everything written while the computer runs. The rest of the drive stays
# unpartitioned, so nothing is resized at first boot. provision.sh writes the matching /etc/fstab,
# by label.
#
# Works on the image file itself, with no root and no loop devices: sfdisk (util-linux), mkfs.fat
# (dosfstools), and mkfs.ext4 (e2fsprogs). Safe to rerun: an image already laid out is left as it is.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/common.sh
. "$here/lib/common.sh"

board=""
image=""
while (($#)); do
  case $1 in
    --board) board=${2:?}; shift 2 ;;
    -h | --help) sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
    -*) die "unknown option: $1" ;;
    *) image=$1; shift ;;
  esac
done
is_board "$board" || die "--board must be one of: $BOARDS"
[[ -f $image ]] || die "no image file at '$image'"
# shellcheck source=boards/orangepi-5.env
. "$here/boards/$board.env"
for tool in sfdisk mkfs.ext4; do
  command -v "$tool" >/dev/null || die "needs $tool"
done
mkfs_fat=$(command -v mkfs.fat || command -v mkfs.vfat || true)
[[ -n $mkfs_fat ]] || die "needs mkfs.fat (dosfstools)"

dump=$(sfdisk --dump "$image") || die "$image has no partition table sfdisk can read"
table=$(sed -n 's/^label: *//p' <<<"$dump")
sector=$(sed -n 's/^sector-size: *//p' <<<"$dump")
sector=${sector:-512}
[[ $table == dos || $table == gpt ]] || die "$image: partition table '$table', expected dos or gpt"
((sector == 512)) || die "$image: $sector-byte sectors, expected 512"

# One "start size type" line per partition, in table order.
parts=$(awk -F'[:,]' '/: start=/ {
  start = ""; size = ""; type = ""
  for (i = 2; i <= NF; i++) {
    split($i, kv, "=")
    gsub(/[[:space:]]/, "", kv[1]); gsub(/[[:space:]"]/, "", kv[2])
    if (kv[1] == "start") start = kv[2]
    else if (kv[1] == "size") size = kv[2]
    else if (kv[1] == "type") type = kv[2]
  }
  print start, size, type
}' <<<"$dump")
count=$(grep -c . <<<"$parts" || true)

mib=$((1024 * 1024 / sector))
align=$((ALIGN_MIB * mib))
coproc_size=$((COPROC_MIB * mib))
data_size=$((DATA_MIB * mib))
if [[ $table == dos ]]; then
  coproc_type=e  # W95 FAT16 (LBA)
  data_type=83   # Linux
else
  coproc_type=EBD0A0A2-B9E5-4433-87C0-68B6B72699C7  # Microsoft basic data
  data_type=0FC63DAF-8483-4772-8E79-3D69D8477DE4    # Linux filesystem
fi

if ((count == 3)); then
  read -r _ size2 _ < <(sed -n 2p <<<"$parts")
  read -r _ size3 _ < <(sed -n 3p <<<"$parts")
  if ((size2 == coproc_size && size3 == data_size)); then
    say "$image is already laid out"
    exit 0
  fi
  die "$image has three partitions, but not this layout's sizes"
fi
((count == BOARD_ROOT_PARTITION)) ||
  die "$image has $count partitions; $board's base image should have its root alone, as partition $BOARD_ROOT_PARTITION"

read -r root_start root_size _ < <(sed -n "${BOARD_ROOT_PARTITION}p" <<<"$parts")
align_up() {
  echo $((($1 + align - 1) / align * align))
}
coproc_start=$(align_up $((root_start + root_size)))
data_start=$(align_up $((coproc_start + coproc_size)))
end=$((data_start + data_size))
# GPT keeps a backup table in the drive's last sectors: leave a MiB for it.
if [[ $table == gpt ]]; then
  end=$((end + mib))
fi
bytes=$((end * sector))
if (($(stat -c %s "$image") < bytes)); then
  truncate -s "$bytes" "$image"
  if [[ $table == gpt ]]; then
    sfdisk --quiet --relocate gpt-bak-std "$image"
  fi
fi

say "adding $COPROC_LABEL ($COPROC_MIB MiB) and $DATA_LABEL ($DATA_MIB MiB) after the root"
if [[ $table == gpt ]]; then
  printf 'start=%s, size=%s, type=%s, name="%s"\nstart=%s, size=%s, type=%s, name="%s"\n' \
    "$coproc_start" "$coproc_size" "$coproc_type" "$COPROC_LABEL" \
    "$data_start" "$data_size" "$data_type" "$DATA_LABEL"
else
  printf 'start=%s, size=%s, type=%s\nstart=%s, size=%s, type=%s\n' \
    "$coproc_start" "$coproc_size" "$coproc_type" "$data_start" "$data_size" "$data_type"
fi | sfdisk --quiet --append --no-reread --no-tell-kernel "$image"

# Filesystems, written straight into the image at each partition's offset. The data partition's
# inode tables and journal are written now, so the kernel doesn't do it on the board.
"$mkfs_fat" -F 16 -n "$COPROC_LABEL" --offset="$coproc_start" "$image" $((coproc_size * sector / 1024)) >/dev/null
mkfs.ext4 -q -F -L "$DATA_LABEL" \
  -E "offset=$((data_start * sector)),lazy_itable_init=0,lazy_journal_init=0,nodiscard" \
  "$image" "$((data_size * sector / 1024))k"
say "laid out $image ($table): root, then $COPROC_LABEL at sector $coproc_start, $DATA_LABEL at sector $data_start"
