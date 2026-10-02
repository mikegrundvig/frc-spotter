# Vision coprocessors

Each vision coprocessor (an Orange Pi 5-family board running PhotonVision) is built from this
repository: one table says which computers the robot has, and each one runs a small health agent
the robot polls. This folder holds what they share with the robot program.

| Path | What |
|---|---|
| `coprocessors/coprocessors.yaml` (at the repository's root) | The table: the team's coprocessors, the one place they're described |
| `coprocessors/<computer>/settings/` | Each computer's PhotonVision settings and calibrations, one file per row |
| `coprocessor/photonvision.lock` | The PhotonVision version the coprocessors run, with the checksums an image is built from |
| `coprocessor/common/` (Gradle `:coprocessor-common`) | What the robot program, the agent, and the build share: the table, the agent's API records, the settings' canonical form and hash, and the JSON they're written in |
| `coprocessor/agent/` (Gradle `:coprocessor-agent`) | The health agent each coprocessor runs, and its systemd unit |

## The table: `coprocessors/coprocessors.yaml`

```yaml
team: 0                # the team number; addresses are 10.TE.AM.x
agentPort: 5808        # the health agent's port, unless a computer says otherwise
computers:
  - name: vision-front # its hostname: lowercase letters, digits, hyphens
    address: 11        # the last number of its address: 10.TE.AM.11 (.6 to .19)
    board: orangepi-5  # orangepi-5 | orangepi-5b | orangepi-5-plus | orangepi-5-pro | orangepi-5-max
    cameras: [front-left, front-right]   # the PhotonVision camera names it runs (its role)
```

| Key | Required | What it may be |
|---|---|---|
| `team` | yes | 0 to 25599. A computer's address is 10.TE.AM.x: team 1234's `.11` is 10.12.34.11 |
| `agentPort` | no, 5808 | 1024 to 65535, except 5800 (PhotonVision's page), 5810 (NetworkTables), and 1181 to 1200 (PhotonVision's camera streams, which start at 1181, two ports per camera). 5808 sits in FRC's team range, 5800-5810 |
| `computers` | yes (`[]` for none) | A list of computers |
| `computers[].name` | yes | A hostname: lowercase letters, digits, and hyphens, starting with a letter and not ending with a hyphen, at most 63 characters. Unique |
| `computers[].address` | yes | 6 to 19: FRC's static range for devices on the robot (WPILib's "IP Configurations"; .20-.199 is the field's DHCP pool, .200-.219 the radio's). 11 and up by convention. Unique |
| `computers[].board` | yes | One of the five boards above |
| `computers[].cameras` | no, none | PhotonVision camera names: the names robot code gives `PhotonCamera`. Printable ASCII (letters, digits, spaces, punctuation), not empty, no `/`, no leading or trailing space, at most 64 characters. Unique across the whole table, since PhotonVision publishes each one at `/photonvision/<camera>` |
| `computers[].agentPort` | no, the table's | As `agentPort` |

The build reads the table and refuses one with a problem, listing every problem with its line. A
key it doesn't know is a problem too, so a misspelling can't pass silently.

The template's table is an example: team 0, one computer, no settings. The robot's build accepts
team 0 and an empty list, but the image workflow builds nothing until a team sets its number and
lists at least one computer.

**The YAML it reads.** The table is read without a YAML library, so nothing extra reaches the robot
program or the agent. It reads the YAML above and anything shaped like it: one `key: value` per
line, nested by indenting with spaces; lists as `- item` lines (at the key's indent or deeper) or
on one line in `[...]`; plain, `'single'`, and `"double"` quoted values; and `#` comments.
Everything else YAML allows (tabs, `{...}` mappings, anchors, tags, directives, multi-line values,
more than one document, nesting more than 16 deep) is refused with its line number, rather than
read in a way you didn't mean. Numbers are written plainly, without leading zeros. A name, board,
or camera that YAML would read as something other than text (`~`, `null`, `true`, `false`, a
number) must be in quotes: `cameras: ["1"]`. So this reader and other YAML tools (the image
workflow reads the table with yq) always agree on what the table says.

In Java: `Table.readYaml(path)` (or `Table.parseYaml(text, name)`) gives a checked `Table`, or a
`TableException` whose `problems()` are the messages. A `Table` is checked however it's made, from
JSON too.

## The agent's API, version 1

Each coprocessor's agent answers on its `agentPort`: HTTP, JSON, no authentication. It runs on a
closed robot network, runs nothing a request says, and bounds every answer. It's read-only but for
one action, a soft power-off, which only the robot controller's address may ask for (a check of
the address a request comes from, not authentication: see below).

