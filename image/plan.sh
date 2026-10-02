#!/usr/bin/env bash
# plan.sh: checks the inputs of an image build and says what to build. The image workflow's first
# job runs it; nothing is downloaded or built until it passes.
#
#   coprocessor/image/plan.sh --recipe-hash HEX [--repo DIR] [--release NAME] [--sha COMMIT]
#
# The recipe hash is the build's: ./gradlew -q coprocessorRecipeHash prints it (and writes it to
# build/coprocessor/recipe-hash.txt), so the robot and the images compute it one way.
#
# It checks:
#   - the table (coprocessors/coprocessors.yaml): the team number, and each computer's name,
#     address, and board;
#   - the lock (coprocessor/photonvision.lock): its PhotonVision version is the robot code's (the
#     version in vendordeps/photonlib.json), and the jar and every board's base image in use have
#     an https URL and a real sha256 (a placeholder fails here, naming what's missing); each base
#     image is the one PhotonVision publishes for that board;
#   - the release name: a published one is coprocessors-<something>, as the tags that trigger the
#     workflow are.
# Then it prints, as name=value lines (appended to $GITHUB_OUTPUT in Actions):
#   team, version, recipe-hash, jar-url, jar-sha256, release, publish (whether --release was given:
#   without one, the images are built under a name made from the commit, and not released),
#   boards (a JSON list: board, url, sha256, rootLocation, minimumFreeMb), and computers (a JSON
#   list: name, board).
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/common.sh
. "$here/lib/common.sh"

repo=$(cd "$here/../.." && pwd)
release=""
sha=""
recipe_hash=""
while (($#)); do
  case $1 in
    --recipe-hash) recipe_hash=${2:?}; shift 2 ;;
    --repo) repo=${2:?}; shift 2 ;;
    --release) release=${2-}; shift 2 ;;
    --sha) sha=${2:?}; shift 2 ;;
    *) die "unknown option: $1" ;;
  esac
done
require_yq
is_sha256 "$recipe_hash" ||
  die "--recipe-hash: the build's recipe hash (./gradlew -q coprocessorRecipeHash), 64 lowercase hex digits"
table="$repo/$COPROC_TABLE"
lock="$repo/$COPROC_LOCK"
vendordep="$repo/$COPROC_VENDORDEP"
[[ -f $lock ]] || die "no lock at $COPROC_LOCK"
[[ -f $vendordep ]] || die "no vendordep at $COPROC_VENDORDEP"

check_table "$table"
team=$(table_get '.team' "$table")

# PhotonVision's version: the lock's must be the robot code's.
version=$(lock_get "$LOCK_VERSION_PATH" "$lock")
robot_version=$(yq -p json -o yaml -r '.version // ""' "$vendordep")
[[ -n $version ]] || die "$COPROC_LOCK gives no PhotonVision version ($LOCK_VERSION_PATH)"
[[ $version == "$robot_version" ]] ||
  die "$COPROC_LOCK is for PhotonVision $version, but the robot code uses $robot_version ($COPROC_VENDORDEP): update the lock"
[[ $version =~ ^[A-Za-z0-9._+-]+$ ]] || die "PhotonVision version '$version' has unexpected characters"

# A URL and sha256 from the lock, checked. $1 names it for messages.
checked_pair() {
  local what=$1 url=$2 sum=$3
  [[ $url == https://* ]] || die "$COPROC_LOCK: $what's URL is missing or not https ('$url')"
  [[ $url =~ ^[A-Za-z0-9:/._~%+-]+$ ]] || die "$COPROC_LOCK: $what's URL has unexpected characters"
  is_sha256 "$sum" || die "$COPROC_LOCK: $what's sha256 is missing or a placeholder ('$sum')"
}

jar_url=$(lock_get "$LOCK_JAR_URL_PATH" "$lock")
jar_sha=$(lock_get "$LOCK_JAR_SHA256_PATH" "$lock")
checked_pair "the PhotonVision jar" "$jar_url" "$jar_sha"

boards=()
for board in $(yq -r '[.computers[].board] | unique | .[]' "$table"); do
  # shellcheck source=boards/orangepi-5.env
  . "$here/boards/$board.env"
  url=$(BOARD=$board lock_get "$LOCK_IMAGE_URL_PATH" "$lock")
  sum=$(BOARD=$board lock_get "$LOCK_IMAGE_SHA256_PATH" "$lock")
  checked_pair "$board's base image" "$url" "$sum"
  [[ ${url##*/} == "$BOARD_PV_ASSET" ]] ||
    die "$COPROC_LOCK: $board's base image is ${url##*/}, but PhotonVision's for $BOARD_TITLE is $BOARD_PV_ASSET"
  boards+=("$(
    BOARD=$board URL=$url SUM=$sum ROOT=$BOARD_ROOT_LOCATION FREE=$BOARD_MINIMUM_FREE_MB \
      yq -n -o=json -I=0 '{"board": strenv(BOARD), "url": strenv(URL), "sha256": strenv(SUM),
        "rootLocation": strenv(ROOT), "minimumFreeMb": env(FREE)}'
  )")
done
boards_json="[$(IFS=,; echo "${boards[*]}")]"
computers_json=$(yq -o=json -I=0 '[.computers[] | {"name": .name, "board": .board}]' "$table")

if [[ -n $release ]]; then
  publish=true
  # A published release has the name a tag would trigger the workflow with.
  [[ $release == coprocessors-* ]] || die "release name '$release': a release is named coprocessors-<something>"
else
  publish=false
  [[ $sha =~ ^[0-9a-f]{7,40}$ ]] || die "without --release, --sha COMMIT names the build"
  release="build-${sha:0:12}"
fi
[[ $release =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] ||
  die "release name '$release': up to 64 letters, digits, '.', '_', '-', starting with a letter or digit"


out=${GITHUB_OUTPUT:-/dev/stdout}
{
  echo "team=$team"
  echo "version=$version"
  echo "recipe-hash=$recipe_hash"
  echo "jar-url=$jar_url"
  echo "jar-sha256=$jar_sha"
  echo "release=$release"
  echo "publish=$publish"
  echo "boards=$boards_json"
  echo "computers=$computers_json"
} >>"$out"
