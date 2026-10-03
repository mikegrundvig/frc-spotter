# The coprocessors, from the robot

The robot program's side of Spotter is its **manager**: `:spotter-manager`, published as
`com.michaelgrundvig.frc:spotter-manager` in Spotter's Maven repository
(`https://mikegrundvig.github.io/frc-spotter/maven`). It's plain Java 17 with nothing but
`:spotter-protocol` (and QuickBuffers' runtime, which WPILib already ships): no WPILib,
no AdvantageKit. Robot code wires in what's robot-specific.

It keeps each coprocessor agent's stream open on a thread of its own, judges each value against its
limits, and gives the robot loop each board's state, and the current alerts, without ever waiting on
the network. Robot code can page a board's logs, run its actions, and push it the team's packs; the
manager pushes them itself, off the field, to a board whose packs differ. `docs/agent.md` is the
agent's side, and protocol 2 in full.

## Wiring it in

```java
Manager spotter =
    new Manager(
        new Robot(RobotState::isEnabled, RobotState::isFMSAttached, RobotController::getTime),
        List.of("10.26.11.11", "10.26.11.12"),
        Settings.DEFAULTS.withPacks(Filesystem.getDeployDirectory().toPath().resolve("spotter-packs")),
        recorder);
spotter.start();

// robotPeriodic(), first:
spotter.update();
```

- **The agents' addresses:** `10.26.11.11`, or `vision-front:5808`; port 5808 unless one's given.
  The manager discovers nothing.
- **The robot** (`Robot`): whether it's enabled, whether the field is attached, and its clock in
  nanoseconds, each a supplier the manager reads when it checks. The manager has no clock of its
  own: when a board was last heard from, whether it's missing, and when each value was sent are all
  on the robot's clock, a simulation's or a replay's as much as the robot's. (The calls above are
  WPILib 2027's.)
- **`update()`, once each loop, before reading anything.** It takes each board's state as its thread
  last published it, judges on the robot's clock which boards are missing, and updates the alerts.
  It never waits, and in steady state it allocates nothing. Everything it updates (each `Board`, its
  `Value`s, the alerts) belongs to the robot loop's thread.
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
| `packs` | none | The team's packs: a folder of pack folders |
| `key` | `/home/systemcore/spotter.key` | The private key that signs writes, for boards that require signatures (*Signing*) |
| `publicKey` | `spotter.pub` beside the key | Its public key, whose id each signature names |

## Each board

`spotter.boards()` gives one `Board` per address, in the order given.

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
  against it can check anything more ("this board should have the PhotonVision pack at 1.2"); the
  manager validates nothing beyond limits and missing boards.
- **`values()`, `value(id)`:** each value, by `pack.collector.field`: its number, text, flag or
  status, or why it's unavailable; its `level()` and `reason()` (*Limits*); and `nanos()`, when the
  agent last sent it, mapped onto the robot's clock. The manager reuses these: read one in the loop
  that got it, and keep what's needed, never the value itself.
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
however slowly the board answers. Closing the manager finishes every request, refused or dropped.

**Garbage.** Decoding the stream into the board's state, publishing it, and `update()` allocate
nothing in steady state: one event, one source and the buffers are reused, and text is decoded only
when it changes (measured with the JVM's own per-thread count, over 100,000 messages). The JDK's HTTP
client, which reads the stream's chunks, allocates about 48 bytes per event of its own.

## Limits

A value's limits are its pack's (`warn` and `fail`: `above`, `below`, `equals`, `notEquals`,
`missing`; within a level any that matches triggers it, `fail` before `warn`), or robot code's:

```java
Settings.DEFAULTS.withLimits(
    "vision.health.fps",
    new Limits(Spotter.Limit.newInstance().setBelow(30), Spotter.Limit.newInstance().setBelow(10)))
```

An override replaces both of the field's levels. Ids are a value's `pack.collector.field`, or an
action's response field's `pack.action.field`. An override that matches nothing on any board is a
warning, once every board has described itself (or is missing, so a board that's away doesn't hold
the check back), so a typo doesn't quietly do nothing.

A value's `level()` is `OK`, `WARNING`, `FAILING`, or `UNAVAILABLE` (no value, and no `missing` rule:
no alert). A `status` is its script's own verdict. Its `reason()` is in its pack's words: the
comparison that matched (`below 30 fps`), a status's message, or why it has no value.

## Alerts

`spotter.alerts()` is the current set, as data (`Alert`: a level, the board, and text), the same list
until one changes. Robot code maps them onto its own, WPILib's `Alert` say: `FAILING` as
`Level.HIGH`, `WARNING` as `Level.MEDIUM`.

| Alert | Level | Says |
|---|---|---|
| A value at warning or failing | its level | `vision-front: Frames per second below 30 fps` |
| A missing board | failing | `vision-front is missing: Connection refused` |
| A board on another major version of the protocol | failing | `vision-front: it speaks Spotter protocol 3.0, the robot 2.0` |
| Packs that differ and won't be pushed now | warning | `vision-front's packs differ from the robot's: they'll be pushed off the field` |
| A board that reports problems | warning | `vision-front reports 2 problems, the first: /var/lib/frc-spotter/packs/detector/pack.yaml:8: unknown key "evry" in a collector; ...` |
| No key pair, while a board requires signatures | warning | `no Spotter key pair on this controller (/home/systemcore/spotter.key, /home/systemcore/spotter.pub): boards that require signatures will refuse its actions and pushes` |
| A limit override that matches nothing | warning | `Spotter's limits for vision.health.fsp match no value or response field on any board` |

The last two are about robot code's own setup, and name no board. A missing board's last values
stay readable, for what they're worth; its connection says it's missing, and its one alert is that.

## Logging

The manager writes no log. A `Recorder` is handed, on the manager's threads:

- each `Description` and `Values` as it's received (`described`, `values`), as QuickBuffers'
  messages, which WPILib's protobuf logging takes as they are, so a replay sees exactly what the
  robot saw;
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
directory so they live in Git with the robot code), the manager makes sure every board has them, so
a spare board just works.

1. It reads them as it's made into the bundle it pushes: a `PackBundle` (`spotter.proto`), each
   file's path in the folder, whether it's executable, and its bytes, exactly what the pack hash
   covers. A file is executable when any execute bit is set, or when it starts with `#!`: the
   robot's deploy may copy files without their execute bits (unconfirmed on Systemcore: open
   question Q51), and a script that names its interpreter still runs once pushed. A program
   without `#!` (a binary, or a script that relies on a default shell) needs its bit, or its
   interpreter named in the pack: `run: [sh, ./health]`. It connects with the bundle's hash (`PackHash`, as the agent
   hashes what it writes).
