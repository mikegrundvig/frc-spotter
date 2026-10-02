# The coprocessors, from the robot

`client/` (Gradle `:client`, `com.michaelgrundvig.frc.spotter.client`) is the robot's side of the
agent (`docs/agent.md`): plain Java 17 with nothing but `:api`, no WPILib or AdvantageKit, so a
robot program, a laptop tool, and the container tests use it alike. Each release publishes it as
`com.michaelgrundvig.frc:spotter-client` (and `:api` as `com.michaelgrundvig.frc:spotter-api`),
with sources, javadoc, and checksums, to the Maven repository at
`https://mikegrundvig.github.io/frc-spotter/maven` (`gradle/publishing.gradle`; the static
repository is the `gh-pages` branch, which the release workflow adds each version to). The robot template wraps it
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
timeouts, stale-after, the timeout of a request off the loop, power-down timeout; `DEFAULTS` is 1,
0.25, 0.5, 3, 10, 30 seconds). The robot loop reads only the latest snapshot (`polls`, when it was polled, whether it
answered, the error if not, the `Health`, and when the answer arrived on the robot's clock) and
`ageSeconds()`, both lock-free. A coprocessor with no answer newer than 3 s is `missing()`. Measured
through Toxiproxy in the container tests: every fault (latency past the timeout, dropped, reset, or
stalled connections, a stopped agent) reads missing 2.9 to 3.0 s after it starts, with why
(`Read timed out`, `Connection reset`), the loop's reads never take more than microseconds, and the
client answers again within a poll of the fault clearing.

`runProbe(id)` runs one probe now (`/v1/probes/<id>`), and `journal(query)` reads a journal page,
each within the request timeout; both off the robot loop.

## What the robot needs: requirements

The agent only reports; the robot says what it needs, as `Requirement`s on probes:

```java
List<Requirement> needs = List.of(
    Requirement.passes("photonvision.unit", Level.HIGH, "PhotonVision is running"),
    Requirement.passes("photonvision.http", Level.HIGH, "PhotonVision answers"),
    Requirement.passes("lidar.ready", Level.MEDIUM, "the team's lidar service says it's ready"));
List<Verdict> verdicts = Verdict.judge(needs, health);
```

`Verdict.judge` says each one met or not, and why, in words for a card or an alert: a probe that
isn't defined on the computer (`is its pack installed?`), hasn't run yet, couldn't run, failed (with
the probe's detail), or reported another value (naming both). The levels map onto the template's
alerts. The probes are the packs' the team copied onto each computer (`docs/agent.md`, "Packs");
the computer's own report (heat, load, memory, the drive, the network link, USB devices, the
clock) is in each `Health` as it is.

## Powering down before switching off

`powerDown()` asks the agent to power its computer off (`POST /v1/shutdown`), on a thread of its
own, and keeps asking each poll until the agent takes it, refuses it, or 30 s pass; then it tries
the agent's port and the software's (a vision program's page) each poll until neither answers.
`powerDownState()` says where that is: whether it was accepted, the agent's answer or why it
couldn't be reached, whether the software's port is still open, and `gone()` once both ports are
closed. A computer that never takes it is named after 30 s, with why. The agent takes it from the
robot controller only, 10.TE.AM.2 on its own network, so the robot needs nothing configured on the
coprocessor. In the container tests, a computer was gone from both ports 1.1 s after it was asked,
`systemctl poweroff` stopping its software first; a stopped agent was named with `Connection
reset`, a hung one with `Read timed out`.

## The check before a deploy

```java
List<DeployCheck.Expected> expected = List.of(
    new DeployCheck.Expected("vision-front", "10.12.34.11", 5808,
        Map.of("IMAGE_ID", "vision-orangepi", "IMAGE_VERSION", "2027.1")));
boolean mayDeploy =
    DeployCheck.report(
        DeployCheck.check(expected, DeployCheck::ask, DeployCheck.Sleeper.REAL), System.out);
```

`DeployCheck` (run on the laptop, never on the robot) asks each coprocessor the robot program
expects for its identity (`/v1/stamp`: a second to connect, a second to answer, once more two
seconds on for one restarting) and compares the two. The caller says what it expects of each: its
hostname, the address it's asked at, its agent's port, and any `/etc/os-release` keys it must have
with their values, whatever an image builder labels its images with. Spotter knows none of them.
A coprocessor with another hostname, without that address, or with a label missing or different
(named, with both values) fails the deploy, as does something else answering on the agent's port
(an agent from before 0.3.0 among them). A caller's asker may also check the computer's software
port when the agent doesn't answer, and say the agent is missing (`Asked.AgentMissing`). One that
doesn't answer at all only warns, so a robot can be deployed with its vision off.

## Unverified

Measured in containers on one x86 desktop, not on a robot: the timings on the robot's network and
a roboRIO's CPU, and a real board's power-off, are bench checks.
