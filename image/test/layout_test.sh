# shellcheck shell=bash
# layout.sh on small image files (the data partition shrunk to 16 MiB): the partitions it adds,
# their filesystems, and a second run.

# An image whose only partition, the root, starts at 16 MiB, as Armbian's do. $1 is dos or gpt.
make_base_image() {
  need sfdisk
  command -v mkfs.fat >/dev/null || command -v mkfs.vfat >/dev/null || skip "needs mkfs.fat"
  need mkfs.ext4 blkid
  truncate -s 48M "$TMP/base.img"
  printf 'label: %s\nstart=32768, size=61440, type=%s\n' "$1" \
    "$([[ $1 == gpt ]] && echo 0FC63DAF-8483-4772-8E79-3D69D8477DE4 || echo 83)" |
    sfdisk --quiet "$TMP/base.img"
}

# "start size type" for partition N.
part() {
  sfdisk --dump "$TMP/base.img" | awk -F'[:,]' -v n="$1" '/: start=/ && ++i == n {
    for (f = 2; f <= NF; f++) { split($f, kv, "="); gsub(/[[:space:]"]/, "", kv[1]); gsub(/[[:space:]"]/, "", kv[2]); v[kv[1]] = kv[2] }
    print v["start"], v["size"], v["type"]
  }'
}

# blkid's view of what's at a sector.
probe() {
  blkid -p -o value -s "$2" -O $(($1 * 512)) "$TMP/base.img"
}

check_laid_out() {
  local start size type
  read -r start size type < <(part 2)
  assert_eq "$start" 98304 "COPROC's start: the root's end, rounded up to 16 MiB"
  assert_eq "$size" 65536 "COPROC's size (32 MiB)"
  assert_eq "$type" "$1" "COPROC's type"
  assert_eq "$(probe "$start" TYPE)" vfat
  assert_eq "$(probe "$start" LABEL)" COPROC
  read -r start size type < <(part 3)
  assert_eq "$start" 163840 "data's start, on 16 MiB"
  assert_eq "$size" 32768 "data's size (16 MiB in the tests)"
  assert_eq "$type" "$2" "data's type"
  assert_eq "$(probe "$start" TYPE)" ext4
  assert_eq "$(probe "$start" LABEL)" coproc-data
}

test_layout_adds_coproc_and_data_after_the_root_dos() {
  make_base_image dos
  COPROC_DATA_MIB=16 "$IMAGE/layout.sh" --board orangepi-5 "$TMP/base.img"
  check_laid_out e 83
  assert_eq "$(part 1)" "32768 61440 83" "the root, untouched"
}

test_layout_adds_coproc_and_data_after_the_root_gpt() {
  make_base_image gpt
  COPROC_DATA_MIB=16 "$IMAGE/layout.sh" --board orangepi-5 "$TMP/base.img"
  check_laid_out EBD0A0A2-B9E5-4433-87C0-68B6B72699C7 0FC63DAF-8483-4772-8E79-3D69D8477DE4
  sfdisk --verify "$TMP/base.img" >/dev/null 2>&1 || fail "sfdisk finds the GPT damaged"
}

test_layout_leaves_a_laid_out_image_alone() {
  make_base_image dos
  COPROC_DATA_MIB=16 "$IMAGE/layout.sh" --board orangepi-5 "$TMP/base.img"
  local first
  first=$(sha256sum <"$TMP/base.img")
  COPROC_DATA_MIB=16 "$IMAGE/layout.sh" --board orangepi-5 "$TMP/base.img"
  assert_eq "$(sha256sum <"$TMP/base.img")" "$first" "the image after a second run"
}

test_layout_refuses_an_unexpected_base() {
  make_base_image dos
  printf 'start=94208, size=2048, type=83\n' | sfdisk --quiet --append "$TMP/base.img"
  assert_fails "has 2 partitions" env COPROC_DATA_MIB=16 "$IMAGE/layout.sh" --board orangepi-5 "$TMP/base.img"
}