| Request | Answer |
|---|---|
| `GET /v1/stamp` | `Stamp`: which image this is, and as which computer |
| `GET /v1/health` | `Health`: the computer's health, 3 to 4 KB |
| `GET /v1/journal?cursor=&before=&from=&priority=&unit=&limit=` | `JournalPage`: a page of the journal |
| `GET /v1/settings` | PhotonVision's settings as canonical rows, with their hash |
| `GET /v1/settings.zip` | The same as files, laid out like the repository's folder for the computer, with `settings.sha256` beside the folder |
| `POST /v1/shutdown` | `ShutdownAnswer` (202): stops PhotonVision, then powers the computer off. From the robot controller (10.TE.AM.2) only |

A refusal or a failure answers `{"error": "..."}` with its status: 400 for a bad query, 403 for a
shutdown it won't take, 404 for an unknown path (or no settings database yet), 405 for the wrong
method (with `Allow`), 421 for a request not addressed to it, 503 (with `Retry-After`) when a
journal or settings request finds another under way, and 500 when the agent failed. A 500 says
only that; what went wrong is in the agent's journal (it may name files).

**Who it answers.** Each request has a thread of its own, so a client that's slow, or never
finishes sending, holds up nobody else; a request must arrive within 4 seconds, an answer may take
60, and 32 connections are open at most. The heavy requests (the journal and the settings) take
turns, and one that finds another under way is refused as busy at once, so health, the stamp, and
a shutdown never wait behind them. It answers only requests addressed to it (the `Host` a request
names is a 10.x, 169.254.x, or loopback address, `localhost`, or its own name, alone or in
`.local`), so a web page elsewhere can't reach it through a name that resolves to it. Every answer
carries `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

The answers are Java records in `com.michaelgrundvig.frc.coprocessor.api`; the paths and limits are
`AgentApi`'s constants. Each record has `toJson()` and `fromJson(...)`, and the top-level ones
`parse(String)`: the robot program reads an answer with `Health.parse(body)`. Every record's
Javadoc says what each value means and in what unit.

**Reading is tolerant one way.** A value that's missing (or `null`) reads as its default (empty,
zero, none), and a value the reader doesn't know is ignored, so a robot and an agent built a
version apart still understand each other. A value of the wrong kind (text where a number
belongs) is an error.

### `Stamp`

```json
{"name": "vision-front", "team": 1234, "address": "10.12.34.11", "board": "orangepi-5",
 "cameras": ["front-left", "front-right"], "release": "coprocessors-2027.1",
 "recipeHash": "4f2c…", "photonvisionVersion": "v2027.1.0", "settingsHash": "9a8b…",
 "bootId": "3c1e6a2e-…", "mac": "c0:74:2b:fe:12:34"}
