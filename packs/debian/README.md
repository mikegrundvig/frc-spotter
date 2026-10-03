# debian: a Debian-family system's own health

What a Debian-family coprocessor (Debian, Ubuntu, Raspberry Pi OS, Armbian) can say about itself,
judged by sensible defaults: its CPU, heat, memory, disk, network link, USB devices, clock, drive
and uptime, and its system journal. Its scripts are POSIX sh, beside `pack.yaml`, reading `/proc`
and `/sys`, and need nothing a Debian-family system doesn't have; the drive's health needs a root
timer, below.

## Values

| Value | What | Limits |
|---|---|---|
| `debian.cpu.busiest` | The busiest core over half a second, % (`/proc/stat`): vision software often runs on the big cores alone, and four at 100% hide inside a 50% total | |
| `debian.cpu.capped` | Whether the fastest cores are held below their top frequency (cpufreq's `scaling_max_freq` below `cpuinfo_max_freq`): a thermal or power limit, or someone setting it lower on purpose. Unavailable without cpufreq (a virtual machine) | warn when true |
| `debian.thermal.hottest` | The hottest thermal zone, °C (`/sys/class/thermal`) | |
| `debian.thermal.margin` | How far any zone is below its nearest trip point that acts on heat (passive, hot or critical; not a fan's active steps), °C; negative once past one | warn below 10, fail below 5 |
| `debian.memory.available` | Memory available without swapping, as a share of all of it (`MemAvailable`), % | warn below 15, fail below 5 |
| `debian.disk.root-free` | The root filesystem's free space anyone may use, as a share of its size, % | warn below 10, fail below 5 |
| `debian.network.up` | Whether `eth0` has a link (`carrier`) | fail when false |
| `debian.network.speed` | The speed it negotiated, Mb/s: a damaged cable negotiates 100 | warn below 1000 |
| `debian.network.drops` | How often its link dropped since boot (from `carrier_up_count`: each time it came up after its first, and one more if it's down now): a loose plug at the radio drops it on every hit | warn above 0 |
| `debian.usb.devices` | The USB devices plugged in, each by its port and product (`7-1 HD Pro Webcam C920; 8-1 Arducam`), or `none` | |
| `debian.usb.disconnects` | The kernel's "USB disconnect" lines in this boot's journal | warn above 0 |
| `debian.clock.synced` | Whether the kernel counts the clock synchronized (`timedatectl`'s `NTPSynchronized`, which chrony and systemd-timesyncd both set) | warn when false |
| `debian.clock.offset` | How far the clock is behind its time source, s (negative when ahead): chrony's, else systemd-timesyncd's. Unavailable with neither | |
| `debian.drive.wear` | The NVMe drive's wear: the share of its rated endurance used, % (can pass 100). From the root timer's file | warn above 80, fail above 95 |
| `debian.drive.unsafe-shutdowns` | Times it lost power without a clean shutdown | |
| `debian.uptime.seconds` | Seconds since boot (`/proc/uptime`) | |

A value a board can't give is unavailable, with why: `its output has no "capped"` on a computer
without cpufreq, no thermal zones in a virtual machine, `no such file:
/run/frc-spotter-debian/drive.json` without the root timer or an NVMe drive.

**The network interface** is `eth0`. A board whose wired interface has another name (`end0` on
many Arm boards, `enP4p65s0`) changes the collector's argument in `pack.yaml`: `run: [./network,
end0]`.

## Log

`debian.journal`, the system journal, newest first or paged back and on by its cursor, at a level
or more severe: `./journal` runs `journalctl -o json` by what the agent asks in `SPOTTER_*`. Reading
the system journal takes the `systemd-journal` group, which the agent's package gives its user.

## Installing it

It's a folder: copy it to `/etc/frc-spotter/packs/debian/`, root's and written by root alone (the
agent trusts nothing else), and restart the agent:

```sh
sudo cp -r debian /etc/frc-spotter/packs/
sudo chown -R root:root /etc/frc-spotter/packs/debian
sudo chmod -R go-w /etc/frc-spotter/packs/debian
sudo systemctl restart frc-spotter
```

Or the robot pushes it: put the folder in the robot program's packs folder (`docs/robot.md`). A
pushed pack can't install the timer below, so its drive values are unavailable unless the board's
installer added the timer too.

### The drive's health: a root timer

Reading an NVMe drive's SMART log takes root, which the agent hasn't. So `drive-health`, run as root
each minute by `frc-spotter-debian-drive.timer`, writes `/run/frc-spotter-debian/drive.json` for
the agent to read. It needs nvme-cli (`apt install nvme-cli`). Spotter doesn't install it: the
board's installer does, once:

```sh
sudo apt install nvme-cli
sudo cp /etc/frc-spotter/packs/debian/frc-spotter-debian-drive.service \
        /etc/frc-spotter/packs/debian/frc-spotter-debian-drive.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now frc-spotter-debian-drive.timer
```

The service runs `/etc/frc-spotter/packs/debian/drive-health /dev/nvme0`: a pack installed
elsewhere, or a drive by another name, changes its `ExecStart`. Without the drive or nvme-cli it
writes no file, and the drive values say so. It writes the file whole, then renames it, so the
agent never reads half of one.

## Tests

`harness/`'s `CatalogContainerTest` installs this pack, with its timer, in the Debian 13 test
container and checks that every value fills with a sane value (or, for what a container lacks, is
unavailable and says why), that its limits judge, and that its journal pages, back and on by
cursor and by level. Its scripts' parsing of chrony's, systemd-timesyncd's and nvme-cli's reports is
checked against stand-ins of those programs.
