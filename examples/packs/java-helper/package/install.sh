#!/bin/sh
# Installs the example pack (docs/agent.md, "Packs"): this folder, as built
# (./gradlew :java-helper-pack:packFolder), into /usr/lib/frc-spotter/packs/java-helper. The
# agent's package must be installed too; a computer runs the pack once its configuration
# (/etc/frc-spotter/agent.json) names it, and the agent restarts.
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
target="$root/usr/lib/frc-spotter/packs/java-helper"
mkdir -p "$target/bin" "$target/lib"
install -m 0644 "$here/pack.json" "$target/pack.json"
install -m 0755 "$here/bin/java-helper" "$target/bin/java-helper"
install -m 0644 "$here/lib/java-helper.jar" "$target/lib/java-helper.jar"
echo "install.sh: the java-helper pack is installed in $target"
