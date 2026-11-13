# Spotter's command line: check packs, read a board

The agent's program is also Spotter's command line, for a pack's author and a board's installer:

```sh
frc-spotter check <folder>...   # packs, read as the agent reads them
frc-spotter status <board>      # a board's problems, values, identity, packs
```

On a board with the package installed it's `/usr/lib/frc-spotter/bin/frc-spotter`:
`/usr/lib/frc-spotter/bin/frc-spotter status localhost`. Elsewhere, it's the same program in each
release's tarball (`frc-spotter-<version>-linux-<arch>.tar.gz`, `usr/lib/frc-spotter/bin/`), for
64-bit ARM and x86 Linux; there's no build for Windows or macOS. With no command, or `serve`, it's
the agent itself.

**What it prints** is plain text on standard output, one fact a line; its usage, and a failure to
run, go to standard error. **Its exit code:** `0` when all is well, `1` for problems found or a
board not reached, `2` for a command it doesn't know or arguments it can't use. Its arguments and
its output's form are stable, so a build can run it and read it.

## `check <folder>...`

Reads packs with the agent's own reader (no copy of it), on any Linux computer, with no board: each
folder a pack (`<name>/pack.yaml` beside its scripts), or a folder of packs, as a robot program's
`src/main/deploy/spotter-packs` is. It reports every problem a board would list, each as
`file:line: what`, and exits `1` on any, for a build and CI:

- everything the agent refuses: a misspelled key, a missing `every`, a pack in a folder named for
  another, a limit that doesn't fit its field's type (`docs/agent.md`, *The format*);
- the bounds a board holds its packs to, all of them together (`docs/agent.md`, *Bounds*);
- and what the agent takes, but a pack shouldn't have:
  - a program of the pack's own (`./name`) that isn't in its folder, or has neither an execute bit
    nor a `#!` first line, so it won't run once pushed (the robot's deploy may drop execute bits:
    a `#!` carries it);
  - a `.` in an id or a field's name, which makes values' ids (`pack.collector.field`) read two
    ways;
  - `equals` or `notEquals` on a `status` field, which is never applied (a status's level is its
    script's own; only `missing` applies).

It also prints notes, which are no problem: a collector's field that's its command's own named part
(an `http` collector's `status` is the HTTP status, not a key of its output). It skips the trust
check (root's, written by root alone), which means something only on a board, and says so.

```
$ frc-spotter check src/main/deploy/spotter-packs
src/main/deploy/spotter-packs/detector/pack.yaml:8: unknown key "evry" in a collector; known: every, fields, file, http, id, run, timeout
src/main/deploy/spotter-packs/detector/pack.yaml:5: ./health has neither an execute bit nor a #! first line, so it won't run once pushed: chmod +x it, start it with #!, or run: [sh, ./health]
note: the trust check (root's, and written by root alone) is skipped: it means something only on a board
2 packs checked: 2 problems
```

## `status <board> [--wait=5s]`

Reads a board as the robot's manager does, from its stream (its address, `10.12.34.11`, or `host:port`, on 5808
unless given): its problems first, then each value as `id  value unit  (label)` or
`unavailable: reason` (and its level, when it's at warning or failing), then its identity and its
packs. The wait is written as a pack's durations are (`500ms`, `1.5s`, `2m`). A board not reached
within the wait is said in plain words, and exits `1`:

```
$ frc-spotter status 10.12.34.11
vision-front (10.12.34.11): agent 0.5.1, protocol 2.0
Problems: none
Values: 20
  debian.cpu.busiest        41 %  (Busiest core)
  debian.memory.available   12.5 %  (Memory available)  warning: below 15 %
  debian.drive.wear         unavailable: no such file: /run/frc-spotter-debian/drive.json  (Drive wear)
  ...
Identity:
  hostname   vision-front
  addresses  10.12.34.11
  ...
Packs: 2
  debian 1.0.0  installed  /etc/frc-spotter/packs/debian
  detector 0.2.0  pushed  /var/lib/frc-spotter/packs/detector

$ frc-spotter status vision-frnt
vision-frnt: not reached: its name doesn't resolve (vision-frnt) (waited 5.0 s)
```

The other failures read `connection refused: is frc-spotter running on it?` and `no answer in
time: is it on the robot's network?`.

## From a robot program's build

A robot program's build on Linux can run `check` as part of its build, so a pack's mistake fails the
build where it's made: an `Exec` of the release tarball's program, unpacked into the build folder.

```groovy
// ./gradlew spotterCheck: the robot's packs, as a board's agent reads them. Part of check, so a
// pack's mistake fails the build. frc-spotter is the program from Spotter's release tarball.
def spotterCheck = tasks.register("spotterCheck", Exec) {
    group = "verification"
    description = "Check the robot's Spotter packs as a board's agent reads them."
    commandLine file("tools/frc-spotter"), "check", file("src/main/deploy/spotter-packs")
}
tasks.named("check") {
    dependsOn spotterCheck
}
```

A non-zero exit fails the task, so `spotterCheck` fails the build on any problem, and its output
says where each one is.
