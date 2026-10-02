# Spotter

Spotter watches an FRC robot's coprocessors from the robot. Two products live here:

- **The coprocessor monitor**, which knows nothing of the software on a coprocessor:
  - **the agent** (`agent/`), a small web service each coprocessor runs, reporting the computer's
    health (load, heat, memory, disks, the journal, the drive) and the checks its packs define;
  - **the API** (`api/`), the records and canonical forms the agent and the robot share;
  - **packs**, which say what to check of a piece of software, and how to stop it before a power-off.
- **The image builder** (`image/`), which builds an Orange Pi coprocessor's image for PhotonVision
  from a team's repository, and installs the agent and PhotonVision's pack on it.

`docs/agent.md` is the agent and its API; `docs/robot.md` is the robot's side as the FRC robot
template uses it; `image/README.md` is the image builder.

## Building

`./gradlew ci` runs every check: formatting, static analysis, the tests, and coverage. Java 25 is
downloaded if it's missing; the code is compiled for Java 17, so the agent also runs on a
board's own Java.
