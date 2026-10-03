# photonvision: PhotonVision on a coprocessor

Whether PhotonVision's service runs and it answers, its settings' fingerprint, its log, and two
actions: restart it, and download its settings. It needs nothing beyond PhotonVision's own image.

## Values, log, actions

| | What | Limits |
|---|---|---|
| `photonvision.service.running` | `systemctl is-active photonvision.service`: `active`, or why not | fail unless `active` |
| `photonvision.web.status` | The HTTP status of its web UI, `http://localhost:5800/` | fail unless 200, or when it doesn't answer |
| `photonvision.settings.hash` | `sha256sum` of its settings database: it changes when its settings do | |
| `photonvision.log` | Its service's journal: `./journal --unit=photonvision.service`, a copy of the debian pack's `journal` | |
| `photonvision.restart` | Restarts PhotonVision (`POST /api/utils/restartProgram`); its cameras stop for about 20 s | fail unless 204 |
| `photonvision.export` | Downloads its settings as a zip (`GET /api/settings/photonvision_config.zip`) | fail unless 200 |

## What was checked, and what wasn't

Checked against PhotonVision's source at the commit WPILib 2027's alpha PhotonLib names
(`dev-v2027.0.0-alpha-2-69-g71416112`), and not on a board:

- **Port 5800:** `Main.DEFAULT_WEBPORT`, which its server listens on.
- **`/`:** its web UI, the server's static files; any 200 means it answers. (It also has `GET
  /api/status`, which answers 200 and `not dead yet`.)
- **`POST /api/utils/restartProgram`** answers **204**, then runs `systemctl restart
  photonvision.service`. So the restart's limit is `notEquals: 204`: the design's example said 200,
  which would fail every restart that worked.
- **`GET /api/settings/photonvision_config.zip`** answers 200 with the settings as a zip.
- **`photonvision.service`** is the service's name: its own restart names it.
- **`photon.sqlite`** is its settings database's name, in its working folder's
  `photonvision_config`.

Not checked:

- **`/opt/photonvision`** as its working folder (so the settings at
  `/opt/photonvision/photonvision_config/photon.sqlite`), and whether the agent's user may read
  that file: PhotonVision's service unit is written by its image's installer (photon-image-modifier),
  not its source. If the agent can't read it, the fingerprint is unavailable and says why.

**Left out: sending a field layout.** The design's example has a `layout` action that posts a file
to `/api/settings/fieldLayout`, as a form's field `data`. PhotonVision takes that upload only when
its file name ends in `.json` (it checks the extension), and the agent names a form's file after
its field, `data`. So the action would always be refused, 400. It comes back when a pack can name
the file it uploads.

## Installing it

Copy the folder to `/etc/frc-spotter/packs/photonvision/` (root's, written by root alone) on the
board PhotonVision runs on, and restart the agent; or the robot pushes it (`docs/robot.md`).
