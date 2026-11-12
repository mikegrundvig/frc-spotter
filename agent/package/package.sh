#!/usr/bin/env bash
# Packs a built agent program into its release files (docs/agent.md, "The package"), as the
# release workflow attaches them to a tag's GitHub release:
#
#   package/package.sh <program> <version> <arch> <out dir>     arch: arm64 or amd64
#
# makes frc-spotter_<version>_<arch>.deb (with dpkg-deb, where it's installed) and
# frc-spotter-<version>-linux-<arch>.tar.gz (the same files, with install.sh), and prints their
# paths. Both hold the program, its unit, sysusers and tmpfiles entries, polkit rules, its licence
# and the list of the Rust crates it's built from (THIRD-PARTY-LICENSES, which needs cargo-deny).
# The same files make the same packages: fixed owner, order and times, no gzip timestamp.
set -euo pipefail
# Folders 0755 and files as given, whatever the caller's umask.
umask 022
here="$(cd "$(dirname "$0")" && pwd)"
crate="$(cd "$here/.." && pwd)"
repo="$(cd "$crate/.." && pwd)"
program="$1"
version="$2"
arch="$3"
out="$4"
case "$arch" in
  arm64) target=aarch64-unknown-linux-gnu ;;
  amd64) target=x86_64-unknown-linux-gnu ;;
  *) echo "package.sh: arch is arm64 or amd64, not $arch" >&2; exit 2 ;;
esac
mkdir -p "$out"
work="$(mktemp -d "$out/package.XXXXXX")"
trap 'rm -rf "$work"' EXIT
root="$work/root"

# The installed layout: the program where the launcher was, so /usr/lib/frc-spotter/bin/frc-spotter
# is the agent and the command line as before.
install -D -m 0755 "$program" "$root/usr/lib/frc-spotter/bin/frc-spotter"
install -D -m 0644 "$here/frc-spotter.service" "$root/usr/lib/systemd/system/frc-spotter.service"
install -D -m 0644 "$here/frc-spotter.sysusers" "$root/usr/lib/sysusers.d/frc-spotter.conf"
install -D -m 0644 "$here/frc-spotter.tmpfiles" "$root/usr/lib/tmpfiles.d/frc-spotter.conf"
install -D -m 0644 "$here/60-frc-spotter.rules" "$root/usr/share/polkit-1/rules.d/60-frc-spotter.rules"
install -D -m 0644 "$here/99-frc-spotter.rules" "$root/usr/share/polkit-1/rules.d/99-frc-spotter.rules"
install -D -m 0644 "$repo/LICENSE" "$root/usr/share/doc/frc-spotter/LICENSE"
install -m 0644 "$repo/THIRD-PARTY.md" "$root/usr/share/doc/frc-spotter/"
# Each Rust crate the program is built from and its licences, from Cargo.lock by cargo-deny: this
# architecture's build's crates, less the tests' and the build's own.
{
  echo "The Rust crates Spotter's agent is built from, each with its licences, from Cargo.lock"
  echo "(cargo deny list). Each crate's licence text is in its source, on crates.io."
  echo
  cargo deny --log-level error --manifest-path "$crate/Cargo.toml" --locked --offline \
    --exclude-dev --target "$target" --color never list --layout crate |
    grep -v '^frc-spotter@'
} >"$root/usr/share/doc/frc-spotter/THIRD-PARTY-LICENSES"
chmod 0644 "$root/usr/share/doc/frc-spotter/THIRD-PARTY-LICENSES"

mtime="@$(git -C "$repo" log -1 --format=%ct)"
find "$root" -exec touch -h -d "$mtime" {} +

# The tarball: the files as installed, with install.sh beside them.
name="frc-spotter-$version-linux-$arch"
mkdir "$work/$name"
cp -a "$root/usr" "$work/$name/"
install -m 0755 "$here/install.sh" "$work/$name/install.sh"
touch -h -d "$mtime" "$work/$name" "$work/$name/install.sh"
tar -C "$work" --owner=0 --group=0 --numeric-owner --sort=name --mtime="$mtime" -cf - "$name" |
  gzip -n >"$out/$name.tar.gz"
echo "$out/$name.tar.gz"

# The .deb: the same files, its maintainer scripts and control file.
if command -v dpkg-deb >/dev/null; then
  install -D -m 0755 "$here/debian/postinst" "$root/DEBIAN/postinst"
  install -m 0755 "$here/debian/prerm" "$here/debian/postrm" "$root/DEBIAN/"
  size=$(du -sk --exclude=DEBIAN "$root" | cut -f1)
  sed -e "s/@VERSION@/$version/" -e "s/@ARCH@/$arch/" -e "s/@SIZE@/$size/" \
    "$here/debian/control.in" >"$root/DEBIAN/control"
  chmod 0644 "$root/DEBIAN/control"
  touch -h -d "$mtime" "$root/DEBIAN" "$root/DEBIAN"/*
  SOURCE_DATE_EPOCH="${mtime#@}" dpkg-deb --root-owner-group -Zxz --build "$root" \
    "$out/frc-spotter_${version}_$arch.deb" >/dev/null
  echo "$out/frc-spotter_${version}_$arch.deb"
fi
