# The coprocessor agent

Each coprocessor on the robot runs the agent, a small web service the robot polls. The agent knows
the computer, not the software on it: it reports the computer's health (load, heat, memory, disks,
the journal, the drive, how it booted, the network link, USB devices, the clock) and which computer
it is, and runs **probes**, named checks
that **packs** define. A pack is one YAML file of probes on one program or board; a team copies the
ones it wants into `/etc/frc-spotter/packs/`. The agent needs nothing else configured. The robot
decides what it needs of each probe; the agent only reports.

| Path | What |
|---|---|
| `api/` (Gradle `:api`) | What the agent and the robot share: the API's records and JSON, and the probes and packs the agent reads. Java 17 |
| `agent/` (`:agent`) | The agent, its systemd unit, launcher, drive-health timer, polkit rules, and package (`agent/package/`) |
| `packs/` | The catalog of packs a team copies from (*Packs*) |
| `client/` (`:client`) | The robot's side, plain Java (`docs/robot.md`) |
| `harness/` (`:harness`) | The container harness, a library for container tests of the agent and packs, and Spotter's own container tests |

## The agent's API, version 1

HTTP, JSON, no authentication, on port 5808 (unless `agent.json` says otherwise). It runs on a
closed robot network, runs nothing a request names (a probe is asked for by its id; everything it
runs is fixed in its pack's file), and bounds every answer. It's read-only but for one action, a
soft power-off, which only the robot controller's address may ask for.

| Request | Answer |
|---|---|
| `GET /v1/stamp` | `Stamp`: which computer this is: its hostname, addresses, MAC, boot, and `/etc/os-release` |
| `GET /v1/health` | `Health`: the computer's health and every probe's latest result |
| `GET /v1/journal?cursor=&before=&from=&priority=&unit=&limit=` | `JournalPage`: a page of the journal |
| `GET /v1/probes` | Every probe's latest result |
| `GET /v1/probes/<id>` | Runs that probe now and answers its result; asked again within 2 s, the last one |
| `POST /v1/shutdown` | `ShutdownAnswer` (202): powers the computer off (`systemctl poweroff`, which stops every service in order first). From the robot controller only |

A refusal or a failure answers `{"error": "..."}` with its status: 400 for a bad query, 403 for a
shutdown it won't take, 404 for an unknown path or probe, 405 for the wrong method (with `Allow`),
421 for a request not addressed to it, 503 (with `Retry-After`) when a journal page finds another
under way, or a probe run finds two, and 500 when the agent failed (what went wrong is in its
journal).

**Who it answers.** Each request has a thread of its own, so a client that's slow, or never
finishes sending, holds up nobody else; a request must arrive within 4 seconds, and 32 connections
are open at most. Heavy requests take turns, so health, the stamp, and a shutdown never wait behind
them. It answers only requests addressed to it (a `Host` that's a 10.x, 169.254.x, or loopback
address, `localhost`, or its own name), so a web page elsewhere can't reach it through a name that
resolves to it. Every answer carries `Cache-Control: no-store` and `X-Content-Type-Options:
nosniff`.

The answers are Java records in `com.michaelgrundvig.frc.spotter.api`; the paths and limits are
`AgentApi`'s constants. Each has `toJson()` and `fromJson(...)`. **Reading is tolerant one way:** a
missing value reads as its default and an unknown one is ignored, so a robot and an agent built a
version apart still understand each other; a value of the wrong kind is an error.

### `Stamp`

```json
{"hostname": "vision-front", "addresses": ["10.12.34.11"], "mac": "c0:74:2b:fe:12:34",
 "bootId": "3c1e6a2e-…", "uptimeSeconds": 1234.56,
 "osRelease": {"ID": "debian", "VERSION_ID": "13", "PRETTY_NAME": "Debian GNU/Linux 13 (trixie)",
               "IMAGE_ID": "vision-orangepi", "IMAGE_VERSION": "2027.1", "PADDOCK_PHOTONVISION_VERSION": "v2027.1.0"}}
```

Which computer this is, read from the computer each time it's asked, with nothing configured: its
hostname (`/proc/sys/kernel/hostname`), which is the agent's name for it; its addresses (every
interface's but loopback's, IPv4 first, IPv6 link-local ones left out, at most 16); the MAC of its
wired interface; this boot's ID and uptime; and every key of `/etc/os-release` (else
`/usr/lib/os-release`) with its value unquoted, as os-release(5) writes them, at most 64 keys of 512
characters. An image builder labels its images there, with os-release's own `IMAGE_ID` and
`IMAGE_VERSION` and its own prefixed keys; Spotter passes them on without reading meaning into any.
The deploy check (`docs/robot.md`) compares a coprocessor's identity with what the robot program
expects of it.

