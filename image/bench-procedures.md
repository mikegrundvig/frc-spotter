# Coprocessor bench procedures

Four checks that a vision coprocessor holds up before it rides on a robot: that it keeps up with
its cameras without overheating (B1), survives the robot's power (B2 and B3), and can be replaced
quickly (B4). Each one is pass or fail, with the pass line in its own section. The time targets
are proposals: change them if your team decides on others, and write down why.

| When | Run |
|---|---|
| A new board model, camera model, camera count, resolution, cooling, or enclosure | B1 |
| A new board model, or a new way of powering it (regulator, battery pack, wiring) | B2 |
| A new board model or drive model, or a release that changes how the drive is laid out (`provision.sh`, `layout.sh`) | B3 |
| Once a season before the first event, and whenever the spares change | B4 |
| A PhotonVision version bump | B1, then B3's settings checks after a few power cycles |

## What every procedure needs

- **The coprocessor as the robot runs it:** the same board model, cooling, enclosure, drive, and
  power, flashed from a release (`README.md` says how), with its cameras.
- **A laptop on the coprocessor's network:** the robot's radio, or a switch with the laptop at a
  static address on the robot's subnet (`10.TE.AM.5`, netmask `255.255.255.0`, is the Driver
  Station's).
- **The health agent** at `http://10.TE.AM.<address>:5808/v1/health`: temperatures with their trip
  points, core frequencies, PhotonVision's restarts, USB errors, the drive's health, and the boot
  ID. Save what it says once a second for the whole run, with the laptop's time. In PowerShell
  (the address yours):

  ```powershell
  while ($true) {
    try { "$(Get-Date -Format o) $(Invoke-RestMethod http://10.12.34.11:5808/v1/health | ConvertTo-Json -Compress -Depth 10)" | Add-Content health.log } catch {}
    Start-Sleep 1
  }
  ```

- **PhotonVision's dashboard** at `http://10.TE.AM.<address>:5800`, for frame rates.
- **The release's `manifest.json`**, which says what the computer's stamp and settings hash should
  be.

**Safety, with a mentor there:** a coprocessor under load gets hot enough to burn; let it cool
before handling it. Wire power with the supply off, check polarity before switching it on, and
never set a supply above the regulator's or board's maximum input. B3's relay switches the
low-voltage DC side only, never mains wiring; a mentor wires it.

Record each run: the date, the board and drive models, the release, pass or fail for each line,
and the numbers behind them. Keep the records where the team keeps its other test results, so the
next season starts from them.

## B1: Capacity and thermal

**What it shows:** that the coprocessor runs every camera at its planned rate for a match and a
queue without slowing down from heat. [Vision coprocessor capacity](../../docs/characterization.md#vision-coprocessor-capacity)
measures how many cameras one board carries; this checks the plan you settled on, as mounted.

**Steps:**

1. Every camera planned for this computer, at its planned resolution and frame rate, running the
   pipeline as the robot runs it (MultiTag, and per-tag results if the robot code uses them).
2. AprilTags in every camera's view, 1 to 4 m away, so detection does real work.
3. The cooling and enclosure exactly as mounted on the robot, at room temperature or warmer.
4. Run for **20 minutes**: two match lengths and the time in the queue between them. Log the
   agent once a second throughout.

**Pass, all of:**
- [ ] Every camera holds at least **95% of its target frame rate in every 10-second window**.
- [ ] The big cores **never drop below their maximum frequency** (the agent reports both).
- [ ] The hottest thermal zone stays **at least 5 °C under its first passive trip point** (the
      agent reads the trip points from the board, since they differ by kernel).
- [ ] **No PhotonVision restart and no USB error** in the run (the agent's restart count and
      journal errors).

**If it fails:** move a camera to another computer, add cooling, or lower a camera's rate, then run
it again. Keep the frame rate and add a computer when it comes to a choice: more images is more
measurements.

## B2: Power ride-through

**What it shows:** that the coprocessor keeps running through the robot's voltage sags, and how
long it's gone when it doesn't.

**Steps:**

1. Run [Coprocessor brownout ride-through](../../docs/characterization.md#coprocessor-brownout-ride-through),
   with every camera running and the agent logged. Add **sudden dips of 0.1 s, 0.5 s, and 2 s**:
   real sags under drivetrain load last hundreds of milliseconds to seconds.
2. Measure the **reboot time**: from power back to PhotonVision's results reaching the robot (or its
   dashboard showing targets again). It's what every reset costs; write it down.
3. **If the coprocessor runs from a USB battery pack used as a backup supply** (charged from the
   robot, powering the coprocessor without a break): first check the season's game manual. It
   says which batteries may power a computing device, and whether charging one from the robot is
   allowed; ask FIRST's Q&A if it doesn't say. Then test each pack model with the coprocessor and
   every camera running and the agent logging its boot ID and uptime:
   - **Input toggling:** switch the pack's charging input on and off 50 times at random intervals
     of 0.5 to 10 s, as robot power cycles and brownouts would.
   - **Full current:** 30 minutes at the board's full draw (up to 4 A at 5 V for an Orange Pi 5;
     5 A for a 5 Pro or 5 Max), on the pack alone, then again while it charges.
   - **Heat:** the same 30 minutes in the robot's mounting position.
   - **Running down:** from full, charging input off, until the pack quits.
   - **Brownout from the charger's side:** sag the regulator's input to 4 V and back, as step 1
     does.

