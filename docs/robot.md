# The coprocessors, from the robot

The robot program's side of Spotter is its **manager**: `:spotter-manager`, published as
`com.michaelgrundvig.frc:spotter-manager` in Spotter's Maven repository
(`https://mikegrundvig.github.io/frc-spotter/maven`). It's plain Java 17 with nothing but
QuickBuffers' runtime, which WPILib already ships, with the protocol both sides speak inside it: no WPILib,
no AdvantageKit. Robot code wires in what's robot-specific.

It keeps each coprocessor agent's stream open on a thread of its own, judges each value against its
limits, and gives the robot loop each board's state, and the current alerts, without ever waiting on
the network. Robot code can page a board's logs, run its actions, and push it the team's packs; the
manager pushes them itself, off the field, to a board whose packs differ. `docs/agent.md` is the
agent's side, and protocol 2 in full.

## Wiring it in

```java
// Facts about the robot, as constants: each board, and the team's packs it's to have.
static final BoardDefinition VISION_FRONT =
    new BoardDefinition("vision-front", "debian", "detector");
static final BoardDefinition VISION_BACK =
    new BoardDefinition("vision-back", "debian", "raspberry-pi");

Manager spotter =
    new Manager(
        new RobotSignals(RobotState::isEnabled, RobotState::isFMSAttached, RobotController::getTime),
        List.of(VISION_FRONT, VISION_BACK),
        Settings.DEFAULTS.withPacks(Filesystem.getDeployDirectory().toPath().resolve("spotter-packs")),
        recorder);
spotter.start();
Board front = spotter.board("vision-front");      // once, and kept: what the robot loop reads

// robotPeriodic(), first:
spotter.update();
```

- **Each board's definition** (`BoardDefinition`): its agent's address (`vision-front`,
  `10.12.34.11`, or `vision-front:5808`; port 5808 unless one's given), and the names of the team's
  packs it's to have (*Each board's packs*). It holds no state, so it's safe as a constant, and
  any number of managers can take it. The manager discovers nothing: it watches the boards it's
  given, and makes a `Board` for each, which robot code reads.