### `Health`

| Member | What |
|---|---|
| `stamp` | As `/v1/stamp` |
| `boot` | Boot ID, uptime, the agent's monotonic clock (microseconds since boot, the journal's clock), whether the boot before shut down cleanly (`null`: unknown), the root device and whether it's read-only |
| `cpu` | Each core's busy percentage; each cluster's cores, current, limit, and maximum MHz |
| `thermal` | Every thermal zone's temperature, with its trip points |
| `memory` | Total and available MiB |
| `disks` | The root's and `/data`'s size and free space, for those mounted |
| `journal` | This boot's count of USB, UVC, filesystem, out-of-memory, thermal, and other errors, with the latest three, from the kernel, the agent, and the units the packs name |
| `drive` | The NVMe drive's temperature, wear, unsafe shutdowns, power cycles, hours, media errors, and critical warnings; `null` without one (*The drive's health*) |
| `probes` | Every probe's latest `ProbeResult`, in its packs' order |
| `network` | Each interface but loopback (at most 16), from `/sys/class/net`: its name, whether a device is behind it (`physical`), its state (`up`, `down`, ...), the speed it negotiated (Mb/s; -1 when none, as for a virtual interface), its carrier changes since boot (each drop counts two), and its receive and transmit errors. A loose plug at the radio shows as carrier changes; a damaged cable as 100 Mb/s |
| `usb` | Every USB device by its port path (`7-1`, `7-1.2` behind a hub; root hubs left out), from `/sys/bus/usb/devices`: its vendor and product IDs, what it calls itself, its link speed (Mb/s), and how often a device was unplugged from that port this boot, counted from the kernel's `USB disconnect` lines. A port whose device was unplugged and hasn't come back is there with `present: false`. At most 32 |
| `clock` | `synced`: whether the kernel counts the clock synchronized (`timedatectl`'s `NTPSynchronized`, which chrony and systemd-timesyncd both set; `null` when unknown); `source` and `offsetMillis`: the daemon that measured the offset (`chrony`, by `chronyc -c tracking`, else `systemd-timesyncd`, by `timedatectl timesync-status`) and how far the clock is behind its time source (negative when ahead; NaN when neither says). Read at most every 10 s |
| `problems` | What the agent couldn't read or ignored, each saying what and why (at most 10): a pack it doesn't trust or can't read, `agent.json` it can't read, `shutdown refused: ...` when it has no controller to take one from. `shutting down: asked for by the robot` comes first once a shutdown is under way |

It's answered at once from the last reading; older than half a second, a new reading starts in the
background. An RK3588's, with no packs, is under 5 KB (the unit tests' fixture); the container
tests', with their 21 probes and a laptop's 13 USB devices, 7.3 KB.

### `ProbeResult`

```json
{"id": "photonvision.unit", "kind": "unit", "status": "pass", "value": "active/running, restarts 0",
 "detail": "", "ranMicros": 81234567, "durationMillis": 12.0}
```

