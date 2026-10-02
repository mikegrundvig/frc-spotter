#!/usr/bin/env bash
# run.sh: the image scripts' tests. Every test_* function in test/*_test.sh runs in its own bash,
# in a fresh temporary directory ($TMP), with the image scripts' folder in $IMAGE.
#
#   coprocessor/image/test/run.sh [PATTERN]    # only the tests whose names contain PATTERN
#
# Prints PASS, FAIL (with the test's output), or SKIP (a tool this machine lacks) per test, and
# exits non-zero if any failed. With COPROC_TESTS_STRICT=1 a skip fails too (Linux CI sets it).
# Needs bash and, for most tests, yq (mikefarah's, version 4); the layout tests need sfdisk and
# mkfs.fat, and the provision tests systemctl.
set -uo pipefail

test_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
IMAGE=$(cd "$test_dir/.." && pwd)
export IMAGE
pattern=${1:-}
passed=0
failed=0
skipped=0
for file in "$test_dir"/*_test.sh; do
  names=$(bash -c '. "$1"; . "$2"; declare -F' _ "$test_dir/lib.sh" "$file" | awk '$3 ~ /^test_/ { print $3 }')
  for name in $names; do
    [[ -z $pattern || $name == *"$pattern"* ]] || continue
    tmp=$(mktemp -d)
    output=$(cd "$tmp" && TMP=$tmp bash -c 'set -euo pipefail; . "$1"; . "$2"; "$3"' _ \
      "$test_dir/lib.sh" "$file" "$name" 2>&1)
    status=$?
    rm -rf "$tmp"
    case $status in
      0)
        passed=$((passed + 1))
        printf 'PASS %s\n' "$name"
        ;;
      77)
        skipped=$((skipped + 1))
        printf 'SKIP %s: %s\n' "$name" "$(tail -n 1 <<<"$output")"
        ;;
      *)
        failed=$((failed + 1))
        printf 'FAIL %s\n' "$name"
        while IFS= read -r line; do
          printf '    %s\n' "$line"
        done <<<"$output"
        ;;
    esac
  done
done
printf '%d passed, %d failed, %d skipped\n' "$passed" "$failed" "$skipped"
if [[ ${COPROC_TESTS_STRICT:-0} == 1 ]] && ((skipped > 0)); then
  echo "COPROC_TESTS_STRICT is set: a skipped test fails the run"
  exit 1
fi
((failed == 0))
