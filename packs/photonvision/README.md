# photonvision: PhotonVision on a coprocessor

Whether PhotonVision's service runs and it answers, its settings' fingerprint, its log, and three
actions: restart it, download its settings, and send it a field layout. It needs nothing beyond
PhotonVision's own image.

## Values, log, actions

| | What | Limits |
|---|---|---|
| `photonvision.service.running` | `systemctl is-active photonvision.service`: `active`, or why not | fail unless `active` |
| `photonvision.web.status` | The HTTP status of its web UI, `http://localhost:5800/` | fail unless 200, or when it doesn't answer |
| `photonvision.settings.hash` | `sha256sum` of its settings database: it changes when its settings do | |
| `photonvision.log` | Its service's journal: `./journal --unit=photonvision.service`, a copy of the debian pack's `journal` | |
| `photonvision.restart` | Restarts PhotonVision (`POST /api/utils/restartProgram`); its cameras stop for about 20 s | fail unless 204 |
| `photonvision.export` | Downloads its settings as a zip (`GET /api/settings/photonvision_config.zip`) | fail unless 200 |
| `photonvision.layout` | Sends it a field layout, the action's input (a `.json` file): it saves it and restarts to apply it | fail unless 200 |

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
- **`POST /api/settings/fieldLayout`** takes the layout as a multipart form's file field `data`,
  only when the file's name has a `json` extension, and answers 200 ("Successfully saved the
  uploaded FieldLayout, rebooting..."), then restarts; otherwise 400, saying why. So the action
  names the file it sends: `form: {field: data, filename: layout.json}`.

Not checked:

- **`/opt/photonvision`** as its working folder (so the settings at
  `/opt/photonvision/photonvision_config/photon.sqlite`), and whether the agent's user may read
  that file: PhotonVision's service unit is written by its image's installer (photon-image-modifier),
  not its source. If the agent can't read it, the fingerprint is unavailable and says why.

## Installing it

Copy the folder to `/etc/frc-spotter/packs/photonvision/` (root's, written by root alone) on the
board PhotonVision runs on, and restart the agent; or the robot pushes it (`docs/robot.md`).

## Tests

`harness/`'s `CatalogContainerTest` runs this pack in the Debian 13 test container against a
stand-in for PhotonVision's web server, which answers as its source does: its service and page are
read, its settings hashed, its log paged, and each action run. The layout is taken, sent as
`layout.json`; the same file named after its field is refused, as PhotonVision refuses it.