2. A board whose packs match starts its stream as usual: nothing is pushed, and nothing restarts.
3. A board whose packs differ answers `409`. A push is signed over a stream's challenge, so the
   manager opens the stream without the hash, and pushes the bundle over it, in protobuf. The agent
   writes its files (`755` or `644`), swaps them in, and exits; systemd starts it again; the
   manager reconnects with the hash, which now matches.
4. **Never automatically on the field:** only while the robot is disabled and the field isn't
   attached. Otherwise the board runs what it has, and a warning says its packs will be pushed off
   the field; they are, once it's off.
5. **No loops.** An automatic push is tried once for each set of packs the board has: one that
   fails (a full disk, a refusal) or doesn't take is a warning, and isn't tried again until the
   board's packs change, or robot code forces one.

**A forced push**, `spotter.push(board)`, pushes now, field or no field: it's robot code's call. It
returns a `CompletableFuture` that completes once the board has taken the packs.

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

`harness/`'s `ManagerContainerTest` runs the manager against the agent installed from its package in
a container under systemd, on a board that requires signatures: a signed action and a cancel; a
board paused with `SIGSTOP`, missing after a second and back; a board answering protocol 3.0; and
the team's packs pushed, signed, the agent restarted by systemd with them.

0.3's client (`client/`, `com.michaelgrundvig.frc:spotter-client`), which polled protocol 1, is
gone.
