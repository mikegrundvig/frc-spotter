#!/bin/sh
# Writes what only root can read, for the health agent, which runs unprivileged: the NVMe drive's
# health (temperature, wear, unsafe shutdowns) and the U-Boot version in SPI flash. Run at boot and
# every minute after (coprocessor-facts.service and .timer). Each file is replaced whole, so the
# agent never reads half of one. A missing file means there was nothing to read (no NVMe drive, no
# SPI flash).
set -u
out=/run/coprocessor
mkdir -p "$out"
chmod 0755 "$out"

# Runs a command into a file, replacing the file only if the command succeeds.
write() {
  file=$1
  shift
  if "$@" >"$out/.$file.tmp" 2>/dev/null; then
    chmod 0644 "$out/.$file.tmp"
    mv -f "$out/.$file.tmp" "$out/$file"
  else
    rm -f "$out/.$file.tmp"
  fi
}

if [ -e /dev/nvme0 ]; then
  write nvme-smart-log.json nvme smart-log --output-format=json /dev/nvme0
  write nvme-id-ctrl.json nvme id-ctrl --output-format=json /dev/nvme0
fi
if [ -e /dev/mtd0 ] && [ ! -e "$out/spi-uboot-version" ]; then
  # binutils' strings, which PhotonVision's Orange Pi image installs for this same check.
  write spi-uboot-version sh -c 'strings /dev/mtd0 | grep -m 1 "^U-Boot "'
fi
exit 0