`status` is `pass`, `fail` (it ran and found otherwise), `error` (it couldn't run or finish: a
timeout, a program that couldn't start), or `pending` (not run yet). `value` is what it found (at
most 256 characters): the text its pattern matched, a unit's state, a metric, a file's hash.
`detail` says why it failed or erred: for a program that failed, the first line it wrote to
standard error. `ranMicros` is on the agent's monotonic clock.

### `JournalPage`

`entries` (oldest first), `cursor`, and `more`, by one of: nothing (the latest entries), `cursor=C`
(the entries just after C), `before=C` (just before C), or `from=boot` (this boot's first). So a
viewer's "Older" asks `before=` its oldest entry, and a whole boot is `from=boot`, then `cursor=`
until `more` is false. `priority` (0-7, or `emerg` to `debug`) gives that and worse; `limit` the
page size (100 unless asked, at most 500). A message longer than 2048 characters is cut. **What's
served** is the kernel's messages, the agent's (`frc-spotter.service`), and those of the units its
packs name (`journalUnits`), and nothing else: logins and other services aren't served.

### `POST /v1/shutdown`

A soft power-off, for a coprocessor kept powered after the robot is switched off, so it stops
cleanly rather than losing power mid-write. The agent logs the request, answers 202 at once
(`{"shuttingDown": true, "alreadyRequested": false}`), then runs `systemctl poweroff`, which stops
every service in order, and waits for each, before the power goes: the software on it saves what it
has without any step of the agent's. Asking again while it's under way answers 202 with
`alreadyRequested: true`. If powering off fails, health says `shutdown failed`, and the next request
tries again.

**Who may ask: an address check, not authentication.** Only the robot controller may ask:
10.TE.AM.2 on the network of the computer's own 10.TE.AM.x address, worked out each time from its
addresses as they are then, with nothing configured (`controller` in `agent.json`, or
`--controller`, names another). Anyone else is refused (403, logged at most once in 10 seconds). A
computer with no 10.x address, or with addresses on more than one 10.x network, can't tell which
robot it's on: it refuses every shutdown, and says so in health's problems (`shutdown refused:
...`). It keeps an accidental click elsewhere from switching vision off; it doesn't stop a laptop
given the controller's address. That's the trust the robot's network already gives the Driver
Station. polkit enforces the rest: the agent's account may power off, and nothing else.

## Probes

A probe is a named check its pack fixes: what it runs, reads, or asks is in its pack's file, and
the robot can only name it. A computer runs its packs' probes (at most 64 together), on the agent's
schedule or when asked.

| Kind | Parameters | Passes when | Its value |
|---|---|---|---|
| `command` | `argv`, `exit` (0; -1 for any), `match` (a regular expression) | it exits so and prints a match | the match's first group, else the first line |
| `http` | `url` (localhost only), `status` (200), `field`, `equals` | it answers that status (and the JSON member equals) | the member, else the status |
| `file` | `path`, `test` (`exists`, `size`, `sha256`, `json`, `text`), `minBytes`, `maxBytes`, `sha256`, `field`, `equals`, `match` | the file passes its test | its size, hash, member, or matched text |
| `unit` | `unit`, `state` (`active`) | systemd says that state | `active/running`, and so on |
| `usb` | `path` (a `/dev/.../by-path/` entry), `minSpeedMbps` | something is plugged in there, fast enough | its port, link speed, and product |
| `threshold` | `metric`, `min`, `max` | the agent's measurement is within | the measurement |

Every probe has `id`, `kind`, `every` (seconds between runs; 0 runs only when asked), `timeout`
(2 s unless it says; at most 120), and optionally `watch`: files whose change reruns it at once,
while between changes it runs only every 5 minutes, so a costly probe (a database's hash) costs
nothing while nothing changes.

**Programs run directly, never through a shell**, as the agent's user, with the arguments their
pack fixes: the agent adds nothing to them, and nothing comes from a request. Any installed program
may be named, an interpreter running a team's script included (`[python3, /opt/team/check.py]`):
a pack is trusted by its file (*Packs*), not by what it runs. What a program prints is read to a
bound and it's stopped at its timeout. At most two probes run on schedule at once (the rest wait
their turn), and two more when asked.

**The metrics** a `threshold` probe reads (`Metrics.NAMES`): `cpu.busiest.percent`,
`cpu.total.percent`, `cpu.capped`, `thermal.hottest.celsius`, `thermal.margin.celsius`,
`memory.available.percent`, `memory.available.mb`, `disk.root.free.percent`,
`disk.data.free.percent`, `disk.data.free.mb`, `drive.celsius`, `drive.used.percent`,
`drive.critical.warning`, `drive.media.errors`, `journal.errors`, `boot.root.readonly`,
`uptime.seconds`.

## Packs

A pack is one YAML file: the probes on one piece of software or one board, and the journal units it
serves. It holds no code and needs no build step, polkit rule, or privilege. The agent reads every
`*.yaml` in `/etc/frc-spotter/packs/`, in the order of their names, once as it starts (restart it,
`systemctl restart frc-spotter`, after changing them). With none, it reports the computer alone.

```yaml
# /etc/frc-spotter/packs/vision.yaml: a vision program run as a systemd unit, with a status page
pack: vision
journalUnits: [vision.service]         # units whose journal it serves and counts
probes:
  - id: vision.unit
    kind: unit
    unit: vision.service
    every: 2
  - id: vision.http
    kind: http
    url: http://localhost:5800/api/status
    every: 5
  - id: vision.settings-changed        # a cheap change signal: the settings file's hash
    kind: file
    path: /opt/vision/settings.db
    test: sha256
    watch: [/opt/vision/settings.db-wal]
```

**A pack is trusted by its file**, as `sshd` trusts its configuration: the agent ignores one that
isn't root's, or that its group or anyone else may write (`chmod 644`, owned by root), and says
which and why in health's problems and its journal. It also ignores, and says so, one it can't
read, one whose name another pack took first, and one defining a probe another already did; the
others run. A pack being there means its software should be: a missing program fails its probes,
it doesn't switch the pack off.

**The catalog.** Spotter's repository keeps packs to copy in `packs/`, each file headed by what it
checks and where it goes: `photonvision.yaml` (its service, web server, and a settings-changed
signal), `health.yaml` (the agent's own measurements held to limits: thermal margin, CPU capped,
memory, root disk free), `orangepi-rk3588.yaml` (what an unprivileged user can read of the
RK3588's accelerators, and what can't be read), `raspberry-pi.yaml` (under-voltage, from the
kernel's `rpi_volt` hwmon rather than `vcgencmd`, which needs a device the agent's sandbox
closes), and `my-program.yaml` (a template for a team's own service). CI reads every one with the agent's own reader (`CatalogTest`), and copies them all onto
one computer together, so the catalog can't drift from the format. The package installs none: a
team copies the ones it wants, and pins them by doing so.

**Reading it.** `Pack.parseYaml` (in the api jar) reads a pack without a YAML library, refusing
anything it would read differently from yq: one key per line, `- ` lists or one-line `[a, b]`
lists, plain or quoted values, `#` comments. `{...}` mappings, anchors, tags, and multi-line values
are refused with their line. A key it doesn't know is a problem, so a misspelling can't pass
silently, and every problem names its file and line. A pack's own tests can read it the same way.

**Testing a pack.** The container harness (`:harness`, `com.michaelgrundvig.frc.spotter:harness`)
is a library: `Images` builds Debian 13 under systemd with the agent installed from its `.deb`
(`Images.base()`, `Images.installAgent(context)`, `Images.installPacks(context, packs)`,
`Images.build(...)`), `Coprocessor` runs one at a fixed address and hostname on `TestNetwork` with
its root read-only and `/data` writable, `Coprocessor.client` is the robot's client for it, and
`Toxiproxied` puts Toxiproxy between them. A pack's tests build an image with its software and pack
on top, and test them as the robot sees them; mark them `@ContainerTest`. They need the agent's
package, as `-Dspotter.agentDeb=<.deb>` (a release's) or `-Dspotter.agentPackage=<folder>`
(`./gradlew :agent:agentPackage`'s, with `DEBIAN/`).

## Overrides: `/etc/frc-spotter/agent.json`

Optional, for an unusual setup; the agent needs none of it. It may set the port, the controller's
address, and the address the agent listens on, and nothing else:

```json
{"port": 5809, "controller": "10.12.34.2", "bind": "10.12.34.11"}
```

`port` is 1024 to 65535 (5808 unless it says); `controller` and `bind` are IPv4 addresses written
out. A file that can't be read, or that sets anything else (an `agent.json` from before 0.3.0, with
its `name`, `packs`, and `cameras`), is ignored, and health's problems say why. The agent reads it
once, at start.

`frc-spotter [serve] [--port=N] [--bind=ADDRESS] [--controller=ADDRESS] [--root=DIR]`:
`--port`, `--bind`, and `--controller` override the file; `--root` reads the computer's files from a
folder (the tests' fixture trees).

## The package

The agent's artifacts, all Java 17 bytecode (`--release 17`, built with JDK 25), from
`./gradlew :agent:agentRelease` into `agent/build/distributions/`, and from a version tag into a
GitHub release, with their checksums:

| Artifact | For | Size |
|---|---|---|
| `frc-spotter_<version>_<arch>.deb` (arm64, amd64) | Debian, Ubuntu, Armbian: carries its own Java runtime (jlink, JDK 25: `java.base` and `jdk.httpserver`) | 19.5 MB |
| `frc-spotter-<version>-linux-<arch>.tar.gz` | Systems without dpkg: the same files, with `install.sh [--root DIR]` | 23 MB |
| `frc-spotter-<version>-all.jar` | A board with its own Java 17 or newer | 0.26 MB |
| `frc-spotter-<version>-all.tar.gz` | The same, with its unit, launcher, drive-health timer, polkit rules, sysusers file, and `install.sh` | 0.25 MB |

The runtime is jlink's for the computer building it, so each architecture's `.deb` is built on it.

```
/usr/lib/frc-spotter/frc-spotter.jar
/usr/lib/frc-spotter/bin/frc-spotter      the launcher
/usr/lib/frc-spotter/bin/frc-spotter-drive   the drive-health job, run as root by its timer
/usr/lib/frc-spotter/runtime/             the .deb's and tarball's Java
/usr/lib/systemd/system/frc-spotter.service
/usr/lib/systemd/system/frc-spotter-drive.service, frc-spotter-drive.timer
/usr/lib/sysusers.d/frc-spotter.conf      its account, in systemd-journal
/usr/share/polkit-1/rules.d/60-frc-spotter.rules   its account may power off
/usr/share/polkit-1/rules.d/99-frc-spotter.rules   ... and nothing else
/etc/frc-spotter/packs/                   empty: the packs a team copies in
/etc/frc-spotter/agent.json               not installed: the optional overrides
```

**Installed:** the `.deb`'s maintainer scripts (and `install.sh`) make its account with
systemd-sysusers, make `/etc/frc-spotter/packs/`, and enable its unit and its drive-health timer;
on a running system they start them, and in a chroot building an image (no systemd running) they
start nothing. No pack is installed. It depends on systemd and polkitd, and recommends nvme-cli. **The
launcher** runs the package's own runtime when there is one, else the board's `java`, with a 64 MB
heap, the serial collector, the quick compiler only, and `-XX:+ExitOnOutOfMemoryError`. **The
unit** runs it unprivileged with no capabilities, on the small cores (`AllowedCPUs=0-3`) at
`Nice=10`, capped at 200 MB, read-only everywhere but its own `/tmp`, able to reach only the robot
network, link-local addresses, and itself, with systemd's other sandboxing on.

**What it costs**, measured in the container tests (x86, rootless Podman): the agent's unit at 34
to 38 MiB on its own runtime, 28 MiB from the `-all.jar` on Temurin 17.

**The drive's health** takes root to read, so the package's own timer (`frc-spotter-drive.timer`,
15 s after boot and then each minute) runs a small root job, `frc-spotter-drive`, that writes
nvme-cli's reports of `/dev/nvme0` (`nvme smart-log` and `nvme id-ctrl`, as JSON) to
`/run/frc-spotter/`, root's and readable by anyone, each written whole and then renamed. The agent
reads those files and nothing else of the drive. Without a drive, or without nvme-cli, the job
leaves no files (and says why in its journal, for nvme-cli), and `drive` is `null`. Its unit
sandboxes it (no network, a read-only system but its runtime directory) and logs only its warnings,
not systemd's line each minute. Tested in a container with nvme-cli stood in for; on a board's
real drive, unverified.

## Tests

`./gradlew ci` runs every check. The unit tests need nothing of the computer they run on: the
agent's read fixture trees (an RK3588's sysfs, its network interfaces and USB devices, canned
command output for the journal, systemd, and the clock), and the catalog's every pack.

**The container tests** (`harness/`, tagged `container`) need Docker or rootless Podman, and skip
without one but in CI on Linux, where they must run. `./gradlew test -Pquick` leaves them out unless
`--tests` names them. They build their images once, named by a hash of what they're built from:
the agent's (Debian 13, pinned by digest, under systemd as its first process, with no Java, the
agent installed from its `.deb`, test packs copied in with two it mustn't trust, a stand-in for the
software it watches, labels in os-release, a stand-in for nvme-cli, and a fixture tree of USB
devices; the root read-only and `/data` a volume, as on a board), and Java 17's (stock Temurin 17 on Ubuntu under systemd, the
agent installed from the `-all.jar` with its `install.sh`). Each coprocessor has a hostname and a
fixed address on a test network, 10.99.71.x (team 9971).

The tests play the robot with the client: every endpoint on the wire, each kind of probe against
real files, units, a shell, and a web server, the packs it mustn't trust, its network link, USB
devices (a container sees its host's, or none), and clock, the drive-health timer writing for it
as root, the deploy check against its identity, Toxiproxy between the client and the agent (latency under and past its timeouts,
dropped and reset connections, a stalled connection, a stopped agent: each goes missing after 3 s
and recovers within a poll), soft-off (taken with nothing configured from a robot container at
10.99.71.2, refused from one at .50, taken from the test through port forwarding when named with
`--controller`, gone from both ports in about 1 s, the container powered off cleanly), an agent
killed or hung (named, after the client's 30 s, with why), and the `-all.jar` on Java 17. Rootless
Podman needs `SYS_ADMIN` in the container's user namespace and no SELinux labels; Docker,
privileged mode.

## JSON, without a library

The robot program and the agent read and write JSON through one small class,
`com.michaelgrundvig.frc.spotter.json.Json`, with nothing but the JDK, so nothing extra reaches the
robot program or the agent, and a hash computed of JSON stays the same across versions:

- A parsed number keeps the text it was written as, so a value read and written again is unchanged
  digit for digit.
- `Json.compact` writes members in their order with no spaces: what the agent sends.
- `Json.pretty` writes members sorted by name, two-space indents, and a newline at the end, with a
  container of plain values short enough (80 characters) on one line: the form files take in Git.
- `Json.hashable` writes sorted, with no spaces and every number in one form (`1`, `1.0`, and
  `1e0` alike), for hashes.

Names sort by UTF-16 code units, as RFC 8785 does; strings escape only what JSON requires.
