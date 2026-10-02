# shellcheck shell=bash
# The recipe hash is the build's (./gradlew coprocessorRecipeHash, RecipeHash in
# coprocessor/common). What provision.sh and stamping read from the repository makes the image, so
# a change to any of it must change that hash: this checks the hash's definition covers all of it.

# shellcheck disable=SC2153 # IMAGE comes from run.sh
test_recipe_covers_everything_the_image_is_made_from() {
  local java=$IMAGE/../common/src/main/java/com/michaelgrundvig/frc/coprocessor/tools/RecipeHash.java
  [[ -f $java ]] || skip "no RecipeHash.java in this checkout"
  # shellcheck source=../lib/common.sh
  . "$IMAGE/lib/common.sh"
  # The paths it lists, and the text of what it leaves out of them (documentation, tests, the
  # coverage floors): any consumed path containing one of those is treated as left out.
  local paths=() left_out=() line
  while IFS= read -r line; do
    paths+=("$line")
  done < <(awk '/List<String> PATHS =/,/\);/' "$java" | grep -oE '"[A-Za-z0-9._/-]+"' | tr -d '"')
  while IFS= read -r line; do
    left_out+=("$line")
  done < <(awk '/static boolean inRecipe/,/^  }/' "$java" | grep -oE '"[A-Za-z0-9._/-]+"' | tr -d '"')
  ((${#paths[@]} > 0)) || fail "found no PATHS in RecipeHash.java"
  ((${#left_out[@]} > 0)) || fail "found nothing inRecipe leaves out in RecipeHash.java"
  covered() {
    local entry rule
    for rule in "${left_out[@]}"; do
      [[ $1 == *"$rule"* ]] && return 1
    done
    for entry in "${paths[@]}"; do
      [[ $1 == "$entry" || $1 == "$entry"/* ]] && return 0
    done
    return 1
  }
  local image=coprocessor/image path missing=()
  local consumed=("$image/provision.sh" "$image/layout.sh" "$image/stamp.sh" "$image/stamp-image.sh"
    "$image/lib/common.sh" "$COPROC_AGENT_UNIT" "$COPROC_LOCK")
  for path in "$IMAGE"/boards/* "$IMAGE"/files/*; do
    consumed+=("$image/${path#"$IMAGE"/}")
  done
  # The agent's jar is built, not committed: what builds it (its libraries' versions the hash
  # adds itself).
  consumed+=(coprocessor/agent/src/main/x coprocessor/agent/build.gradle coprocessor/common/src/main/x
    coprocessor/common/build.gradle gradle/quality.gradle)
  # The paths provision.sh reads from the repository are among those.
  grep -qF "repo/\$COPROC_AGENT_JAR" "$IMAGE/provision.sh" || fail "provision.sh reads the agent's jar differently now"
  grep -qF "repo/\$COPROC_AGENT_UNIT" "$IMAGE/provision.sh" || fail "provision.sh reads the agent's unit differently now"
  for path in "${consumed[@]}"; do
    covered "$path" || missing+=("$path")
  done
  ((${#missing[@]} == 0)) || fail "the recipe hash (RecipeHash.PATHS) doesn't cover: ${missing[*]}"
}
