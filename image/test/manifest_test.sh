# shellcheck shell=bash
# manifest.sh: a release's SHA256SUMS and manifest.json, from its images and stamps.

# An image and stamp per computer, in $TMP/release. Options after the name go to stamp.sh.
release_image() {
  local name=$1 base
  shift
  base="$TMP/release/1234-$name-pvdev-v2027.0.0-alpha-2-69-g71416112-coprocessors-1"
  mkdir -p "$TMP/release"
  rm -rf "$TMP/out"
  run_stamp "$name" --stamp-out "$base.stamp.json" "$@" 2>/dev/null
  printf 'compressed image of %s\n' "$name" >"$base.img.xz"
}

test_manifest_lists_every_computer_and_its_checksum() {
  need_yq
  need sha256sum
  make_repo
  make_settings_tool
  release_image vision-front
  release_image vision-back
  "$IMAGE/manifest.sh" "$TMP/release"
  (cd "$TMP/release" && sha256sum --quiet -c SHA256SUMS) || fail "SHA256SUMS doesn't check"
  assert_eq "$(wc -l <"$TMP/release/SHA256SUMS")" 2 "lines in SHA256SUMS"
  local manifest=$TMP/release/manifest.json
  assert_eq "$(yq -r '.team' "$manifest")" 1234
  assert_eq "$(yq -r '.release' "$manifest")" coprocessors-1
  assert_eq "$(yq -r '.photonvisionVersion' "$manifest")" dev-v2027.0.0-alpha-2-69-g71416112
  assert_eq "$(yq -r '[.computers[].name] | join(" ")' "$manifest")" "vision-back vision-front"
  assert_eq "$(yq -r '.computers[1].address' "$manifest")" 10.12.34.11
  assert_eq "$(yq -o=json -I=0 '.computers[1].cameras' "$manifest")" '["front-left","front-right"]'
  assert_eq "$(yq -r '.computers[1].imageSha256' "$manifest")" \
    "$(sha256sum <"$TMP/release/$(yq -r '.computers[1].image' "$manifest")" | cut -c1-64)"
  local field
  for field in photonvisionVersion recipeHash settingsHash; do
    [[ -n $(FIELD=$field yq -r '.computers[1][strenv(FIELD)]' "$manifest") ]] || fail "no $field per computer"
  done
}

test_manifest_records_the_common_images_checksum() {
  need_yq
  make_repo
  make_settings_tool
  release_image vision-front
  release_image vision-back
  local base="$TMP/release/1234-vision-front-pvdev-v2027.0.0-alpha-2-69-g71416112-coprocessors-1"
  printf '%064d\n' 7 >"$base.common-sha256"
  "$IMAGE/manifest.sh" "$TMP/release"
  local manifest=$TMP/release/manifest.json
  assert_eq "$(yq -r '.computers[1].commonImageSha256' "$manifest")" "$(printf '%064d' 7)"
  assert_eq "$(yq -r '.computers[0] | has("commonImageSha256")' "$manifest")" false
  assert_eq "$(wc -l <"$TMP/release/SHA256SUMS")" 2 "lines in SHA256SUMS"
}

test_manifest_refuses_images_from_two_releases() {
  need_yq
  make_repo
  make_settings_tool
  release_image vision-front
  release_image vision-back --release coprocessors-2
  assert_fails "don't share one release" "$IMAGE/manifest.sh" "$TMP/release"
}

test_manifest_refuses_an_image_without_a_stamp() {
  need_yq
  make_repo
  make_settings_tool
  release_image vision-front
  echo 'stray' >"$TMP/release/other.img.xz"
  assert_fails "has no stamp" "$IMAGE/manifest.sh" "$TMP/release"
}