**Pass, all of:**
- [ ] The ride-through procedure's own pass line: the coprocessor rides through to below the robot
      controller's brownout voltage, so a sag costs the robot its motors before its cameras.
- [ ] The reboot time is measured and recorded.
- [ ] With a pack, each of its tests:
  - [ ] Input toggling: the boot ID never changes, and a scope on the pack's output shows no dip
        below the board's minimum (4.75 V for a 5 V board).
  - [ ] Full current: no overcurrent cut-off, and the output stays within 5% of 5 V.
  - [ ] Heat: the pack's case stays below its rated temperature.
  - [ ] Running down: the hours are recorded (the pit time it covers), and whether it quits
        abruptly or warns first. One that quits abruptly needs a rule: start every event day with
        every pack full.
  - [ ] Brownout from the charger's side: the pack's output holds throughout.

## B3: Power pulls

**What it shows:** that a hard power cut, at the worst moment, costs nothing that matters: the
board comes back by itself, with the same software and settings. With a battery pack as a backup
supply these cuts are rare; this proves the fallback rather than an everyday event.

**Equipment:** a repeat-cycle timer relay switching the coprocessor's own power input,
**downstream of any battery pack**, on the low-voltage DC side.

**Steps:** cut power about **20 times**, letting it boot fully between cuts unless the cut is meant
to land during boot:
1. A few during boot (before the agent answers).
2. A few while idle.
3. A few while every camera streams.
4. **Five within 2 seconds of a settings change** in PhotonVision's dashboard (move a slider, then
   cut), since PhotonVision saves every change within about two seconds.

Before the first cut and after the last, read the root partition's write counters over SSH:
`sudo tune2fs -l "$(findmnt -no SOURCE /)" | grep -E 'Lifetime writes|Last write time'`.

Then the gentle way, **soft-off**, five times with every camera streaming: the robot asks the agent
to shut down (`POST /v1/shutdown`), and once the agent says it's safe, switch the power off.
PhotonVision's stop is bounded at 15 s (the image's drop-in for `photonvision.service`), a proposal
this checks.

**Pass, all of:**
- [ ] **20 of 20 boots recover unattended**: no one touches the board.
- [ ] After every boot, the agent's **version, stamp, and settings hash** (calibrations included)
      match the release's `manifest.json`.
- [ ] **The root is never written**: its write counters read the same before the first cut and
      after the last.
- [ ] **Repairs on `/data` are logged** (`journalctl -b -u 'systemd-fsck@*'`), and **no settings are
      lost**: the settings hash still matches, and the last change before each cut is either kept
      or cleanly absent.
- [ ] The drive's **unsafe shutdowns count rises by 20** (the agent reports it), so every cut was
      a real one.
- [ ] **The journal keeps entries to within its sync interval (10 s) of each cut:** the previous
      boot's last entry (`journalctl -b -1 -n 1 -o short-monotonic`) is within 10 s of the uptime
      the agent last reported before that cut.
- [ ] **Soft-off, 5 of 5:** PhotonVision stops by itself well inside 15 s (after the next boot,
      `journalctl -b -1 -u photonvision` shows it stopped, not killed at the timeout), the agent
      says it's safe before the power goes, and the next boot counts it as a clean shutdown with
      no rise in unsafe shutdowns.

## B4: Recovery

**What it shows:** that a dead drive costs minutes, not an event. Declare one dead, then:

1. **(a) Swap in its spare:** the pre-flashed spare drive labeled for that computer. Time it from
   "dead" to every camera reporting.
2. **(b) Flash a blank drive from the release** (`README.md`, *Flashing from Windows*), then put it
   in. Time it the same way.

**Pass, for each of (a) and (b):**
- [ ] Done **within 5 minutes for (a), within 30 minutes for (b)**.
- [ ] Every camera reports, **with its committed calibration**.
- [ ] The agent's **stamp and settings hash match the release's `manifest.json`**.
- [ ] The robot's and Pitwall's **alerts for that computer clear**.

If (a) fails, the spare wasn't current: reflash spares whenever a release changes that computer's
image, and boot-check each on a bench board, since a spare that's never booted is an unknown.