- **The robot** (`RobotSignals`): whether it's enabled, whether the field is attached, and its clock in
  nanoseconds, each a supplier the manager reads when it checks. The manager has no clock of its
  own: when a board was last heard from, whether it's missing, and when each value was sent are all
  on the robot's clock, a simulation's or a replay's as much as the robot's. (The calls above are
  WPILib 2027's.)
- **`update()`, once each loop, before reading anything.** It takes each board's state as its thread
  last published it, judges on the robot's clock which boards are missing, and updates the alerts.
  It never waits, and in steady state it allocates nothing. Everything it updates (each `Board`, its
  `Value`s, the alerts) belongs to the robot loop's thread: read them there.
- **`close()`** stops its threads.

## Settings

Each has a default (`Settings.DEFAULTS`); robot code changes those it needs with `with…`.

| Setting | Default | What |
|---|---|---|
| `heartbeat` | 250 ms | How often each agent says it's there when nothing else happens; asked for on connect, kept by the agent between 50 ms and 5 s |
| `missing` | 1 s | How long a board may be silent before it's missing, on the robot's clock. A connection silent that long is also dropped and made again |
| `backoff` | 5 s | The longest wait between attempts to reach a board: 250 ms after a failure, doubling up to this |
| `limits` | none | Robot code's limits for fields, by id, in place of their packs' (*Limits*) |
| `refuseWhileEnabled` | on | Refuse an action while the robot is enabled or the field is attached, unless the action says `whileEnabled: true` |
| `pushAutomatically` | on | Push the team's packs to a board whose packs differ, while the robot is disabled and off the field |
| `packs` | none | The team's packs: a folder of pack folders, from which each board gets those it names |
| `key` | `/home/systemcore/spotter.key` | The private key that signs writes, for boards that require signatures (*Signing*) |
| `publicKey` | `spotter.pub` beside the key | Its public key, whose id each signature names |

## Each board

The manager makes one `Board` for each definition, and each `update()` brings it up to date.
`spotter.board("vision-front")` finds one by its address, and `spotter.boards()` lists them in the
order given: look a board up once, and keep it. `board.definition()` is the definition it was
made from.

- **`connection()`:** `CONNECTING` (not heard from yet, and not for as long as the missing
  threshold), `CONNECTED`, `MISSING` (silent past the threshold), or `OTHER_PROTOCOL`. `why()` says
  why it isn't reached: the last attempt's failure, its silence, or the other protocol.
- **`name()`:** its hostname once it's described itself; its address until then.
- **`problems()`:** what its agent ignored, and why, as a list of text: a pack that didn't load
  (each mistake with its file and line), a program anyone but root could change, an `agent.json`
  that can't be read. While it has any, its alerts include a warning saying how many, and the
  first, so a pack that didn't load is never silent on the robot.
- **`description()`:** what it last described: its identity, packs, values', logs' and actions'
  declarations, and its problems, as QuickBuffers' `Description`. A few lines of robot code
  against it can check anything more ("this board should have the detector pack at 0.2"); the
  manager validates nothing beyond limits and missing boards.
- **`value(id)`, `values()`:** each value, by `pack.collector.field`: its number, text, flag or
  status, or why it's unavailable; its `level()` and `reason()` (*Limits*); and `nanos()`, when the
  agent last sent it, mapped onto the robot's clock. `value(id)` is the same `Value` for an id
  every time, and every `update()` brings it up to date, so look it up once and keep it:

  ```java
  Value fps = front.value("detector.health.fps");   // once, as the robot starts
  // each loop, after spotter.update():
  boolean detecting = fps.level() == Level.OK;
  ```

  It's never null: while the board has no value by that id (it hasn't described itself yet, or its
  packs don't declare it), it's `UNAVAILABLE`, its `number()` NaN, and its reason says so
  (`vision-front has no value detector.health.fps`). Decide on `level()`, not on `number()`. A
  value the board stops declaring goes unavailable the same way, and comes back if it does.
  The catalog's packs' values and logs are named in `CatalogPacks`, so robot code reads them by a
  name Spotter owns, never a string of its own: `board.value(CatalogPacks.Debian.CPU_BUSIEST)`.
- **`changes()`:** the change notice, a count that rises whenever the description or a value
  changes; a value's `changed()` is the count when it last did. A loop that keeps the count it last
  saw knows what changed.
- **`protocol()`:** the version it answered with. Another minor version works as usual and shows
  here; another major version is `OTHER_PROTOCOL`, and its values and actions aren't used.

**Threads.** Each board's stream is read on a thread of the manager's, which publishes the board's
state into one of three buffers it swaps with the loop's, with no lock: `update()` takes the newest,
whole, so every read within a loop agrees. Requests (a log page, an action, a cancel, a push) go on
each board's own request threads, never the loop's: three at once and eight waiting at most, one
more refused at once ("too many requests to vision-front at once"), each finished within 30 s
however slowly the board answers. Closing the manager finishes every request, waiting or under
way, before `close()` returns: its future fails, saying the manager is closed.

**What a board may send.** Whatever a board sends, by fault or on purpose, the robot allocates
within what it sent, and never loses the board for good:

- each event is read whole, at most 1 MiB (`Protocol.MAX_EVENT`), and checked before it's parsed
  (`WireCheck`: every string within what holds it, at most 16,384 list elements); one past that,
  or malformed, drops the connection, which is made again;
- a description of more than 2,048 values, 128 actions or 128 problems isn't used, and says so (the
  agent loads no more, so only a broken or hostile board does);
- a board's values raise at most 32 alerts;
- an answer to a request is at most 8 MiB, checked the same way;
- whatever a board's thread meets, an `Error` included, it connects again after its backoff.

