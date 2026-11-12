#!/bin/sh
# Installs the coprocessor agent without its .deb (docs/agent.md): from the tarball, laid out as
# installed (usr/...). On a running system it's started; with --root, into an image being built (a
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
mkdir -p "$root"
cp -R "$here/usr" "$root/"
mkdir -p "$root/etc/frc-spotter/packs"
if [ "$root" = / ]; then
  systemd-sysusers frc-spotter.conf
  systemd-tmpfiles --create frc-spotter.conf || true
  systemctl enable frc-spotter.service
  if [ -d /run/systemd/system ]; then
    systemctl daemon-reload
    systemctl restart frc-spotter.service
  fi
else
  systemd-sysusers --root="$root" frc-spotter.conf
  systemd-tmpfiles --root="$root" --create frc-spotter.conf || true
  systemctl --root="$root" enable frc-spotter.service
fi
echo "install.sh: the coprocessor agent is installed; packs are folders in /etc/frc-spotter/packs/"
