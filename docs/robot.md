# The coprocessors, from the robot

`client/` (Gradle `:client`, `com.michaelgrundvig.frc.spotter.client`) is the robot's side of the
agent (`docs/agent.md`): plain Java 17 with nothing but `:api`, no WPILib or AdvantageKit, so a
robot program, a laptop tool, and the container tests use it alike. The robot template wraps it
with its logging, alerts, and dashboard cards; this page is what the client itself does.

## Asking without waiting

```java
AgentClient front = new AgentClient("vision-front", "10.12.34.11", 5808, 5800, // agent, software
    ClientSettings.DEFAULTS, System::nanoTime);
Poller.Snapshot<Health> latest = front.latest();   // never blocks
boolean missing = front.missing();
```

An `AgentClient` asks its agent for `/v1/health` once a second on a thread of its own, within
250 ms to connect and 500 ms to answer (`ClientSettings`: poll period, connect and answer
timeouts, stale-after, download timeout, power-down timeout; `DEFAULTS` is 1, 0.25, 0.5, 3, 10, 30
seconds). The robot loop reads only the latest snapshot (`polls`, when it was polled, whether it
answered, the error if not, the `Health`, and when the answer arrived on the robot's clock) and
`ageSeconds()`, both lock-free. A coprocessor with no answer newer than 3 s is `missing()`. Measured
through Toxiproxy in the container tests: every fault (latency past the timeout, dropped, reset, or
stalled connections, a stopped agent) reads missing 2.9 to 3.0 s after it starts, with why
(`Read timed out`, `Connection reset`), the loop's reads never take more than microseconds, and the
client answers again within a poll of the fault clearing.

`runProbe(id)` runs one probe now (`/v1/probes/<id>`), `download(name)` streams a pack's file (a
settings backup) up to 64 MB, and `journal(query)` reads a journal page, each within the download
timeout; all of them off the robot loop.

## What the robot needs: requirements

The agent only reports; the robot says what it needs, as `Requirement`s on probes:

```java
List<Requirement> needs = List.of(
    Requirement.equals("vision.version", VISION_VERSION, Level.HIGH,
        "the vision software is the version the robot code was built against"),
    Requirement.passes("vision.http", Level.HIGH, "the vision software answers"),
    Requirement.passes("camera.front-left", Level.HIGH, "the front-left camera is plugged in"),
    Requirement.passes("builtin.thermal-margin", Level.MEDIUM, "the cores aren't about to throttle"));
List<Verdict> verdicts = Verdict.judge(needs, health);
```

`Verdict.judge` says each one met or not, and why, in words for a card or an alert: a probe that
isn't defined on the computer (`is its pack installed?`), hasn't run yet, couldn't run, failed (with
the probe's detail), or reported another value (naming both). The levels map onto the template's
alerts.

## Powering down before switching off

`powerDown()` asks the agent to power its computer off (`POST /v1/shutdown`), on a thread of its
own, and keeps asking each poll until the agent takes it, refuses it, or 30 s pass; then it tries
the agent's port and the software's (a vision program's page) each poll until neither answers.
`powerDownState()` says where that is: whether it was accepted, the agent's answer or why it
couldn't be reached, whether the software's port is still open, and `gone()` once both ports are
closed. A computer that never takes it is named after 30 s, with why. In the container tests, a
computer was gone from both ports 1.1 s after it was asked, with its pack's step stopping the
software first; a stopped agent was named with `Connection reset`, a hung one with `Read timed
out`.

## The check before a deploy

`DeployCheck` (run on the laptop, never on the robot) asks each coprocessor in the compiled table
for its stamp (a second to connect, a second to answer, once more two seconds on for one
restarting) and compares it with the build (`CompiledTable.compare`). A coprocessor with another
name, team, or address, or without a label the build expects (an image builder's, such as its
software's version), fails the deploy, as does something else answering on the agent's port;
another recipe only warns. An image builder's asker may also check its software's port when the
agent doesn't answer, and say the agent is missing (`Asked.AgentMissing`). One that doesn't answer
at all only warns, so a robot can be deployed with its vision off. The template's example table
(team 0) is checked against nothing.

## Unverified

Measured in containers on one x86 desktop, not on a robot: the timings on the robot's network and
a roboRIO's CPU, and a real board's power-off, are bench checks.