**Garbage.** Decoding the stream into the board's state, publishing it, and `update()` allocate
nothing in steady state: one event, one source and the buffers are reused, and text is decoded only
when it changes (measured with the JVM's own per-thread count, over 100,000 messages). The JDK's HTTP
client, which reads the stream's chunks, allocates about 48 bytes per event of its own.

## Limits

A value's limits are its pack's (`warn` and `fail`: `above`, `below`, `equals`, `notEquals`,
`missing`; within a level any that matches triggers it, `fail` before `warn`), or robot code's:

```java
Settings.DEFAULTS
    .withLimits("vision.health.fps", new Limits(Limits.below(30), Limits.below(10)))
    .withLimits("vision.health.mode", new Limits(Limits.equalTo("calibrating"), Limits.missing()))
```

`Limits.above`, `below`, `equalTo`, `notEqualTo` (a number, text or a boolean), `missing()` and
`none()` each make one level's comparison; `.setMissing(true)` adds missing to any of them. An
override replaces both of the field's levels. Ids are a value's `pack.collector.field`, or an
action's response field's `pack.action.field`. An override that matches nothing on any board is a
warning, once every board has described itself (or is missing, so a board that's away doesn't hold
the check back), so a typo doesn't quietly do nothing.

A value's `level()` is `OK`, `WARNING`, `FAILING`, or `UNAVAILABLE` (no value, and no `missing` rule:
no alert). A `status` is its script's own verdict. Its `reason()` is in its pack's words: the
comparison that matched (`below 30 fps`), a status's message, or why it has no value.

## Alerts

`spotter.alerts()` is the current set, as data (`Alert`: a key, a level, the board, and text), the
same list until one changes. Robot code maps them onto its own, WPILib's `Alert` say: `FAILING` as
`Level.HIGH`, `WARNING` as `Level.MEDIUM`.

