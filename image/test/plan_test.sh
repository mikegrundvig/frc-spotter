# shellcheck shell=bash
# plan.sh: the build's inputs checked before anything is downloaded.

RECIPE=3333333333333333333333333333333333333333333333333333333333333333

run_plan() {
  "$IMAGE/plan.sh" --repo "$TMP/repo" --recipe-hash "$RECIPE" "$@" 2>&1
}

# A value from plan.sh's name=value output.
output() {
  sed -n "s/^$1=//p" "$TMP/plan.out"
}

test_plan_lists_the_boards_and_computers_to_build() {
  need_yq
  make_repo
  run_plan --sha 0123456789abcdef0123456789abcdef01234567 >"$TMP/plan.out"
  assert_eq "$(output team)" 1234
  assert_eq "$(output version)" dev-v2027.0.0-alpha-2-69-g71416112
  assert_eq "$(output release)" build-0123456789ab
  assert_eq "$(output publish)" false
  assert_eq "$(output recipe-hash)" "$RECIPE" "the build's recipe hash, passed on"
  assert_eq "$(output jar-sha256)" 1111111111111111111111111111111111111111111111111111111111111111
  assert_eq "$(output computers)" '[{"name":"vision-front","board":"orangepi-5"},{"name":"vision-back","board":"orangepi-5-plus"}]'
  output boards >"$TMP/boards.json"
  assert_eq "$(yq -p json -o yaml -r '.[].board' "$TMP/boards.json" | paste -sd ' ')" "orangepi-5 orangepi-5-plus"
  assert_eq "$(yq -p json -o yaml -r '.[0].sha256' "$TMP/boards.json")" edf2bda3032579d759de46aab0e8094cfd3de3586ba764b663470d2b80351cb7
  assert_eq "$(yq -p json -o yaml -r '.[0].rootLocation' "$TMP/boards.json")" partition=1
  assert_eq "$(yq -p json -o yaml -r '.[1].minimumFreeMb' "$TMP/boards.json")" 1024
}

test_plan_publishes_a_named_release() {
  need_yq
  make_repo
  run_plan --release coprocessors-2027.1 >"$TMP/plan.out"
  assert_eq "$(output release)" coprocessors-2027.1
  assert_eq "$(output publish)" true
}

test_plan_refuses_a_lock_for_another_photonvision() {
  need_yq
  make_repo
  sed -i 's/alpha-2-69/alpha-2-72/' "$TMP/repo/coprocessor/photonvision.lock"
  assert_fails "but the robot code uses dev-v2027.0.0-alpha-2-69-g71416112" run_plan --release coprocessors-1
}

test_plan_refuses_a_placeholder_checksum() {
  need_yq
  make_repo
  yq -i '.images.orangepi-5.sha256 = "TODO: archive the image first"' "$TMP/repo/coprocessor/photonvision.lock"
  assert_fails "orangepi-5's base image's sha256 is missing or a placeholder" run_plan --release coprocessors-1
}

test_plan_refuses_another_boards_base_image() {
  need_yq
  make_repo
  yq -i '.images.orangepi-5.url |= sub("opi5.img", "opi5plus.img")' "$TMP/repo/coprocessor/photonvision.lock"
  assert_fails "PhotonVision's for Orange Pi 5 is photonvision_opi5.img.xz" run_plan --release coprocessors-1
}

test_plan_needs_the_builds_recipe_hash() {
  need_yq
  make_repo
  assert_fails "--recipe-hash" "$IMAGE/plan.sh" --repo "$TMP/repo" --release coprocessors-1
  assert_fails "--recipe-hash" "$IMAGE/plan.sh" --repo "$TMP/repo" --recipe-hash abc --release coprocessors-1
}

test_plan_refuses_a_bad_release_name() {
  need_yq
  make_repo
  assert_fails "release name" run_plan --release 'coprocessors-1; rm -rf /'
}

test_plan_publishes_only_coprocessors_names() {
  need_yq
  make_repo
  assert_fails "a release is named coprocessors-" run_plan --release v2027.1
}
