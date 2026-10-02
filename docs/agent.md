# The coprocessor agent

Each coprocessor on the robot runs the agent, a small web service the robot polls. The agent knows
the computer, not the software on it: it reports the computer's health (load, heat, memory, disks,
the journal, the drive, how it booted) and runs **probes**, named checks that **packs** define. A
pack says what to check of one program, and how to stop it before a power-off; an image builder
ships the packs for the software in its images. The robot decides what it needs of each probe; the
agent only reports.

| Path | What |
|---|---|
| `api/` (Gradle `:api`) | What the agent, the robot, and the build share: the API's records, the probes, the table and packs, the build tool, and JSON. Java 17 |
| `agent/` (`:agent`) | The agent, its systemd unit, launcher, polkit rules, and package (`agent/package/`) |
| `packs/builtin/pack.yaml` | The built-in pack, which every computer runs: the agent's own measurements as probes |
| `client/` (`:client`) | The robot's side, plain Java (`docs/robot.md`) |
| `harness/` (`:harness`) | The container harness, a library for container tests of the agent and packs, and Spotter's own container tests |
| `examples/` | An example table, and an example pack with a Java helper (`:java-helper-pack`) |

## The agent's API, version 1

HTTP, JSON, no authentication, on the configuration's port (5808 unless it says). It runs on a
closed robot network, runs nothing a request names (a probe is asked for by its id; everything it
runs is fixed in the image), and bounds every answer. It's read-only but for one action, a soft
power-off, which only the robot controller's address may ask for.

| Request | Answer |
|---|---|
| `GET /v1/stamp` | `Stamp`: which image this is, as which computer, and the hash of the probes it runs |
| `GET /v1/health` | `Health`: the computer's health and every probe's latest result |
| `GET /v1/journal?cursor=&before=&from=&priority=&unit=&limit=` | `JournalPage`: a page of the journal |
| `GET /v1/probes` | Every probe's latest result |
| `GET /v1/probes/<id>` | Runs that probe now and answers its result; asked again within 2 s, the last one |
| `GET /v1/downloads/<name>` | A file a pack serves, such as a backup of its software's settings |
| `POST /v1/shutdown` | `ShutdownAnswer` (202): runs the packs' steps (each stops the software it watches), then powers the computer off. From the robot controller only |

A refusal or a failure answers `{"error": "..."}` with its status: 400 for a bad query, 403 for a
shutdown it won't take, 404 for an unknown path, probe, or download, 405 for the wrong method (with
`Allow`), 421 for a request not addressed to it, 503 (with `Retry-After`) when a journal page or a
download finds another under way, or a probe run finds two, and 500 when the agent failed (what went
wrong is in its journal).

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
{"name": "vision-front", "team": 1234, "address": "10.12.34.11", "version": "coprocessors-2027.1",
 "recipeHash": "4f2c…", "builtAt": "2027-01-10T18:30:00Z",
 "labels": {"board": "orangepi-5", "visionVersion": "v2027.1.0"},
 "probesHash": "77d0…", "bootId": "3c1e6a2e-…", "mac": "c0:74:2b:fe:12:34"}
```

The image's identity, which its builder writes to `/etc/coprocessor/stamp.json` (all but
`probesHash`, `bootId`, and `mac`, which the agent adds, plus `agentPort`, the port it serves on
when its configuration doesn't say): the computer's name, team, and address; the image's version
and the hash of what built it; when it was built; and **labels**, the builder's own facts about the
image (the software in it and its version, the board it's for), which Spotter only carries.
`probesHash` is the hash of the probe set the agent compiled from its configuration and installed
packs: the robot's build compiles the same set from the table and the packs, so equal hashes mean
the computer runs the probes the robot expects.

### `Health`

| Member | What |
|---|---|
| `stamp` | As `/v1/stamp` |
| `boot` | Boot ID, uptime, the agent's monotonic clock (microseconds since boot, the journal's clock), whether the boot before shut down cleanly (`null`: unknown), the root device and whether it's read-only, the SPI bootloader's version |
| `cpu` | Each core's busy percentage; each cluster's cores, current, limit, and maximum MHz |
| `thermal` | Every thermal zone's temperature, with its trip points |
| `memory` | Total and available MiB |
| `disks` | The root's and `/data`'s size and free space, for those mounted |
| `journal` | This boot's count of USB, UVC, filesystem, out-of-memory, thermal, and other errors, with the latest three, from the kernel, the agent, and the units the packs name |
| `drive` | The NVMe drive's temperature, wear, unsafe shutdowns, power cycles, hours, media errors, and critical warnings; `null` without one |
| `probes` | Every probe's latest `ProbeResult`, in the configuration's order |
| `problems` | What the agent couldn't read, each saying what and why (at most 10). `shutting down: asked for by the robot` comes first once a shutdown is under way |

It's answered at once from the last reading; older than half a second, a new reading starts in the
background. Measured in the container tests: about 5.6 KB with the test packs' 20-odd probes.

### `ProbeResult`

```json
{"id": "java-helper.java", "kind": "command", "status": "pass", "value": "17", "detail": "",
 "ranMicros": 81234567, "durationMillis": 42.0}
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
served** is the kernel's messages, the agent's (`frc-coprocessor-agent.service`), and those of the
units the configured packs name (`journalUnits`), and nothing else: logins and other services
aren't served.