**The key** says what an alert is about, and stays the same while it stands, though its text (a
reading, a status's message) and its level may change. A board's start with its address as robot
code gave it; robot code's own setup's with `setup/`.

| Alert | Key | Level | Says |
|---|---|---|---|
| A value at warning or failing | `vision-front/value/vision.health.fps` | its level | `vision-front: Frames per second below 30 fps` |
| A missing board | `vision-front/missing` | failing | `vision-front is missing: connection refused: is frc-spotter running on it?` (or `its name doesn't resolve`, `no answer in time: is it on the robot's network?`, or, when robot code never called `start()`, `the manager isn't started`) |
| A board on another major version of the protocol | `vision-front/protocol` | failing | `vision-front: it speaks Spotter protocol 3.0, the robot 2.0` |
| A board that describes more than the manager takes | `vision-front/described` | failing | `vision-front describes 2049 values (at most 2048), more than the manager takes: its values and actions aren't used` |
| More than 32 values at warning or failing on one board | `vision-front/values/more` | the worst of the rest | the failing ones first, then `vision-front: 9 more values at warning or failing` |
| Packs that differ and won't be pushed now | `vision-front/packs` | warning | `vision-front's packs differ from the robot's: they'll be pushed off the field` |
| A board that reports problems | `vision-front/problems` | warning | `vision-front reports 2 problems, the first: /var/lib/frc-spotter/packs/detector/pack.yaml:8: unknown key "evry" in a collector; ...` |
| Packs that can't be read, or no such folder | `setup/packs` | warning | `the team's Spotter packs can't be read (/home/systemcore/deploy/spotter-packs isn't a folder): nothing is pushed` |
| A board's pack that isn't in the folder | `setup/packs/detector/pack/detectr` | warning | `detector is to have the pack detectr, which isn't among the team's packs (/home/systemcore/deploy/spotter-packs): a pack is a folder there with a pack.yaml` |
| A board's packs, with no packs folder | `setup/packs/detector/no-folder` | warning | `detector is to have the packs debian, detector, but the manager has no packs folder: nothing is pushed` |
| A board's packs past what a push may carry | `setup/packs/vision-front/too-large` | warning | `the team's Spotter packs for vision-front are 5000 files, more than the 4096 a push may carry: nothing is pushed to it` |
| No key pair, while a board requires signatures | `setup/key` | warning | `no Spotter key pair on this controller (/home/systemcore/spotter.key, /home/systemcore/spotter.pub): boards that require signatures will refuse its actions and pushes` |
| A limit override that matches nothing | `setup/limits/vision.health.fsp` | warning | `Spotter's limits for vision.health.fsp match no value or response field on any board` |

The `setup/` ones are about robot code's own setup, and name no board. A missing board's last
values stay readable, for what they're worth; its connection says it's missing, and its one alert
is that.

**Onto WPILib's alerts**, one per key: WPILib's `Alert` has a fixed level, so a level that changes
makes a new one.

```java
private final Map<String, org.wpilib.util.Alert> shown = new HashMap<>();
private List<Alert> mirrored = List.of();

/** After spotter.update(), each loop. */
void mirror() {
  if (spotter.alerts() == mirrored) {
    return;                                           // the same list until one changes
  }
  mirrored = spotter.alerts();
  Set<String> now = new HashSet<>();
  for (Alert each : mirrored) {
    now.add(each.key());
    org.wpilib.util.Alert.Level level =
        each.level() == Level.FAILING
            ? org.wpilib.util.Alert.Level.HIGH
            : org.wpilib.util.Alert.Level.MEDIUM;
    org.wpilib.util.Alert alert = shown.get(each.key());
    if (alert != null && alert.getLevel() != level) {
      alert.close();
      alert = null;
    }
    if (alert == null) {
      alert = new org.wpilib.util.Alert("Coprocessors", each.key(), each.text(), level);
      shown.put(each.key(), alert);
    }
    alert.setText(each.text());
    alert.set(true);
  }
  shown.entrySet().removeIf(entry -> !now.contains(entry.getKey()) && close(entry.getValue()));
}

private static boolean close(org.wpilib.util.Alert alert) {
  alert.close();
  return true;
}
```

## Logging

The manager writes no log. A `Recorder` is handed, on the manager's threads:

- each `Description` and `Values` as it's received (`described`, `values`), as QuickBuffers'
  messages, so a replay sees exactly what the robot saw. WPILib's `DataLog` takes each one's bytes
  as a raw entry (`RawLogEntry`), or the message itself through a small `Protobuf` adapter of robot
  code's; its calls are thread-safe, so a recorder can make them. AdvantageKit's `Logger` is the
  robot loop's alone: log what it needs there, from each `Board`, after `update()`;
- each run's events (`run`): started, a line logged, finished;
- each push a board took (`pushed`), with the packs' hash and whether robot code forced it.

A message is reused once the call returns: log it, or copy it, before then, and return quickly.
What a recorder throws is ignored, so a logging fault never takes a board away.

## Logs

```java
board.log("debian.journal", LogQuery.latest(100).atLeast("warning"))
```

returns a `CompletableFuture<Spotter.LogPage>`, fetched on the board's request threads: check it in
a later loop (`isDone()`), and never wait on it in the loop. A board that isn't connected is asked
nothing: the future fails at once, saying why. Page back with
`LogQuery.before(page.getBefore(), n)` and on with `LogQuery.after(page.getAfter(), n)`.

## Actions

```java
Run run = board.run("detector.recalibrate");        // or run(id, "text"), run(id, bytes)
```

starts an action on the manager's threads and returns a `Run` at once, which the manager keeps up to
date from the board's stream. Every call on it is safe from any thread and never waits.

- **`state()`:** `STARTING`, `RUNNING`, `FINISHED`, or `REFUSED`; `done()`, and `whenDone()` for
  code off the loop.
- **`log()`:** its lines so far (its standard error, line by line), as they come.
- **`outcome()`, `why()`:** how it finished (completed, timed out, cancelled, lost...), in words.
- **`response()`:** each response field its action declares, judged by its limits like a value (a
  `Value` with its level and reason), once it's finished. These are the run's own, never reused.
- **`level()`, `reason()`:** its verdict, as the design judges a run: `FAILING` when it was refused
  or didn't complete (timed out, cancelled, lost, couldn't start), whatever its pack says; once
  completed, the worst level among its response's fields (`exit isn't 0`). `UNAVAILABLE` until it's
  done. So `run.level() == Level.FAILING` is the whole check, a timeout and a refusal included.
- **`cancel()`:** stops it, politely then firmly. Asked before the board has started it, it's sent
  as soon as it has.
- **`file(name)`:** a `file` field of its response, as its bytes.
- **`changes()`:** a count that rises as it starts, logs, and finishes.

**Refused at once**, without asking the board, when the board isn't connected or has no such action;
and, unless `refuseWhileEnabled` is off, while the robot is enabled or the field is attached, unless
the action says `whileEnabled: true`. The agent can't see the match, so this is the manager's job.
A board's own refusal (another run of the action going, an unsigned write) is `REFUSED` too, with
the board's reason.

**A result while the board was away** (the agent restarted, the network dropped) comes when it's
back: the agent keeps finished runs until the board reboots and lists them on every connect, and the
manager fetches the run's log again. A run that was going when the agent stopped comes back `lost`.

## Pushing the team's packs

Given the team's packs (`Settings.withPacks(folder)`: a folder of pack folders, in the deploy
directory so they live in Git with the robot code), the manager makes sure every board has the
packs it names, so a spare board just works.

**Each board's packs.** A robot's boards aren't all alike: a detector board and a Raspberry Pi
want different packs. Each `BoardDefinition` names exactly the packs it's to have, from the
one folder, and its board gets those and nothing else:

```java
// spotter-packs/: debian/, detector/, raspberry-pi/, each a folder with its pack.yaml
new BoardDefinition("vision-front", "debian", "detector")
new BoardDefinition("vision-back", "debian", "raspberry-pi")
new BoardDefinition("spare")            // none: packs pushed to it before are taken away
```

What a board gets is what its own line says, whatever other boards name. A board that names none
is pushed none, so a board moved off a pack loses it; the packs installed on it are its own either
way. Only pack folders are pushed (a folder with a `pack.yaml` at its top), so a `README.md` in
the packs folder goes nowhere, and neither does a link in it: it's never followed. Each board gets its own bundle and its own hash, so each is pushed
only when its own set changes. A name that isn't a pack in the folder is a warning, and so are
packs named with no packs folder, a folder that isn't there, and a board's set past what a push
may carry (4,096 files, 16 MiB): nothing is pushed to that board.

1. It reads them as it's made into the bundle it pushes: a `PackBundle` (`spotter.proto`), each
   file's path in the folder, whether it's executable, and its bytes, exactly what the pack hash
   covers. A file is executable when any execute bit is set, or when it starts with `#!`: the
   robot's deploy may copy files without their execute bits (unconfirmed on Systemcore: open
   question Q51), and a script that names its interpreter still runs once pushed. A program
   without `#!` (a binary, or a script that relies on a default shell) needs its bit, or its
   interpreter named in the pack: `run: [sh, ./health]`. Its hash is `PackHash`'s, as the agent
   hashes what it writes.
