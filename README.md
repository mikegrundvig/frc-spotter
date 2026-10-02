# Spotter

Spotter watches an FRC robot's coprocessors from the robot. A small agent on each coprocessor
reports the computer's health (heat, load, memory, disks, the journal, the drive, the network
link, USB devices, the clock) and which computer it is, and runs the checks its packs define. The
robot polls it, judges what it needs, and asks it to power off cleanly before the robot is
switched off. It knows the computers, not the software on them: what to check of a program is a
pack, one YAML file you choose.

## Quick start

On the coprocessor (64-bit ARM or x86 Linux with systemd: Debian, Ubuntu, Raspberry Pi OS,
Armbian, PhotonVision's images):

```sh
# 1. Install the agent. It brings its own Java, and needs nothing configured.
curl -fsSLO https://github.com/mikegrundvig/frc-spotter/releases/download/v0.3.0/frc-spotter_0.3.0_arm64.deb
sudo apt install ./frc-spotter_0.3.0_arm64.deb

# 2. Copy in the packs you want from the catalog (packs/), and restart the agent to read them.
curl -fsSLO https://raw.githubusercontent.com/mikegrundvig/frc-spotter/v0.3.0/packs/photonvision.yaml
curl -fsSLO https://raw.githubusercontent.com/mikegrundvig/frc-spotter/v0.3.0/packs/health.yaml
sudo install -o root -g root -m 0644 photonvision.yaml health.yaml /etc/frc-spotter/packs/
sudo systemctl restart frc-spotter

# 3. Ask it how it is, from the coprocessor or the robot's network.
curl http://<board>:5808/v1/health
```

Use `amd64` in the package's name on x86. With no packs at all it still reports the computer.
Its name is the computer's hostname. It takes a power-off only from the robot controller,
10.TE.AM.2 on the network of its own 10.TE.AM.x address, and a computer with no such address
takes none (`/v1/health`'s `problems` says so). `/etc/frc-spotter/agent.json` can change its port
or the controller's address for an unusual setup, and nothing else (`docs/agent.md`).

**A pack is trusted by its file:** the agent ignores one that isn't root's or that anyone else may
write, and says so in `problems`. Packs are copied, not installed by the package, so a Spotter
upgrade never changes what a computer checks mid-season.

## The catalog of packs

Each file in `packs/` starts with what it checks and where to copy it. CI reads every one with the
agent's own reader.

| Pack | What it checks |
|---|---|
| `photonvision.yaml` | PhotonVision's service, its web server, and a cheap signal that its settings changed |
| `health.yaml` | The computer's own measurements held to limits, so the robot can require them: thermal margin, CPU capped, memory, root disk free |
| `orangepi-rk3588.yaml` | An Orange Pi 5-family board's GPU load and NPU clock, and what can't be read unprivileged |
| `raspberry-pi.yaml` | A Raspberry Pi's supply dropping below its under-voltage threshold |
| `my-program.yaml` | A template for your team's own program: its systemd service, and its health page if it has one |

A pack of your own is one YAML file of probes: a systemd unit, a page on `localhost`, a file, a
USB port, one of the agent's measurements, or any installed program (a team's
`python3 /opt/team/check.py` included), each with fixed arguments: the robot only names a probe.
`docs/agent.md` has the format.

## The robot's side

The client (`client/`, `com.michaelgrundvig.frc.spotter.client`) is plain Java 17 for a robot
program, with no WPILib: it polls each coprocessor on a thread of its own so the robot loop never
waits, judges the probes the robot requires, powers a coprocessor down and watches it go, and
checks each coprocessor's identity before a deploy.

```java
AgentClient front = new AgentClient("vision-front", "10.12.34.11", 5808, 5800,
    ClientSettings.DEFAULTS, System::nanoTime);
Health health = front.latest().answer();          // never blocks; null until the first answer
if (health != null) {
  List<Verdict> verdicts = Verdict.judge(List.of(
      Requirement.passes("photonvision.unit", Level.HIGH, "PhotonVision is running")), health);
}
```

The client and what it shares with the agent are in Spotter's Maven repository, with their sources
and javadoc, at `https://mikegrundvig.github.io/frc-spotter/maven`:

| Coordinates | What |
|---|---|
| `com.michaelgrundvig.frc:spotter-client:0.3.0` | The robot's side; brings `spotter-api` with it |
| `com.michaelgrundvig.frc:spotter-api:0.3.0` | The agent's API records and their JSON, and the packs' reader |

```groovy
repositories {
    maven { url = "https://mikegrundvig.github.io/frc-spotter/maven" }
}
dependencies {
    implementation "com.michaelgrundvig.frc:spotter-client:0.3.0"
}
```

Each release adds its version there; none is removed. `docs/robot.md` is the robot's side in full.

## Releases

Each [release](https://github.com/mikegrundvig/frc-spotter/releases) has the agent's `.deb` for
64-bit ARM and x86 Linux, with its own Java runtime; a tarball of the same for systems without
dpkg; and the `-all.jar` for a board with Java 17 or newer, alone and with its unit, timer,
polkit rules, and install script. `SHA256SUMS` has their checksums. It installs as `frc-spotter`
(its package, unit, and account); releases before 0.2.0 installed it as `frc-coprocessor-agent`.
Image builders install the `.deb` and a team's pack files like any other package and files, and
label their images in `/etc/os-release`, which Spotter reports as it is.

## In this repository

- `agent/`: the agent, its systemd unit, its drive-health timer, polkit rules, and package
  (`docs/agent.md`);
- `packs/`: the catalog of packs;
- `api/`: what the agent and the robot share, and the packs' reader;
- `client/`: the robot's side (`docs/robot.md`);
- `harness/`: container tests of the agent and the client together, and a library a pack's own
  tests use.

## Building

`./gradlew ci` runs every check: formatting, static analysis, the tests (with the container tests,
when Docker or Podman is there), and coverage. Java 25 is downloaded if it's missing; the code is
compiled for Java 17, so the agent also runs on a board's own Java. `./gradlew :agent:agentRelease`
builds the agent's packages for the computer it runs on.

## License

MIT: see `LICENSE`.