```

`cameras` is the computer's role. `settingsHash` is the hash of the settings stamped into the
image, empty when it was built with none.

**The stamp file.** The image's stamping writes `/etc/coprocessor/stamp.json` as this JSON without
`bootId` and `mac`, which the agent adds as it answers (from
`/proc/sys/kernel/random/boot_id`, and the network interface), plus `agentPort`, the port it
serves on. Other members are ignored.

### `Health`

| Member | Record | What |
|---|---|---|
| `stamp` | `Stamp` | As `/v1/stamp` |
| `boot` | `Boot` | Boot ID, uptime, the agent's monotonic clock (microseconds since boot, the journal's clock), whether the boot before shut down cleanly (`null`: unknown), the root device and whether it's read-only, the SPI bootloader's version |
| `cpu` | `Cpu`, `CpuCluster` | Each core's busy percentage over `windowSeconds`; each cluster's cores, current, limit, and maximum MHz. `bigClusters()` and `bigCoresCapped()` answer "are PhotonVision's cores held back" |
| `thermal` | `ThermalZone`, `TripPoint` | Every thermal zone's temperature, with its trip points as the running kernel has them. `firstPassive()` is where throttling starts |
| `photonvision` | `Service` | PhotonVision's service as systemd sees it: active state, sub-state, result, restarts this boot, and when it last started |
| `cameras` | `Cameras`, `ExpectedCamera`, `UsbCamera` | The cameras in the role, each with the USB path PhotonVision's settings match it by and whether something is plugged in there; and every USB camera plugged in, with its port and link speed |
| `journal` | `JournalSummary`, `JournalEntry` | This boot's count of USB, UVC, filesystem, out-of-memory, thermal, and other errors, with the latest three (messages cut to 200 characters) |
| `drive` | `Drive` | The NVMe drive's temperature, wear, unsafe shutdowns (each power cut counts one), power cycles, hours, media errors, and critical warnings; `null` without an NVMe drive |
| `settings` | `SettingsState` | The stamped settings hash against the live one; `matches()` |
| `problems` | text | What the agent couldn't read, each saying what and why (at most 10); empty when it read everything. `shutting down: asked for by the robot` comes first once a shutdown is under way |

A health answer is 3 to 4 KB for an RK3588 with two cameras: more than the design's guess of 2 KB,
mostly the trip points and the journal's latest entries. It's answered at once: asked again within
half a second, it's the same answer; older than that, it's still the last one while a reading
afresh starts in the background (a reading takes about 0.1 s, and a few seconds when the settings
have just changed). Only the very first request waits for the first reading. So a poller's timeout
never trips on a slow reading, and two pollers cost no more than one.

`problems` starts with `shutting down: asked for by the robot` while a shutdown is under way, and
with `shutdown failed: ...; ask again to retry` when powering off failed.

### `JournalPage`

`entries` (oldest first), `cursor`, and `more`. Where the page is, by one of:

| Asked with | The page | Its `cursor` | `more` |
|---|---|---|---|
| nothing | the latest entries | the newest entry's: ask with `cursor=` for the next | false |
| `cursor=C` | the entries just after C | the newest entry's, for the page after | whether more were waiting |
| `before=C` | the entries just before C | the oldest entry's: ask with `before=` for the page before | whether older ones remain |
| `from=boot` | the first entries of this boot | the newest entry's, to go on with `cursor=` | whether more were waiting |

So a viewer's "Older" asks with `before=` the oldest entry it has, and a whole boot's log is
`from=boot`, then `cursor=` until `more` is false. With no entries, the cursor is the one asked
with. `priority` (0-7, or `emerg` to `debug`) gives that priority and worse, and `limit` the page
size (100 unless asked, at most 500). A message longer than 2048 characters is cut. Entries of
every boot the journal keeps are there; each says its boot.

**What's served** is the kernel's messages and those of PhotonVision and the agent (with systemd's
own about them), and nothing else: `unit` may be `kernel`, `photonvision.service`, or
`coprocessor-agent.service` (`AgentApi.JOURNAL_UNITS`), and without one, the page has all three.
The rest of the journal (logins, other services) isn't served.

### `POST /v1/shutdown`

A soft power-off, for a coprocessor kept powered after the robot is switched off (by a battery
pack, say), so it stops cleanly rather than losing power mid-write. The agent logs the request to
the journal, answers 202 at once with `{"shuttingDown": true, "alreadyRequested": false}`, then
runs `systemctl stop photonvision.service` (so PhotonVision saves its settings; up to 2 minutes)
and `systemctl poweroff`, which it runs even if the stop failed. Asking again while it's under way
answers 202 with `alreadyRequested: true` and does nothing more. The agent answers until the
computer goes down; health says `shutting down` meanwhile. If powering off fails, health says
`shutdown failed`, and the next request tries again.

**Who may ask: an address check, not authentication.** A shutdown is taken only from the robot
controller's address, 10.TE.AM.2 by the stamp's team, and refused (403) from anyone else, and on a
computer with no stamp (no name or team), which has no robot to take one from. Each refusal is
logged, at most once in 10 seconds (with a count of the rest). This keeps an accidental click
elsewhere from switching vision off. It doesn't stop anything on the robot's network that sends
from 10.TE.AM.2: a laptop given that address while the robot controller is off could shut a
coprocessor down. That's the same trust the robot's network gives the Driver Station and
PhotonVision's own page (which can restart and wipe a coprocessor without asking). If it ever must
be stronger, a per-team secret, provisioned onto the robot controller and into each image outside
Git, would let the agent authenticate the request itself.

## Settings in Git

PhotonVision keeps all its settings, calibrations included, in one SQLite database,
`photon.sqlite`: a `global` table (network, hardware, field layout, ...) and a `cameras` table
(each camera's configuration, pipelines, and calibrations), with JSON text in their columns. In
Git, they're one file per row, under `coprocessors/<computer>/settings/`:

```
database.json                  {"userVersion": 2}: the database's schema version
global/networkConfig.json      {"contents": {...}}
cameras/<unique name>.json     {"config_json": {...}, "drivermode_json": null, ...}
```

Each file is the row's columns, each column's JSON as JSON (so a diff shows a calibration's
changed member, not a changed line of escaped text), sorted and indented by `Json.pretty`. A key
becomes a file name with everything but letters, digits, `.`, `_`, and `-` written as `%XX`, as
are a leading dot and the names Windows reserves, so every key makes a file on every system; two
keys that differ only in case would be one file on Windows, and are refused. A column whose text
isn't JSON is kept as text, under its name plus `.text`.

Sorted members are safe for PhotonVision to read back: its JSON library (avaje-jsonb) generates
readers that take members in any order, the type member of a polymorphic value included (checked
in avaje-jsonb's generator source). It matters, because PhotonVision's own order isn't stable: some
of its maps are keyed by enums, whose order changes from run to run.

In Java (`com.michaelgrundvig.frc.coprocessor.settings`): `Settings` and `SettingsRow` are the
rows; `SettingsFiles` reads and writes the folder (`read`, `write`, and `render`/`parse` for the
files as text); `SettingsDatabase` reads and writes the database through JDBC's own API, so this
project needs no driver (the agent brings xerial's sqlite-jdbc, and the tests use it too). It reads
every table, with its primary key as the key and every other column as JSON, so a column a new
PhotonVision adds comes along unasked. Writing replaces every row in one transaction, and refuses a
database at another schema version, or without a table or column the settings name.
`SettingsText` is the rows as the database's text, parsed a row at a time: a calibration is about a
megabyte of JSON, many times that parsed, and the agent has 64 MB.

Two things don't round-trip, and PhotonVision's schema has neither: an SQL NULL reads as JSON
`null` and is written back as the text `null` (PhotonVision's columns are NOT NULL), and a column
named like kept text (`x.text`) would be ambiguous, so its table is left out.

**The backup** (`/v1/settings.zip`) holds `coprocessors/<computer>/settings/` and, written last
beside it, `coprocessors/<computer>/settings.sha256`: each file's SHA-256 as `sha256sum` writes
them. A zip that was cut short is missing it, or fails `sha256sum -c
coprocessors/<computer>/settings.sha256` run from the repository's root. Git ignores it.

**The tests' database** is made from PhotonVision's schema (its `DatabaseSchema` migrations,
transcribed in `src/testFixtures/resources/photonvision/`) with example rows written from its
configuration classes' fields; no PhotonVision jar is downloaded. A round trip against a database
the pinned version's own jar made (its `--smoketest` makes an empty one) is a follow-up for CI, once
the jar is archived.

### The settings hash

`SettingsHash.of(settings)` (or `settings.hash()`) is the SHA-256, in lowercase hex, of
`Json.hashable` of `{"tables": {<table>: {<key>: <columns>}}, "userVersion": <n>}`, after the rules
below. So rewriting doesn't change it (members reordered, `70.0` written `70`), files and the
database give the same hash, and a changed setting changes it. The robot's build hashes each
computer's committed settings into the program; each agent hashes the live database; empty means
none are committed.

Some values PhotonVision changes by itself, as it runs. The hash leaves them out, or reads them in
a fixed order (`SettingsHash.RULES`, each with its reason; `SettingsHashTest` pins the list):

| Table, column | Value | Rule | Why |
|---|---|---|---|
| `cameras`, `config_json` | `currentPipelineIndex` | left out | The robot program switches pipelines and driver mode, and PhotonVision saves the choice |
| `cameras`, `config_json` | `streamIndex` | left out | PhotonVision assigns each camera's stream ports as it starts |
| `cameras`, `config_json` | `matchedCameraInfo.dev` | left out, for a USB camera matched by its port | The `/dev/videoN` number the kernel gave the camera this boot |
| `cameras`, `config_json` | `matchedCameraInfo.path` | left out, for a USB camera matched by its port | The `/dev/videoN` device; PhotonVision matches such a camera by the by-path entry in `otherPaths` |
| `cameras`, `config_json` | `matchedCameraInfo.otherPaths` | any order | The camera's other paths, in an order PhotonVision notes can change; which ones (the USB port) still counts |

"A USB camera matched by its port" is one whose `type` is `PVUsbCameraInfo` with a by-path entry
in its `otherPaths`. For any other (a CSI camera, a file, a USB camera without one), the path is
what identifies the camera, so it counts.

**Identical cameras swapped between ports are invisible**, to PhotonVision and to the hash alike:
two cameras of the same model without serial numbers look the same, and PhotonVision follows the
port, so each port's calibration now applies to the other camera. Label cameras and their ports.

**Unverified:** the list comes from reading PhotonVision's source. A bench test on a real board
confirms it: the hash holding steady across reboots, replugs, and pipeline switches. Changing the
list changes every hash, so every image needs stamping again.

## The version lock: `coprocessor/photonvision.lock`

PhotonLib's vendordep (`vendordeps/photonlib.json`) says which PhotonVision the robot program is
built for; the lock says the same version, with what pins an image's inputs:

```json
{
  "version": "dev-v2027.0.0-alpha-2-69-g71416112",
  "jar": {"url": "...", "sha256": "..."},
  "images": {"orangepi-5": {"url": ".../photonvision_opi5.img.xz", "sha256": "..."}, ...}
}
```

`version` must equal the vendordep's. `jar` is the PhotonVision jar for 64-bit ARM Linux, and
`images` each board's base image: PhotonVision's own image for that board, from the
[photon-image-modifier](https://github.com/PhotonVision/photon-image-modifier) release the pinned
PhotonVision version builds on, whose file name it keeps. A value not known yet is a placeholder,
text starting `PLACEHOLDER`: the robot's build allows it and names it, and the image build refuses
it. The template's lock has the base images of release v2027.2.3, with the SHA-256 digests the
release publishes, and placeholders for the jar: PhotonVision's rolling development release no
longer holds this version's, so it has to be archived (built from its commit, if need be) before
images can be built. The lock is JSON, which YAML tools read too (the image scripts use yq).

**Every build checks it:** `./gradlew checkPhotonVisionLock` (part of `check`, so of `build`,
`ci`, and `deploy`) fails when the lock's version isn't the vendordep's, saying both. So when
PhotonLib is updated, the lock moves with it, in the same commit, with the new jar's and images'
addresses and checksums (or placeholders until they're known).

## The compiled table

Every build compiles the table into the robot program as `/coprocessor/table.json` (the
`coprocessorTable` task, from `gradle/coprocessors.gradle`; a generated resource, not source), with
what the robot checks each coprocessor against: PhotonLib's PhotonVision version, the recipe hash,
and each computer's committed settings hash (empty for a computer with none). A table with a
problem, or settings that can't be read, fail the build there.

`CompiledTable.load()` reads it. `compare(computer, stamp)` says how a coprocessor's stamp differs
from this build, each difference a `Mismatch`: what differs, a sentence naming both values, and
how much it matters (`Severity`):

| Differs | Severity | Meaning |
|---|---|---|
| name, team, address, or PhotonVision version | `ERROR` | The wrong computer, or the wrong PhotonVision: a deploy fails, and the robot raises a high alert |
| recipe hash | `WARNING` | The image was built from another recipe (the image's scripts, the agent, the lock): flash the current release when convenient |
| this build has no recipe hash | `UNKNOWN` | The image's recipe can't be judged (built without Git, from a downloaded copy, or in a folder of a larger repository): not a mismatch, said as unknown |

`mismatches(computer, stamp)` is the sentences of the errors and warnings alone. The robot's card
and its deploy check go by these, with no rules of their own. The settings hash isn't compared:
settings change while someone calibrates, which isn't a mismatch.

**The recipe hash** says how the common image is built, and changes only when what goes onto a
board does: never for a robot-side change (a GradleRIO or WPILib update, the Gradle wrapper, the
robot program, documentation, tests), which would otherwise fail every deploy until a new image
nobody needs was flashed. It's the SHA-256, in lowercase hex, of:

1. the lines `git -c core.quotePath=true ls-tree -r --full-tree HEAD --` prints for
   `coprocessor/image`, `coprocessor/agent`, `coprocessor/common`, `coprocessor/photonvision.lock`,
   and `gradle/quality.gradle` (the one build file the agent's build applies), leaving out
   documentation (`*.md`), tests (`coprocessor/image/test/`, `src/test/`, `src/testFixtures/`), and
   coverage floors; then
2. a line `runtime <group>:<name>:<version>` for each library on the agent's runtime classpath,
   sorted, so a new sqlite-jdbc is a new image (and a new WPILib, which the agent doesn't run,
   isn't).

Committed files only, so it's the same on every machine and system; uncommitted changes don't
count, since an image is built from a commit. Without Git to ask (no git on the PATH, a downloaded
copy, no commit yet, or the project in a folder of a larger repository) there's no hash: the
compiled table's is empty, the build says so, and the robot reports each coprocessor's recipe as
unknown rather than wrong.

One implementation computes it, in the build: `./gradlew coprocessorRecipeHash` prints it (only it,
with `-q`) and writes it to `build/coprocessor/recipe-hash.txt`, and the compiled table uses the
same code. The image workflow runs that task, so the robot and the images never disagree on it.
The agent's and the shared code's builds are self-contained: the robot's build files configure no
other project, and the agent's build applies only files the hash covers (`RecipeBoundaryTest`
checks both), so nothing the hash leaves out can change the agent's jar. `CoprocessorBuild` runs
these tasks with the code the robot and the agents run.

## Tests

`./gradlew :coprocessor-common:test :coprocessor-agent:test` runs them; `./gradlew ci` runs them
with every other check. They need nothing of the computer they run on: the agent's read a fixture
tree, and PhotonVision's database is made from its schema (`src/testFixtures` in
`coprocessor/common`, which the agent's tests use too).

## JSON, without a library

The robot program, the agent, and the build all read and write JSON through one small class,
`com.michaelgrundvig.frc.coprocessor.json.Json`, with nothing but the JDK. Pitwall uses avaje-jsonb,
which WPILib brings the robot program, but the coprocessors don't run WPILib, and settings hashes
must be computed identically by the robot's build and by each agent for years: a library's output
can change under a version bump, and a hash with it. So:

- A parsed number keeps the text it was written as, so a value read and written again is unchanged
  digit for digit.
- `Json.compact` writes members in their order with no spaces: what the agent sends.
- `Json.pretty` writes members sorted by name, two-space indents, and a newline at the end, with a
  container of plain values short enough (80 characters) on one line: the form files take in Git.
- `Json.hashable` writes sorted, with no spaces and every number in one form (`1`, `1.0`, and
  `1e0` alike), for hashes.

Names sort by UTF-16 code units, as RFC 8785 does; strings escape only what JSON requires.

## The agent

`coprocessor/agent/` (Gradle `:coprocessor-agent`) is the health agent each coprocessor runs:
plain Java on the JDK's own web server, with xerial's sqlite-jdbc to read PhotonVision's database.

**What it reads**, each from the computer itself:

| Part | From |
|---|---|
| The stamp | `/etc/coprocessor/stamp.json`; the boot from `/proc/sys/kernel/random/boot_id`; the MAC from the first wired interface in `/sys/class/net` (one with a device behind it, preferring one that's up) |
| The boot | `/proc/uptime`; the root filesystem from `/proc/self/mountinfo` and its device from `/sys/dev/block/<major:minor>/uevent`; whether the boot before ended cleanly from its journal (`journalctl -b -1`: journald stopping, or systemd reaching its power-off, reboot, or shutdown target; unverified on these boards); the bootloader from `/run/coprocessor/spi-uboot-version` |
| The CPU | `/proc/stat`, between answers; each cluster from `/sys/devices/system/cpu/cpufreq/policy*` |
| Heat | `/sys/class/thermal/thermal_zone*`, with each zone's trip points |
| PhotonVision | `systemctl show photonvision.service` |
| Cameras | `/dev/v4l/by-path/*-video-index0`, each through its video device to its USB device in `/sys` (port, speed, IDs, name); where each camera of the role is expected, from PhotonVision's settings, as PhotonVision matches it (its by-path entry) |
| The journal | `journalctl -o json`: the kernel's messages and priority 3 or worse, counted a bounded piece at a time, picking up by cursor |
| The drive | `/run/coprocessor/nvme-smart-log.json` and `nvme-id-ctrl.json`, nvme-cli's reports, which a root job on the image writes every minute (the agent can't read the drive itself). nvme-cli's JSON has changed between versions, so a temperature reads as kelvins or with its unit, and a few names have two spellings (unverified against the image's nvme-cli) |
| Settings | PhotonVision's database, `/opt/photonvision/photonvision_config/photon.sqlite`, opened read-only, in one transaction so PhotonVision's writes wait milliseconds at most. Only the hash and where each camera is expected are kept, worked out again when the file (or its write-ahead log) changes; the settings themselves are read again to be sent, and dropped after. A file that couldn't be read isn't read again until it changes |

Every path and command goes through one `Host`, so the tests run against a fixture tree of an
RK3588 (`src/test/resources/rk3588`: eight cores in three clusters, seven thermal zones, two
cameras, an NVMe drive) with canned command output, on any system. Colons in sysfs names are
written `%3A` there, since Windows can't check out a file named with one.

**Bounds** (`Limits`, each tested): a command has 2 seconds, and is stopped once it has printed
what's needed, at most 4 MB (a journal page); a file is read to 256 KB (a longer one is cut short,
which a JSON file won't survive); a JSON answer is at most 4 MB. The settings are bounded as
they're read, not after: a database file (with its log) of at most 32 MB, at most 16 MB of
settings text in all, and at most 2 MB in any one value, which SQLite itself enforces too
(`SQLITE_LIMIT_LENGTH`, at twice that, so a hostile database can't make the agent hold a huge value
even once). Past a bound, the settings are refused, saying so in health's problems.

Those numbers are measured. PhotonVision's settings parse to about nine times their text, so one
value parses within the 64 MB heap beside the rest of the settings held while they're sent: under
`-Xmx64m`, a database of 16 MB of text in values of 2 MB (a calibration is about 1 MB) hashes in
about 3 s, sends as JSON in 3 s and as a zip in 1.5 s, with health polled throughout and answered
at once, and 130 MB resident at the peak. Values of 4 MB ran out of heap. The unit caps the agent
at 200 MB (`MemoryMax`), and out of memory, the agent exits and is restarted
(`-XX:+ExitOnOutOfMemoryError`) rather than limping on.

**Cost**, measured on a desktop: up and answering in about 160 ms; 75 MB resident after a minute
of health and full journal pages twice a second; a health answer in about 2 ms.

**A hostile database** can't run anything: the schema's functions are distrusted
(`trusted_schema` off), only plain tables are read (not views, virtual or shadow tables), and a
table that isn't plainly keyed is left out rather than failing the rest. Whether PhotonVision's
database is ever in write-ahead-log mode is a bench check: the agent notices a save either way.

### Built and installed

`./gradlew :coprocessor-agent:build` makes **`coprocessor/agent/build/libs/coprocessor-agent.jar`**,
run with `java -jar`: the agent and everything it needs, with sqlite-jdbc's native libraries for
64-bit ARM and x86 Linux only (1.6 MB). The image installs it as
`/opt/coprocessor/coprocessor-agent.jar`, and **`coprocessor/agent/coprocessor-agent.service`** as
`/etc/systemd/system/coprocessor-agent.service`, enabled. The unit is also the launcher: its
`ExecStart` carries the JVM's settings (a 64 MB heap, the serial collector, the quick compiler
only, exit when out of memory) and runs `/usr/bin/java`, where PhotonVision's image installs
OpenJDK 25. It runs on the small cores (`AllowedCPUs=0-3`; PhotonVision has 4-7) at `Nice=10`, as
an unprivileged user with no capabilities, read-only everywhere but `/run/coprocessor-agent`
(where sqlite-jdbc unpacks its library), able to reach only the robot network, link-local
addresses, and itself (`IPAddressAllow`), with systemd's other sandboxing on (namespaces, SUID,
realtime, personality, clock, hostname, kernel logs, devices, `UMask=0077`, at most 64 tasks).
Not `MemoryDenyWriteExecute`, since Java compiles code as it runs, nor `ProcSubset=pid`, since it
reads `/proc/stat` and `/proc/uptime`.

`java -jar coprocessor-agent.jar [serve] [--port=N] [--bind=ADDRESS] [--database=PATH]
[--unit=NAME]` serves on the stamp file's `agentPort` (5808 when it has none) unless `--port`
says. The stamp file is the `Stamp` JSON without `bootId` and `mac`, plus `agentPort`; the image's
stamping writes it.

**What the image provides:**

- **The user** `coprocessor-agent`, a system user (no login, no home) in the `systemd-journal`
  group, so it can read the journal.
- **`/run/coprocessor/`**, world-readable, written by root every minute: `nvme-smart-log.json`
  (`nvme smart-log --output-format=json /dev/nvme0`), `nvme-id-ctrl.json` (`nvme id-ctrl
  --output-format=json /dev/nvme0`), and `spi-uboot-version` (one line). A file that's missing
  reads as unknown.
- **Read access** to PhotonVision's folder and database (its files are 644, its folders 755).
- **A polkit rule** letting the agent's user stop PhotonVision's unit and power off, and refusing
  it everything else, in `/etc/polkit-1/rules.d/` (the image's `coprocessor-agent.rules`). The
  actions are systemd's `org.freedesktop.systemd1.manage-units`, with the unit and verb it passes,
  and logind's `org.freedesktop.login1.power-off`, and `power-off-multiple-sessions` for when
  someone is logged in over SSH:

  ```js
  polkit.addRule(function (action, subject) {
    if (subject.user !== "coprocessor-agent") return polkit.Result.NOT_HANDLED;
    if (action.id === "org.freedesktop.login1.power-off" ||
        action.id === "org.freedesktop.login1.power-off-multiple-sessions") return polkit.Result.YES;
    if (action.id === "org.freedesktop.systemd1.manage-units" &&
        action.lookup("unit") === "photonvision.service" &&
        action.lookup("verb") === "stop") return polkit.Result.YES;
    return polkit.Result.NO;
  });
  ```

  `org.freedesktop.login1.power-off-ignore-inhibit` isn't granted: if something holds a shutdown
  inhibitor, the power-off fails (and health says so). Whether anything on the image takes one is a
  bench check.

**For stamping:** `java -jar coprocessor-agent.jar settings-db ROWS_DIR EMPTY_DB OUT_DB` builds
`OUT_DB` from a copy of `EMPTY_DB` (the empty database the pinned PhotonVision made in its smoke
test) and the committed settings in `ROWS_DIR`, reads it back to check nothing was lost, and prints
the settings' hash, the one the stamp carries. It fails, saying why, on settings that aren't there,
a database at another schema version, or anything it can't write.