2. Each board describes the hash of its pushed packs. One whose packs match runs as it is: nothing
   is pushed, and nothing restarts.
3. To a board whose packs differ, the manager pushes the bundle over its stream (a push is signed
   over the stream's challenge), in protobuf. The agent writes its files (`755` or `644`), swaps
   them in, and exits; systemd starts it again; the manager reconnects, and the board describes
   the hash it now has, which matches. The agent exits once the packs are in place, even if the
   robot went before its answer was sent. A board that took a push and hasn't restarted within five
   missing thresholds (5 s by default) has failed it: a warning says so, and it's pushed once more.
4. **Never automatically on the field:** only while the robot is disabled and the field isn't
   attached. Otherwise the board runs what it has, and a warning says its packs will be pushed off
   the field; they are, once it's off.
5. **No loops.** An automatic push is tried once for each set of packs the board has: one that
   fails (a full disk, a refusal) or doesn't take is a warning, and isn't tried again until the
   board's packs change, or robot code forces one. The one exception is a push the board took and
   never restarted for (step 3), which is pushed once more.

**A forced push**, `spotter.push(board)`, pushes now, field or no field: it's robot code's call. It
returns a `CompletableFuture` that completes once the board has taken the packs.

**A board takes pushes only when its `agent.json` names its team** (`{"team": 1234}`) or its
controller: one that only works its controller out from its own address refuses them (and runs no
pack's action for the robot, only power-off and reboot), since on a school's or a home's 10.x
network, .2 could be anyone. Its description says it refuses pushes, and a mismatch is a warning;
the board's 403 says why.

**A board can refuse pushes** (`{"acceptPushes": false}` in its `agent.json`): its description says
so, and a mismatch is a warning. A forced push to it fails, with its reason.

**Small packs only.** A push is for configuration and scripts: a bundle is at most 16 MiB and 4,096
files, sent as it is (no compression), within the agent's size cap. A pack with large data, such as
one shipping a model file, is installed with the board's image or by whatever installs Spotter,
never pushed from the robot.

A push restarts the agent, so the board may show missing for a second or two. That's at startup,
once per board, before anything depends on the board's values.

## Signing

Off unless a board requires it: a board that lists trusted keys in its `agent.json` takes a write
(an action, a cancel, a push) only when it's signed by one of them (`docs/agent.md`, *Signed
writes*). Reading stays open.

- **The key pair** is made once, with openssl, on the robot controller:

  ```sh
  openssl genpkey -algorithm ed25519 -out spotter.key
  openssl pkey -in spotter.key -pubout -out spotter.pub
  ```

  `spotter.key` is the private key (PKCS#8 in PEM), `spotter.pub` its public key (X.509 in PEM).
  The manager reads both with Java's own key specs, and derives nothing: the key id each signature
  names comes from `spotter.pub`.
- **Where they live:** on the robot controller, outside the deploy folder and out of Git:
  `/home/systemcore/spotter.key` unless `Settings.withKey` says otherwise, and `spotter.pub`
  beside it unless `Settings.withPublicKey` does. A replacement controller needs both.
- **What each board trusts:** its `agent.json` lists the public key in `trustedKeys`, as the base64
  of its X.509 encoding, which is the text between `spotter.pub`'s PEM lines on one line
  (`MCowBQYDK2VwAyEA…`). It's safe anywhere.
- **The manager signs every write** when it has the pair: over the board's current stream
  connection's challenge, with a counter that rises with each write, one write at a time per board.
  A board that doesn't require signatures ignores them.
- **Without the pair,** or half of it, reading works; writes to a board that requires signatures are
  refused (`REFUSED`, with the board's reason), and a warning says there's no key pair. A pair that
  can't be read, or whose public key isn't the private key's, is a warning too.

## Tests

`manager/src/test` runs the real agent in the test's own process, on a local port, with packs the
tests write (`LocalAgent`, the agent's test fixture): connecting and the stream, values and limits,
overrides, missing boards and reconnects, the protocol's version, alerts, the recorder, actions and
logs, pushes, and signing. The zero-allocation tests count each thread's bytes with `ThreadMXBean`.
Keys are made as the tests run; none is ever committed.

The agent's container tests' `ManagerContainerTest` (`agent/src/containerTest`) runs the manager against the agent installed from its package in
a container under systemd, on a board that requires signatures: a signed action and a cancel; a
board paused with `SIGSTOP`, missing after a second and back; a board answering protocol 3.0; and
the team's packs pushed, signed, the agent restarted by systemd with them.

0.3's client (`client/`, `com.michaelgrundvig.frc:spotter-client`), which polled protocol 1, is
gone.