### `POST /v1/shutdown`

A soft power-off, for a coprocessor kept powered after the robot is switched off, so it stops
cleanly rather than losing power mid-write. The agent logs the request, answers 202 at once
(`{"shuttingDown": true, "alreadyRequested": false}`), runs each configured pack's
`beforeShutdown` steps in order (a vision program's pack stops it, so it saves its settings; each
step bounded by its own timeout), then `systemctl poweroff`, which it runs even if a step failed.
Asking again while it's under way answers 202 with `alreadyRequested: true`. If powering off
fails, health says `shutdown failed`, and the next request tries again.

**Who may ask: an address check, not authentication.** Only the configuration's `controller`
(10.TE.AM.2, the robot controller, as the build writes it; without one, 10.TE.AM.2 by the stamp's
team) may ask; anyone else is refused (403, logged at most once in 10 seconds), as is every request
on a computer with neither. It keeps an accidental click elsewhere from switching vision off; it
doesn't stop a laptop given the controller's address. That's the trust the robot's network already
gives the Driver Station. polkit enforces the rest: the agent's account may power off, and do what
its packs' rules grant, and nothing else.

## Probes

A probe is a named check the image fixes: what it runs, reads, or asks is in its definition, and
the robot can only name it. They come from packs (and, rarely, the table's `probes:` for one
computer), are compiled with the computer's configuration into a **probe set** (format 1; at most 64
probes, 8 steps, 16 journal units, 8 downloads), and run on the agent's schedule or when asked.

| Kind | Parameters | Passes when | Its value |
|---|---|---|---|
| `command` | `argv`, `exit` (0; -1 for any), `match` (a regular expression) | it exits so and prints a match | the match's first group, else the first line |
| `http` | `url` (localhost only), `status` (200), `field`, `equals` | it answers that status (and the JSON member equals) | the member, else the status |
| `file` | `path`, `test` (`exists`, `size`, `sha256`, `json`, `text`), `minBytes`, `maxBytes`, `sha256`, `field`, `equals`, `match` | the file passes its test | its size, hash, member, or matched text |
| `unit` | `unit`, `state` (`active`) | systemd says that state | `active/running`, and so on |
| `usb` | `path` (a `/dev/v4l/by-path/` entry), `minSpeedMbps` | something is plugged in there, fast enough | its link speed, vendor, and product |
| `threshold` | `metric`, `min`, `max` | the agent's measurement is within | the measurement |

