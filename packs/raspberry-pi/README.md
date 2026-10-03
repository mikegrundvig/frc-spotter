# raspberry-pi: a Raspberry Pi's power

How often a Raspberry Pi's supply fell below the firmware's under-voltage threshold since boot, and
when it last did. A brownout resets USB cameras and can corrupt an SD card, so any episode fails.

| Value | What | Limits |
|---|---|---|
| `raspberry-pi.power.episodes` | Under-voltage episodes since boot | |
| `raspberry-pi.power.summary` | A status: `ok`, `none since boot`; or `failing`, `2 since boot, latest 1234.6 s` (seconds after boot) | its own level |

**How:** `./undervoltage` counts the kernel's `Undervoltage detected!` lines in this boot's log
(`journalctl -k -b`). Read unprivileged, with the `systemd-journal` group the agent's package gives
its user; not with `vcgencmd`, which needs the `video` group and `/dev/vcio`, a device the agent's
sandbox closes (a drop-in could open it: `docs/agent.md`, *Giving a pack more*).

**Checked against the Raspberry Pi kernel's source (rpi-6.12.y), not on a board:**

- `drivers/hwmon/raspberrypi-hwmon.c` asks the firmware every 2 s, clearing its sticky bits each
  time, so no reading says "since boot". Each time under-voltage starts, it logs `Undervoltage
  detected!` at critical; when it ends, `Voltage normalised`. So the kernel's log of this boot
  holds every episode, each with its time.
- The firmware driver's `get_throttled` file is gone in rpi-6.12.y; where an older kernel has it,
  it's the last 2-second reply too, not since boot.
- A capped CPU clock shows in the debian pack's `debian.cpu.capped`.

**One difference from the design's example:** the example runs
`/usr/lib/frc-spotter-raspberry-pi/undervoltage`; here the script is the pack's own,
`./undervoltage`, so the pack is one folder to copy or push, with no installer step.

## Installing it

Copy the folder to `/etc/frc-spotter/packs/raspberry-pi/` (root's, written by root alone) on a
Raspberry Pi, and restart the agent; or the robot pushes it (`docs/robot.md`). On a computer that
isn't a Pi it says `none since boot`, as the driver isn't there to log anything.
