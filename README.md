# Spotter

Spotter watches an FRC robot's coprocessors from the robot. It knows the computers, not the
software on them:

- **the agent** (`agent/`), a small web service each coprocessor runs, reporting the computer's
  health (load, heat, memory, disks, the journal, the drive) and the checks its packs define, and
  powering the computer off cleanly when the robot asks;
- **packs**, which say what to check of one program and how to stop it before a power-off: the
  built-in pack (`packs/builtin/`), and an example pack with a Java helper
  (`examples/packs/java-helper/`);
- **the API** (`api/`), what the agent, the robot, and the build share, with the build tool that
  compiles a team's table of coprocessors;
- **the client** (`client/`), the robot's side: polling each agent without ever blocking the robot
  loop, judging what the robot needs of it, powering it down, and the check before a deploy;
- **the harness** (`harness/`), container tests of the agent and the client together, and a
  library a pack's own tests use.

Image builders (such as [frc-paddock](https://github.com/mikegrundvig/frc-paddock), which builds
PhotonVision coprocessor images) install the agent's `.deb` and their packs.

`docs/agent.md` is the agent, its API, probes, packs, configuration, and package; `docs/robot.md`
is the robot's side.

## Installing the agent

Each [release](https://github.com/mikegrundvig/frc-spotter/releases) has the agent's `.deb` for
64-bit ARM and x86 Linux, with its own Java runtime; a tarball of the same for systems without
dpkg; and the `-all.jar` for a board with Java 17 or newer, alone and with its unit, polkit rules,
and install script. `SHA256SUMS` has their checksums. Configure a computer in
`/etc/frc-spotter/agent.json` (`docs/agent.md`). It installs as `frc-spotter` (its package, unit,
and account); releases before 0.2.0 installed it as `frc-coprocessor-agent`.

## Building

`./gradlew ci` runs every check: formatting, static analysis, the tests (with the container tests,
when Docker or Podman is there), and coverage. Java 25 is downloaded if it's missing; the code is
compiled for Java 17, so the agent also runs on a board's own Java. `./gradlew :agent:agentRelease`
builds the agent's packages for the computer it runs on.

## License

MIT: see `LICENSE`.