Every probe has `id`, `kind`, `every` (seconds between runs; 0 runs only when asked), `timeout`
(2 s unless it says; at most 120), and optionally `watch`: files whose change reruns it at once,
while between changes it runs only every 5 minutes, so a costly probe (a database's hash) costs
nothing while nothing changes. `each: camera` writes one probe per configured camera.

**Programs run directly, never through a shell**, as the agent's user, with the agent's Java named
in `FRC_AGENT_JAVA`. A definition may not run a shell or a program that runs another (`sh`, `bash`,
`busybox`, `env`, `sudo`, `xargs`, `systemd-run`, `python*`, `perl*`, ...: `Check.NOT_RUN`);
arguments are fixed text. What a program prints is read to a bound and it's stopped at its timeout.
At most two probes run on schedule at once (the rest wait their turn), and two more when asked.

**The metrics** a `threshold` probe reads (`Metrics.NAMES`): `cpu.busiest.percent`,
`cpu.total.percent`, `cpu.capped`, `thermal.hottest.celsius`, `thermal.margin.celsius`,
`memory.available.percent`, `memory.available.mb`, `disk.root.free.percent`,
`disk.data.free.percent`, `disk.data.free.mb`, `drive.celsius`, `drive.used.percent`,
`drive.critical.warning`, `drive.media.errors`, `journal.errors`, `boot.root.readonly`,
`uptime.seconds`.

## Packs

A pack is a folder: its definitions as `pack.json` (built from its `pack.yaml`), and whatever
programs and polkit rules it brings. Installed, they sit under `/usr/lib/frc-coprocessor/packs/`:

```
/usr/lib/frc-coprocessor/packs/
  builtin/pack.json                     the agent package's: its measurements as probes
  java-helper/pack.json                 the example pack (examples/packs/java-helper)
  java-helper/bin/java-helper           its launcher: exec "${FRC_AGENT_JAVA:-java}" -jar ...
  java-helper/lib/java-helper.jar
/usr/share/polkit-1/rules.d/
  60-frc-coprocessor-agent.rules        the agent's account may power off
  61-...rules to 98-...rules            each pack's: what its steps need (stopping its unit, say)
  99-frc-coprocessor-agent.rules        ... and nothing else
```

A pack's rule is numbered 61 to 98, so it runs after the agent's and before the agent's last, which
refuses the agent's account everything no earlier rule granted.

`pack.yaml`, for a vision program run as a systemd unit with a status page:

```yaml
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
  - id: vision.settings-hash
    kind: command
    argv: ['{pack}/bin/vision-helper', hash, /data/vision/settings.db]
    match: '^([0-9a-f]{64})$'
    every: 10
    watch: [/data/vision/settings.db]
    timeout: 20
beforeShutdown:                        # steps a shutdown runs first, in order
  - name: vision.stop
    argv: [systemctl, stop, vision.service]
    timeout: 120
downloads:                             # files served at /v1/downloads/<name>
  - name: settings.zip
    argv: ['{pack}/bin/vision-helper', settings-zip, /data/vision/settings.db, '{computer}']
    contentType: application/zip
    maxBytes: 67108864
    timeout: 60
```

Placeholders, filled when the set is compiled: `{agent}` (the agent's folder), `{runtime}` (its
Java runtime), `{pack}` (the pack's folder), `{computer}`, and in an `each: camera` definition,
`{camera}` and `{port}`. The build checks a pack and writes its `pack.json` with `CoprocessorBuild
pack pack.yaml pack.json`; the agent reads only `pack.json`.

**A pack's Java helper** runs on `FRC_AGENT_JAVA`, the Java the agent runs on: the `.deb`'s own
runtime (`java.base` and `jdk.httpserver` only) or the board's. A helper that needs more modules
(`java.sql`, say) runs on the board's own Java instead. `examples/packs/java-helper` is a whole
pack with a Java helper, its build (`./gradlew :java-helper-pack:packFolder`), and its install
script: a copy is the start of a pack of your own.

**Testing a pack.** The container harness (`:harness`, `com.michaelgrundvig.frc.spotter:harness`)
is a library: `Images` builds Debian 13 under systemd with the agent installed from its `.deb`
(`Images.base()`, `Images.installAgent(context)`, `Images.build(...)`), `Coprocessor` runs one at a
fixed address on `TestNetwork` with its root read-only and `/data` writable, `Coprocessor.client`
is the robot's client for it, and `Toxiproxied` puts Toxiproxy between them. A pack's tests build
an image with its software and pack on top, and test them as the robot sees them; mark them
`@ContainerTest`. They need the agent's package, as `-Dspotter.agentDeb=<.deb>` (a release's) or
`-Dspotter.agentPackage=<folder>` (`./gradlew :agent:agentPackage`'s, with `DEBIAN/`).

## Configuration: `/etc/frc-coprocessor/agent.json`

One file per computer, written into its image at stamping, or on a board whose root is read-only
and was installed by hand, `/data/frc-coprocessor/agent.json`. The agent reads it once, at start.

```json
{
  "name": "vision-front",
  "controller": "10.12.34.2",
  "port": 5808,
  "packs": ["vision"],
  "cameras": [{"name": "front-left", "port": "platform-fc800000.usb-usb-0:1:1.0-video-index0"}],
  "probes": []
}
```

`packs` are the packs it runs besides the built-in one, in order; each must be installed, or the
agent says so in health's problems. `cameras` are the cameras it runs with the USB port each is
plugged into (its `/dev/v4l/by-path/` entry), for `each: camera` probes. `probes` are the table's
own for this computer. Without a file, the agent runs the built-in pack alone, and takes a
shutdown only from 10.TE.AM.2 by its stamp's team.

`frc-coprocessor-agent [serve] [--port=N] [--bind=ADDRESS] [--controller=ADDRESS] [--root=DIR]`:
`--port` and `--controller` override the file; `--root` reads the computer's files from a folder
(the tests' fixture trees).

## The table: `coprocessors/coprocessors.yaml`

A team describes its coprocessors once, in its robot repository (`examples/coprocessors.yaml`):

```yaml
team: 1234
agentPort: 5808            # the agents' port, unless a computer says otherwise
image:                     # what the image builder is told, for every computer
  recipe: vision-orangepi
computers:
  - name: vision-front     # its hostname: lowercase letters, digits, hyphens
    address: 11            # 10.TE.AM.11 (.6 to .19)
    cameras: [front-left, front-right]
    packs: [vision]
    ports:
      front-left: platform-fc800000.usb-usb-0:1:1.0-video-index0
    image:                 # what the image builder is told about this computer
      board: orangepi-5
```

| Key | Required | What it may be |
|---|---|---|
| `team` | yes | 0 to 25599. A computer's address is 10.TE.AM.x: team 1234's `.11` is 10.12.34.11 |
| `agentPort` | no, 5808 | 1024 to 65535 (an image builder may refuse its software's ports) |
| `image` | no | A mapping of names to single values, for the image builder; Spotter only carries it |
| `computers[].name` | yes | A hostname: lowercase letters, digits, and hyphens, starting with a letter and not ending with a hyphen, at most 63 characters. Unique |
| `computers[].address` | yes | 6 to 19: FRC's static range for devices on the robot. Unique |
| `computers[].cameras` | no | Camera names: printable ASCII, not empty, no leading or trailing space, at most 64 characters, each once |
| `computers[].packs` | no | The packs it runs besides the built-in one |
| `computers[].ports` | no | Each camera's `/dev/v4l/by-path/` entry, for the built-in pack's camera probes |
| `computers[].probes` | no | Probes of its own, written as a pack's are |
| `computers[].image` | no | As `image`, for this computer |
| `computers[].agentPort` | no, the table's | As `agentPort` |

`Table.readYaml` reads it without a YAML library, refusing anything it would read differently from
yq, and lists every problem with its line; a key it doesn't know is a problem, so a misspelling
can't pass silently. An image builder checks its own `image` settings and rules on top.

**The build tool** (`com.michaelgrundvig.frc.spotter.tools.CoprocessorBuild`, in the api jar)
compiles what the robot and the images need from the table: `table` (the compiled table the robot
program carries), `probes` (each computer's probe set and its hash), `agent-configs` (each
computer's `agent.json`), and `pack` (a pack's `pack.json`). It finds a computer's packs in the
team's `coprocessors/packs/`, then in each `--packs DIR` given (an image builder's), then in
`packs/`; the built-in pack is in the jar.

**The compiled table** (`CompiledTable`) is the table with each computer's probe set and what an
image builder adds: the recipe hash it would build with, and labels every stamp must carry with
these values. `compare(computer, stamp)` says how a stamp differs: name, team, address, or an
expected label are errors (a deploy fails); the recipe hash or the probes hash, warnings (flash the
current release when convenient); no recipe hash, unknown.

## The package

The agent's artifacts, all Java 17 bytecode (`--release 17`, built with JDK 25), from
`./gradlew :agent:agentRelease` into `agent/build/distributions/`, and from a version tag into a
GitHub release, with their checksums:

| Artifact | For | Size |
|---|---|---|
| `frc-coprocessor-agent_<version>_<arch>.deb` (arm64, amd64) | Debian, Ubuntu, Armbian: carries its own Java runtime (jlink, JDK 25: `java.base` and `jdk.httpserver`) | 19.5 MB |
| `frc-coprocessor-agent-<version>-linux-<arch>.tar.gz` | Systems without dpkg: the same files, with `install.sh [--root DIR]` | 23 MB |
| `frc-coprocessor-agent-<version>-all.jar` | A board with its own Java 17 or newer | 0.26 MB |
| `frc-coprocessor-agent-<version>-all.tar.gz` | The same, with its unit, launcher, polkit rules, sysusers file, built-in pack, and `install.sh` | 0.25 MB |

The runtime is jlink's for the computer building it, so each architecture's `.deb` is built on it.

```
/usr/lib/frc-coprocessor-agent/frc-coprocessor-agent.jar
/usr/lib/frc-coprocessor-agent/bin/frc-coprocessor-agent    the launcher
/usr/lib/frc-coprocessor-agent/runtime/                     the .deb's and tarball's Java
/usr/lib/frc-coprocessor/packs/builtin/pack.json
/usr/lib/systemd/system/frc-coprocessor-agent.service
/usr/lib/sysusers.d/frc-coprocessor-agent.conf              its account, in systemd-journal
/usr/share/polkit-1/rules.d/60-frc-coprocessor-agent.rules, 99-frc-coprocessor-agent.rules
/etc/frc-coprocessor/                                        its configuration
```

**Installed:** the `.deb`'s maintainer scripts (and `install.sh`) make its account with
systemd-sysusers and enable its unit; on a running system they start it, and in a chroot building
an image (no systemd running) they start nothing. It depends on systemd and polkitd. **The
launcher** runs the package's own runtime when there is one, else the board's `java`, with a 64 MB
heap, the serial collector, the quick compiler only, and `-XX:+ExitOnOutOfMemoryError`. **The
unit** runs it unprivileged with no capabilities, on the small cores (`AllowedCPUs=0-3`) at
`Nice=10`, capped at 200 MB, read-only everywhere but its runtime directory (where a pack helper
may unpack a native library), able to reach only the robot network, link-local addresses, and
itself, with systemd's other sandboxing on.

**What it costs**, measured in the container tests (x86, rootless Podman): the agent's unit at 34
to 38 MiB on its own runtime, 28 MiB from the `-all.jar` on Temurin 17; the example pack's helper
(a JVM start and a file's hash) in about 40 ms.

**The drive's health** takes root to read, so an image runs a root job that writes nvme-cli's
reports (`nvme smart-log` and `nvme id-ctrl`, as JSON) to `/run/coprocessor/` every minute, and the
agent reads those. Without them, `drive` is `null`.

## Tests

`./gradlew ci` runs every check. The unit tests need nothing of the computer they run on: the
agent's read fixture trees (an RK3588's sysfs, canned command output).

**The container tests** (`harness/`, tagged `container`) need Docker or rootless Podman, and skip
without one but in CI on Linux, where they must run. `./gradlew test -Pquick` leaves them out unless
`--tests` names them. They build their images once, named by a hash of what they're built from:
the agent's (Debian 13, pinned by digest, under systemd as its first process, with no Java, the
agent installed from its `.deb`, test packs, a stand-in for the software it watches, and a fixture
tree of USB devices; the root read-only and `/data` a volume, as on a board), and Java 17's (stock
Temurin 17 under systemd, the agent installed from the `-all.jar` with its `install.sh`, and the
example pack). Each coprocessor has a fixed address on a test network, 10.99.71.x (team 9971).

The tests play the robot with the client: every endpoint on the wire, each kind of probe against
real files, units, and a web server, the deploy check, Toxiproxy between the client and the agent
(latency under and past its timeouts, dropped and reset connections, a stalled connection, a
stopped agent: each goes missing after 3 s and recovers within a poll), soft-off (refused from
another address, taken from the controller's, gone from both ports in about 1 s, the container
powered off cleanly), an agent killed or hung (named, after the client's 30 s, with why), and a
pack's Java helper on Java 17. Rootless Podman needs `SYS_ADMIN` in the container's user namespace
and no SELinux labels; Docker, privileged mode.

## JSON, without a library

The robot program, the agent, and the build all read and write JSON through one small class,
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
