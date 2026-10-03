# The catalog: packs to copy

Ready-made packs, each a folder, for a team to copy in: none is installed by default, and the agent
runs nothing it isn't given. Take the ones a board needs, as they are or changed to suit, and put
each where the agent reads packs (`docs/agent.md`, *Packs*).

| Pack | For | What |
|---|---|---|
| [`debian`](debian/) | Any Debian-family coprocessor | Its own health: CPU, heat, memory, disk, network link, USB, clock, drive, uptime; and its system journal. The drive's health needs a root timer its installer adds |
| [`photonvision`](photonvision/) | A board running PhotonVision | Whether it runs and answers, its settings' fingerprint, its log; restart it, download its settings |
| [`raspberry-pi`](raspberry-pi/) | A Raspberry Pi | Under-voltage episodes since boot |

Each folder's README says what each value means and its limits, what the pack needs, and how it
was checked.

## Putting one in place

- **Installed with the board,** by whatever installs Spotter (an image build, a script): copy the
  folder to `/etc/frc-spotter/packs/<name>/`, root's and written by root alone (the agent trusts
  nothing else), and restart the agent (`sudo systemctl restart frc-spotter`). Anything a pack needs
  beyond its folder, such as the debian pack's root timer, is the installer's job, and its README
  says so.
- **Pushed by the robot:** put the folder in the robot program's packs folder, in its deploy
  directory, and the manager pushes it to every board whose `agent.json` names the team
  (`docs/robot.md`, *Pushing the team's packs*). A push is for small packs, configuration and scripts; anything that needs root to put in
  place, or large data such as a model file, belongs with the board's image instead.

**Changing one:** a pack is yours once it's copied. Its limits are yours to tune (or the robot's to
override, by id), its collectors' intervals yours to change, and its version yours to bump. Keep
its name, or its values' ids change with it.

## A team's own

A pack for the team's own program follows the same format (`docs/agent.md`, *The format*): a
folder, `pack.yaml` beside the scripts its commands run (`./name`). `spotter-tools check
<folder>` (`docs/tools.md`) reads it as a board's agent would, on any computer. The design's `detector`
example (`agent/src/test/resources/packs/detector/`) shows one with a log and actions.
