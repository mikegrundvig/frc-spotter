#!/bin/sh
# Installs the coprocessor agent without its .deb (docs/agent.md): from the tarball, which
# carries its own Java runtime, or from the -all.jar and its files, which run on the board's java
# (17 or newer). On a running system it's started; with --root, into an image being built (a
# chroot), it's only enabled. Needs systemd and polkit.
#
#   install.sh [--root DIR]
set -eu
root=/
while [ $# -gt 0 ]; do
  case $1 in
    --root) root=${2:?--root needs a folder}; shift 2 ;;
    *) echo "install.sh: unknown option $1" >&2; exit 2 ;;
  esac
done
here=$(cd "$(dirname "$0")" && pwd)
lib="$root/usr/lib/frc-spotter"
# The tarball is laid out as installed (usr/...); the jar's files sit side by side.
if [ -d "$here/usr" ]; then
  mkdir -p "$root"
  cp -R "$here/usr" "$root/"
else
  mkdir -p "$lib/bin" "$root/usr/lib/systemd/system" "$root/usr/lib/sysusers.d" \
    "$root/usr/share/polkit-1/rules.d"
  jar=$(ls "$here"/frc-spotter-*-all.jar | head -n 1)
  install -m 0644 "$jar" "$lib/frc-spotter.jar"
  install -m 0755 "$here/frc-spotter" "$lib/bin/frc-spotter"
  install -m 0644 "$here/frc-spotter.service" "$root/usr/lib/systemd/system/"
  install -m 0644 "$here/frc-spotter.sysusers" \
    "$root/usr/lib/sysusers.d/frc-spotter.conf"
  install -m 0644 "$here"/*-frc-spotter.rules "$root/usr/share/polkit-1/rules.d/"
  if [ -f "$here/pack.json" ]; then
    mkdir -p "$root/usr/lib/frc-spotter/packs/builtin"
    install -m 0644 "$here/pack.json" "$root/usr/lib/frc-spotter/packs/builtin/pack.json"
  fi
fi
mkdir -p "$root/etc/frc-spotter"
if [ "$root" = / ]; then
  systemd-sysusers frc-spotter.conf
  systemctl enable frc-spotter.service
  if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
    systemctl restart frc-spotter.service
  fi
else
  systemd-sysusers --root="$root" frc-spotter.conf
  systemctl --root="$root" enable frc-spotter.service
fi
echo "install.sh: the coprocessor agent is installed; configure it in /etc/frc-spotter/agent.json"
