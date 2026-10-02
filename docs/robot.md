# The coprocessors, from the robot

Each vision coprocessor runs a small health agent (`coprocessor/README.md` says what it answers,
and how the table of coprocessors, `coprocessors/coprocessors.yaml`, describes them). This page is
the robot's side: how the robot program asks each agent, what it alerts on, the coprocessors'
cards on Pitwall's page, and the check `deploy` makes first. The code is
`frc.robot.subsystems.coprocessors`.

## Asking without waiting

Each coprocessor is asked for its health (`/v1/health`, which carries its stamp: 3 to 4 KB) once a
second, on a thread of its own, within 250 ms to connect and 500 ms to answer (`Coprocessors.Settings`, in
[the values register](values.md#coprocessorssettings)). The robot loop never waits on the network:
it reads only each coprocessor's latest answer and how old it is. An answer is placed on the
robot's clock halfway between asking and hearing. The agent answers at once from its last reading,
taken in the background, so what an answer says can be up to about a poll older than the answer.

What each answer says is logged under `Coprocessors/<name>` as AdvantageKit inputs (the stamp, load
per core, the big cores' clock against their most, every thermal zone with its trip point,
PhotonVision's service and restarts, the cameras plugged in, the journal's errors, the drive), so
the coprocessors' health sits in the match log and replays with it. A replay judges each
coprocessor from its log exactly as the robot did; the loop judges one in full only when a new
answer arrives, or it goes missing or comes back. `Coprocessors/<name>/BootedAtRobotNanos` is the
robot's time when the coprocessor booted: an entry in its journal, which counts from its boot, is
on the robot's clock at that plus the entry's monotonic time. A reading older than its answer
places the boot late, never early, so each boot keeps its earliest estimate. That places a coprocessor's journal
on the match's timeline, though the boards keep no reliable wall time.

## Alerts

Each coprocessor is judged against what this build expects of it: the table compiled into the
program, with this build's PhotonVision version, image recipe, and each computer's committed
settings.

| Alert | Level | When |
|---|---|---|
| Missing | high while enabled, medium while disabled | No answer for 3 s, or none since the robot program started, with why (no connection, no answer in time); one that took a request to power down says so instead |
| The wrong image | high | Its stamp names another PhotonVision, name, team, or address than this build's: both are named, by the table's own comparison |
| Another recipe | medium | Its image was built from another recipe than this build's: flash this build's release when convenient. A build without a recipe hash (no Git, or not the repository's own folder) can't judge the recipe: its card says so, and nothing is alerted |
| Two boards answer | high | MACs take turns answering at its address: a spare drive with its image in another board, say. A board replaced by another isn't two boards, since the first never answers again |
| A camera missing | medium | A camera the table gives it isn't plugged in where its settings expect it |
| Hot | medium | Its hottest zone within 5 °C of its trip point (each zone's first passive trip point, else its first), or its big cores held below their most; unsaid only once 7 °C clear for 10 s, so a board at the margin doesn't flicker it |
| PhotonVision restarted | medium | systemd restarted PhotonVision since the robot first heard this boot: a board up all day has restarts the robot never saw, which its card shows |
| Errors in its journal | medium | USB, UVC, filesystem, memory, or heat errors this boot, at once however old; other errors only as they come after the robot first heard this boot; with the latest |
| Settings differ from Git | medium | Its PhotonVision settings now differ from those committed for it: normal while calibrating, so not red |
| PhotonVision's version | high | A version PhotonVision publishes (`/photonvision/version`) isn't this program's PhotonLib (`PhotonVersion.versionString`), as exact strings |
| Watching it failed | high | The robot's own code watching one coprocessor threw: the others are watched on |

The version alert catches what PhotonLib lets through: its own check compares only the message
format, so two versions that share one pass it. Every coprocessor publishes its version to that one
topic as its PhotonVision starts, and the topic keeps only the last, so a wrong version heard once
is said for the rest of the run, naming the coprocessors whose stamp carried it; it's unsaid when
each of them boots again (and publishes anew). A program built without its table (anything but
Gradle) watches no coprocessors, and says so in a high alert.

**The template's example table** (team 0, with computers listed) would give every robot a missing
alert for a coprocessor it doesn't have, so a robot watches none of them, and a low alert says to
set the team number and list the computers (or `computers: []` for none). The simulator still
simulates the example's computers, so their cards and faults can be tried.

## Pitwall's cards

Pit has a card for each coprocessor: present, missing, or two boards answering; its PhotonVision
version and stamp against this build's, and its settings against Git's; the hottest zone against
its trip point; the big cores' load, and whether they're throttled; the cameras present against the
table; and since boot, PhotonVision's restarts, whether the last shutdown was clean, and the
journal's errors. Each card ends in what can be done:

- **Open PhotonVision**: its own page, by address (`http://10.TE.AM.x:5800`).
- **Download settings backup**: the agent's `/v1/settings.zip`, laid out like the repository's
  folder for the computer: unzip it into the repository, review the diff, and commit it.
- **Download logs**: its journal (the kernel's, PhotonVision's, and the agent's entries, as the
  agent serves them), filtered by priority and unit, each entry on the robot's clock when it's from
  the coprocessor's current boot. The viewer starts at the latest 500 entries; **Older** pages
  back, and **Newer** fetches those since. **Download as text** is this boot's whole journal, from
  its first entry, up to Pitwall's bounds (8 MB, 100 pages, 30 s).

Pitwall asks the agent for these on its own server threads, when the page asks, never in the robot
loop; the browser never talks to a coprocessor. Only the page can ask (its requests name its event
stream), two requests at once at most, and downloads wait until the field system disconnects. A
backup is streamed through as it arrives (up to 64 MB), never held whole; a journal page is at most
8 MB, and a log's text is cut off after 30 s. An agent busy with another journal or settings
request (503) is asked once more after the wait it names (at most 2 s); still busy, the page is told
so (503), to try again.

## Powering down before switching off

A power cut can lose what a coprocessor was writing (its root is read-only; its settings aren't),
so Pit has an action, **Power down coprocessors**, to run before someone flips the breaker. It runs
only while the robot is disabled, and only until the field system first connects in this run of
the program: never in a match, even through a dropout that clears "field connected" for a moment.

1. It asks every computer in the table to power down (`POST /v1/shutdown`, which the agent takes
   only from the robot's own address): PhotonVision stops, then the board halts. One that doesn't
   answer is asked again each second, for 30 s; one that refuses (an image without the request
   answers 404) isn't asked again.
2. It waits until each that took the request is gone on both its agent's port and PhotonVision's
   (5800), each tried once a second on its IO's own thread.
3. Then Pit and the state bar say **Safe to switch off**. Only a board that took the request can
   be gone: one that couldn't be reached is quiet on both ports too (its network unplugged, say),
   and still running, so the page says *Don't switch off: … couldn't be reached: check it before
   switching off*, and its card says it couldn't be asked, and why.

One not gone 30 s after it was asked is named in a high alert, with why (couldn't be reached,
refused, or still answering): don't switch off yet. A halted board stays off while it has power, so
a robot left on after powering down says, after three minutes, *Coprocessors halted: cycle the
robot's power to restart them*, and the missing alerts say each was powered down from Pitwall, not
that it failed. Enabling the robot stops the action asking and waiting, but a board that already
took the request still powers down.

A button can start the same sequence: wire a push button between one of Systemcore's digital
inputs and ground, and set `powerDownButton` in `Coprocessors.Settings` to that input (it's -1, no
button, by default). Holding it a second asks Pitwall to run the action, under the same rule. It
works only once it has read released: a button stuck, shorted, or floating that reads pressed from
the start does nothing, and a medium alert says to check its wiring.

## The check before a deploy

`./gradlew deploy` first runs `coprocessorCheck`, which asks each coprocessor in the table for its
stamp: a second to connect and a second to answer, then, for one that didn't answer with a stamp,
once more two seconds on, as one restarting would answer then. A coprocessor that never answers
holds the deploy about 8 s.

- A coprocessor whose stamp names another PhotonVision, name, team, or address than this build's
  **fails** the deploy, naming both (the table's own comparison). One built from another recipe
  only **warns** (flash this build's release when convenient), as does one this build can't judge
  the recipe of, having no recipe hash; the rest is still compared.
- So does something else answering on the agent's port (a 404, or no stamp): a different image.
- So does PhotonVision answering (5800) without its agent (refused, or no answer in time): the
  agent isn't running, or it isn't this repository's image.
- One that doesn't answer at all only **warns**, so the robot can be deployed with its vision off.

The template's example table (team 0) is checked against nothing, and says so.

`./gradlew coprocessorCheck` runs it alone. `-PskipCoprocessorCheck` skips it, and says so: for a
deploy that knowingly leaves a coprocessor as it is.

## In the simulator

Each computer in the table gets a simulated agent, which answers as a healthy board running this
build's image would, once a second on the robot's clock, through the same records a real answer is
read into. Its faults (unreachable, the wrong version, hot, a camera unplugged, PhotonVision
restarting, a duplicated address, settings changed, ignoring power-down) are in Pitwall's *Sim*
view, as [the simulator](simulator.md#what-can-fail-live) lists them, and show on its card and in
its alerts as a real coprocessor's would. Powered down, it halts three seconds later, and stays
halted until the simulator starts again. *Open PhotonVision* has no page behind it in the simulator.

## Unverified

- The vendor kernel's thermal trip points: the agent reads each board's at run time.
- Whether Systemcore's SmartIO inputs are pulled up, as a roboRIO's are, for the button.
- How long a board takes to power down. The agent stops PhotonVision first, which it allows up to
  two minutes to save its settings, so a slow stop can outlast the 30 s alert: the alert then says
  it's still answering, and the board is on its way down.
- How quickly two boards at one address take turns answering: it depends on how often the robot
  renews its address table, so the window (a minute) may need tuning against a real pair.
