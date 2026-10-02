#!/bin/sh
# Installs PhotonVision's pack (coprocessor/README.md, "Packs"): this folder, as built
# (./gradlew :coprocessor-photonvision:packFolder), into /usr/lib/frc-coprocessor/packs/photonvision,
# and its polkit rule. The agent package must be installed too; a computer runs the pack once its
# configuration (/etc/frc-coprocessor/agent.json) names it, and the agent restarts.
#
#   install.sh [--root DIR]    DIR: an image's root, being built (a chroot); / when not given
set -eu
root=/
while [ $# -gt 0 ]; do
  case $1 in
    --root) root=${2:?--root needs a folder}; shift 2 ;;
    *) echo "install.sh: unknown option $1" >&2; exit 2 ;;
  esac
done
here=$(cd "$(dirname "$0")" && pwd)
target="$root/usr/lib/frc-coprocessor/packs/photonvision"
mkdir -p "$target/bin" "$target/lib" "$root/usr/share/polkit-1/rules.d"
install -m 0644 "$here/pack.json" "$target/pack.json"
install -m 0755 "$here/bin/photonvision-helper" "$target/bin/photonvision-helper"
install -m 0644 "$here/lib/photonvision-helper.jar" "$target/lib/photonvision-helper.jar"
install -m 0644 "$here/61-frc-coprocessor-photonvision.rules" \
  "$root/usr/share/polkit-1/rules.d/61-frc-coprocessor-photonvision.rules"
echo "install.sh: PhotonVision's pack installed in $target"
