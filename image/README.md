# Coprocessor images

Every vision coprocessor runs an image built from this repository. Nothing is set up by hand on a
board, and nothing is installed when it boots: CI takes PhotonVision's official image for the
board, adds a read-only root, a data partition, and the health agent, and then makes one copy per
computer in `coprocessors/coprocessors.yaml`, stamped with that computer's name, address, and
committed settings. One GitHub Release holds every computer's image.

So to change a coprocessor, change the repository and cut a release; to replace a broken one,
flash its image from the release onto a drive. A drive knows which computer it is.

It builds on the rest of the coprocessor work (`coprocessor/README.md`): the table
(`coprocessors/coprocessors.yaml`, with your team's number and computers), the version lock
(`coprocessor/photonvision.lock`, with real checksums), and the health agent's project
(`coprocessor/agent`, with its unit). Without any of them, the workflow's first job stops and says
which.

## What's on a drive

| Partition | What | While running |
|---|---|---|
| 1, the root | PhotonVision's image for the board (Armbian), with PhotonVision pinned to the robot code's version, the health agent, and this computer's identity | Read-only |
| 2, `COPROC` | 32 MiB, FAT: `stamp.json` and `README.txt`, saying which computer the drive is. Windows can open it | Not mounted |
| 3, `coproc-data` | 8 GiB, ext4: PhotonVision's whole config folder (`photon.sqlite`, logs, calibration images) and the journal. Checked at boot; on an error it turns read-only rather than spread the damage | Read-write |

The rest of the drive stays unpartitioned, so nothing resizes at first boot. `/tmp`, `/var/tmp`,
`/var/log` (but for the journal), and NetworkManager's and systemd's state live in RAM.

A read-only root means a power cut can't damage the system, and every writer is deliberate: one
we missed fails loudly in the journal instead of writing quietly. It also enforces the version
lock: PhotonVision's "offline update" can't replace its jar.

**Identity** (written at stamping): the hostname; the address, `10.TE.AM.<address>`, netmask
`255.255.255.0`, gateway `10.TE.AM.4` (FRC's documented static range for on-robot devices is
`.6` to `.19`: [IP Configurations](https://docs.wpilib.org/en/latest/docs/networking/networking-introduction/ip-configurations.html));
`/etc/hosts` naming every computer in the table; a machine ID; the team's SSH public keys
(`coprocessors/authorized_keys`, if the repository has one); and `/etc/coprocessor/stamp.json`:

```json
{
  "name": "vision-front",
  "team": 1234,
  "address": "10.12.34.11",
  "board": "orangepi-5",
  "cameras": ["front-left", "front-right"],
  "release": "coprocessors-2027.1",
  "recipeHash": "…",
  "photonvisionVersion": "dev-v2027.0.0-alpha-2-69-g71416112",
  "settingsHash": "…",
  "agentPort": 5808
}
```

That's the agent's `Stamp` (`coprocessor/README.md`) less what the agent adds as it answers, plus
the port it listens on. A computer with no committed settings gets an empty `settingsHash`, and no
settings database: PhotonVision starts with its defaults.

PhotonVision runs with `-n`, so it never changes the network: the address is the image's.

**Logging in.** The only way in is SSH with one of the team's keys, as the user `photon`
(`ssh photon@10.TE.AM.11`; Windows has the OpenSSH client built in), who can use `sudo` without a
password for maintenance. Nothing else logs in:
- No password works for any account. PhotonVision's image ships public default passwords for
  `root` and `photon`; the image replaces both with `*`, which matches no password but still lets
  a key in.
- No console. PhotonVision's image logs `root` in on the HDMI screen and the serial port with no
  password at all (Armbian's autologin, which its first-login script would have removed); the image
  removes that and turns the consoles' login prompts off. Boot messages still show on both.
- No root login over SSH, and no X11 forwarding.

The build checks itself and fails if any account keeps a usable password or any console logs in
by itself, and in the chroot it reads `sshd -T`'s effective settings.

The team's public keys go in `coprocessors/authorized_keys`. With none there, nobody can log in at
all: reflash to recover. Put only public keys there (it's in every image, and the images are
public); stamping drops each key's comment, which is often a name or an email address. The team's
password manager records where the private key lives. Each drive makes its own SSH host key on
`/data` at first boot.

**Soft-off.** The health agent runs unprivileged, never as root, as `coprocessor-agent`. A polkit
rule (`files/coprocessor-agent.rules`) lets that account do exactly two things, for the agent's
`POST /v1/shutdown` (accepted only from the robot controller, 10.TE.AM.2): stop
`photonvision.service`, and power the board off, also while someone is logged in over SSH. polkit
refuses it everything else.

## The files here

| File | What it does | Needs |
|---|---|---|
| `provision.sh` | Makes PhotonVision's image into the common image, in a chroot of it: installs the pinned jar, the agent and its soft-off rule, and `nvme-cli` and `polkitd` at pinned versions; the read-only root's `/etc/fstab`; the journal; the logins and SSH; Armbian's RAM logging, first-boot resize, and first-run tasks off, and rsyslog and fake-hwclock's saving. Runs PhotonVision's `--smoketest`, which leaves an empty settings database in that version's schema and PhotonVision's native libraries extracted in the image, then checks the result. Safe to rerun | Root, in the image's chroot |
| `layout.sh` | Adds `COPROC` and `coproc-data` to the image file, after the root | sfdisk, mkfs.fat, mkfs.ext4; no root |
| `stamp.sh` | Writes one computer's identity, stamp, and settings database into the image's three filesystems | bash, yq |
| `stamp-image.sh` | Mounts an image's partitions and runs `stamp.sh` into them | Root, loop devices |
| `plan.sh` | Checks the table, the lock, and the release name before anything is downloaded | yq |
| `manifest.sh` | Writes a release's `SHA256SUMS` and `manifest.json` | yq |
| `local-build.sh` | The whole build on a mentor's Linux machine | Root, in a privileged container |
| `boards/*.env` | What differs per board | |
| `files/` | The configuration and units `provision.sh` installs | |
| `bench-procedures.md` | The bench checks a new board, power setup, or release passes | |
| `test/run.sh` | The tests: `coprocessor/image/test/run.sh` | bash, yq; sfdisk and mkfs.fat for the layout tests |

The scripts need [mikefarah's yq](https://github.com/mikefarah/yq), version 4 (GitHub's Ubuntu
runners have it). Ubuntu's own `yq` package is a different program, and the scripts refuse it.

`./gradlew ci` runs the tests wherever bash and yq are installed, and says when it skips them
(Windows, or no yq). In CI on Linux every one must run: a missing tool or a skipped test fails it,
and `ci.yml` installs what the partition tests need. The image workflow runs every test and
`shellcheck` before it builds anything.

## How CI builds a release

`.github/workflows/coprocessor-image.yml` runs when started by hand (Actions, *Coprocessor images*,
*Run workflow*) and when a tag named `coprocessors-*` is pushed:

```bash
git tag coprocessors-2027.1 && git push origin coprocessors-2027.1
```

1. **Check the inputs** (`plan.sh`): the table; the lock's PhotonVision version against
   `vendordeps/photonlib.json`'s, so the coprocessors always run what the robot code was built
   against; a real sha256 for the jar and each base image in use (a placeholder stops the build
   here); the release name. The scripts' tests run first. It also builds the health agent, and
   takes the recipe hash from the build (`./gradlew -q coprocessorRecipeHash`), the same one the
   robot program compiles in.
2. **The common image, per board**, on GitHub's arm64 runners, so nothing is emulated: the base
   image and the jar downloaded and checked against the lock's sha256, then `provision.sh` run in
   PhotonVision's own [photon-image-runner](https://github.com/PhotonVision/photon-image-runner).
   Runs that release nothing cache it by the recipe hash, so trying a settings change only
   re-stamps. A release never uses a cache, for the common image or for Gradle: everything in it
   is built by that run, from that commit.
3. **Stamp, per computer**: `layout.sh`, then `stamp-image.sh`, then `xz`.
4. **Release**: `SHA256SUMS`, `manifest.json`, and every image,
   `<team>-<computer>-pv<version>-<release>.img.xz`, in one GitHub Release.

Where a release can come from:
- only the team's own repository, never a fork;
- only a name starting `coprocessors-`;
- from a tag, only if the tag still names the commit the run built;
- started by hand, only from the default branch, under a new tag the workflow makes at the commit
  it built (GitHub refuses a tag that exists);
- never over an existing release or tag: a name already used stops the build at the first step.

Started by hand with no release name, it builds every image as a workflow artifact (kept 7 days)
and releases nothing, to try a change. `manifest.json` also records the sha256 of the common image
each computer's was stamped from.

Two repository settings make releases stronger still, and are worth turning on: **immutable
releases** (Settings, General, Releases), so a published release's files can't be replaced; and
**build provenance**, by adding GitHub's
[`actions/attest-build-provenance`](https://github.com/actions/attest-build-provenance) to the
release job, so anyone can check an image was built by this workflow from this repository
(`gh attestation verify`).

**The recipe hash** says what a common image is made from, so the robot can tell a coprocessor
flashed from another recipe (`coprocessor/README.md`). It's computed one way, by the build, for the
robot and the images alike: `./gradlew -q coprocessorRecipeHash` prints it and writes it to
`build/coprocessor/recipe-hash.txt`. It covers this folder, the agent and the shared code with
their build files, the lock, `gradle/quality.gradle`, and the versions of the libraries the agent
runs with; not documentation or tests. The robot's own build files (`build.gradle`,
`settings.gradle`, the Gradle wrapper) are left out by design, so a WPILib or Gradle update doesn't
ask for an image that would be the same. `test/recipe_test.sh` checks that everything the image
scripts read from the repository is covered.

GitHub's arm64 runners are free for **public** repositories only. A private repository's builds
need paid arm64 runners, or a mentor's machine (below).

## Building on your own Linux machine

`local-build.sh` does what CI does, in a container. It needs, and why:

- **Linux.** It mounts images and runs a chroot; macOS and Windows can't.
- **A privileged container** (`--privileged`): loop devices and mounts, to open the image's
  partitions, need it. With the host's `/dev` too (`-v /dev:/dev`), or the partitions a loop
  device gets after the container starts never appear in it.
- **On an x86 machine, ARM emulation, from your distribution.** The chroot runs the image's own
  ARM programs (`apt-get`, PhotonVision's smoke test). Install `qemu-user-static` (Debian and
  Ubuntu: `sudo apt install qemu-user-static`; Fedora: `sudo dnf install qemu-user-static`), which
  registers qemu with its "F" flag, so it keeps working inside a chroot. `local-build.sh` checks.
  An arm64 Linux machine needs none of it.
- **To run it in that container.** It mounts images and bind-mounts the machine's `/dev`, so it
  refuses to run anywhere else unless told `--outside-container`.
- **About 30 GB free**, for downloads, the common image, and one computer's image at a time.

Build the health agent and the recipe hash first, on the machine itself
(`./gradlew :coprocessor-agent:jar coprocessorRecipeHash`), then, from the repository's root (Docker shown; Podman takes the same options):

```bash
docker run --rm --privileged -v /dev:/dev -v "$PWD:/repo" -w /repo \
  eclipse-temurin:25-jdk@sha256:119a3d18f160a3e7655a66034d0f43beee31cd7b3b9142d57a5de29772011de6 bash -c '
  set -e
  apt-get update -qq
  apt-get install -y -qq curl xz-utils fdisk dosfstools e2fsprogs git >/dev/null
  curl -fsSL -o /usr/local/bin/yq https://github.com/mikefarah/yq/releases/download/v4.53.6/yq_linux_amd64
  echo "c5f056448f973ae7d39b5401949648a78f2dc1947d6a8eb65be60d5c504b9385  /usr/local/bin/yq" | sha256sum -c -
  chmod +x /usr/local/bin/yq
  git config --global --add safe.directory /repo
  coprocessor/image/local-build.sh \
    --agent-jar coprocessor/agent/build/libs/coprocessor-agent.jar \
    --agent-unit coprocessor/agent/coprocessor-agent.service \
    --settings-tool "java -jar coprocessor/agent/build/libs/coprocessor-agent.jar settings-db"'
```

The container image is pinned by its digest (`eclipse-temurin:25-jdk` on 2026-09-25) and yq by its
checksum (from its release; on an arm64 machine, `yq_linux_arm64`, whose sha256 is
`88a1016bc1d657375a35864e4f44b6f333df8ff97b559f51bba0adcb2169df09`), so the privileged container
runs only what was reviewed. To move to newer ones, take the new digest and checksum from their
publishers, not from a download. The images, `SHA256SUMS`, and `manifest.json` land in
`build/coprocessor-image/local/release/`. It builds from the commit checked out: the recipe hash
counts committed files only, and `local-build.sh` reads it from `build/coprocessor/recipe-hash.txt`,
so make it with the agent: `./gradlew :coprocessor-agent:jar coprocessorRecipeHash`. If it can't
unmount an image, it stops and says what's left mounted.

## Flashing from Windows

You need a laptop where you have administrator rights (every tool that writes a raw disk needs
them), a USB-to-M.2 NVMe enclosure (M-key, NVMe; a SATA-only enclosure won't take an NVMe drive),
and from the release: the computer's `.img.xz` and `SHA256SUMS`. It's a shop job: at an event,
swap in that computer's pre-flashed spare instead.

1. **Check the download.** In PowerShell, in the download folder:

   ```powershell
   $image = "1234-vision-front-pv<version>-<release>.img.xz"   # the file's name
   $expected = ((Get-Content SHA256SUMS) -match [regex]::Escape($image)).Split(" ")[0]
   (Get-FileHash $image -Algorithm SHA256).Hash -eq $expected
   ```

   `True` means the file is exactly what CI built. `False`: download it again.
2. **Write it**, with either:
   - **Raspberry Pi Imager**, which PhotonVision recommends. It must be installed, and use
     **version 2.0.0 or earlier**: PhotonVision warns that 2.0.2 and later fail to write images.
     *Choose OS*, *Use custom*, pick the `.img.xz`; *Choose Storage*, pick the enclosure; when it
     offers OS customization, choose *No*.
   - **Rufus**, whose portable `.exe` runs without installing. It hides USB hard drives, which is
     how an enclosure appears: press **Alt-F** to list them (its FAQ calls them unsupported). Pick
     the `.img.xz` and the enclosure, and write it.

   Double-check the drive you pick: everything on it is replaced.
3. **Check the drive.** Windows opens its `COPROC` partition: `README.txt` and `stamp.json` name the
   computer. If Windows offers to format any of the drive's partitions, choose *Cancel*: it can't
   read the other two, and formatting one would erase it.
4. Put the drive in **that computer's** board, and only that one: two drives with one image would
   share an address. Label the drive with the computer and the release.

After it boots, the computer answers at `http://10.TE.AM.<address>:5808/v1/stamp` (the health
agent) and PhotonVision at `http://10.TE.AM.<address>:5800`; Pitwall shows both.

The **Orange Pi 5B** has no M.2 slot: its image goes on the soldered eMMC, so there's no enclosure
step, and its spare is a spare board. Orange Pi documents writing the eMMC with RKDevTool over USB
in MaskROM mode (Rockchip's USB driver), or from a board booted from an SD card.

## The bootloader, once per board

The RK3588's boot ROM looks for a bootloader in SPI flash, then eMMC, then the SD card, never on
NVMe ([rk2aw](https://xnux.eu/rk2aw/)). So a board that boots from an NVMe drive needs a bootloader
in its SPI flash first, once per board. All of this is **unverified** per board; check it on the
bench with the board you have:

| Board | Bootloader | |
|---|---|---|
| Orange Pi 5 | SPI flash, 16 MB | Install once |
| Orange Pi 5 Plus | SPI flash, 16/32 MB | Install once |
| Orange Pi 5 Max | SPI flash, 16 MB | Install once |
| Orange Pi 5 Pro | SPI flash *or* an eMMC module: which ships is unverified | Check the board |
| Orange Pi 5B | No SPI flash listed; boots its eMMC | Nothing to install |

Two ways to install it:
- **Orange Pi's documented way** (its wiki, per board): RKDevTool on Windows with the board in
  MaskROM mode, which needs Rockchip's USB driver.
- **From the board itself:** boot PhotonVision's own SD card image for the board once, log in, and
  use Armbian's `armbian-install` (*Install/Update the bootloader on SPI Flash*), if PhotonVision's
  image includes it.

Keep one spare board with its bootloader installed. A stale SPI bootloader is a known trap
(PhotonVision's own image adds a tool to read its version: `sudo strings /dev/mtd0 | grep "^U-Boot"`),
and the health agent reports the version in SPI flash.

## For the robot code: the agent, the settings tool, and the lock

Until the health agent, its unit, and the settings tool exist, these are the paths and the
interface the scripts expect (in `lib/common.sh` and the workflow's `env`, one place each):

- **The agent**: `coprocessor/agent/build/libs/coprocessor-agent.jar` (built by
  `:coprocessor-agent:jar`), installed at `/opt/coprocessor/coprocessor-agent.jar`; its unit,
  `coprocessor/agent/coprocessor-agent.service`, installed as `coprocessor-agent.service` and
  enabled. Its `ExecStart` must name that jar path (`provision.sh` checks). It never runs as root:
  the unit needs `User=coprocessor-agent`, or `DynamicUser=yes` with no `User=` (the account is
  then named after the unit), and the build refuses anything else. That name is `IMG_AGENT_USER` in
  `lib/common.sh`, the one the soft-off rule names. A fixed `User=` (the agent's unit has one) is
  created as a system account with its own group, and added to `systemd-journal`.
- **Soft-off**: the agent's account may run `systemctl stop photonvision.service` (polkit's
  `org.freedesktop.systemd1.manage-units`, unit `photonvision.service`, verb `stop`) and power the
  board off (`org.freedesktop.login1.power-off`, and `power-off-multiple-sessions` when someone is
  logged in over SSH). Any other request from that account is refused outright, rather than left to
  polkit's defaults.
- **What root reads for the agent**, every minute, into `/run/coprocessor/`:
  `nvme-smart-log.json` and `nvme-id-ctrl.json` (`nvme-cli`'s JSON) and `spi-uboot-version` (the
  first `U-Boot …` line in SPI flash). A missing file means there was nothing to read.
- **The settings tool**: `java -jar coprocessor-agent.jar settings-db ROWS_DIR EMPTY_DB OUT_DB`
  builds `OUT_DB` from PhotonVision's empty database and the committed rows in `ROWS_DIR`
  (`coprocessors/<computer>/settings/`), and prints the settings hash, lowercase hex, on its last
  line (anything before it, such as the JVM's own warnings, is passed on). It's called only for a
  computer with committed settings.
- **The lock** is read with these yq paths: `.version`, `.jar.url`, `.jar.sha256`,
  `.images.<board>.url`, `.images.<board>.sha256` (YAML, or JSON). Each base image's file name must
  be PhotonVision's for that board (`BOARD_PV_ASSET` in `boards/`).
- **The recipe hash** is the build's (`./gradlew -q coprocessorRecipeHash`, `RecipeHash` in
  `coprocessor/common`), which the workflow passes to `plan.sh`; the robot compiles the same value
  in for its checks.

## What's unverified

Nothing here has run on a board yet, and the workflow hasn't run on GitHub. The tests cover what
runs without one: stamping, the layout, the plan, the manifest, the recipe hash's coverage, `provision.sh`'s
file changes and self-checks on a tree shaped like PhotonVision's image (its logins as that image
ships them), and the soft-off rule, run under Node against the requests it must allow and refuse.
Still to check:
- **PhotonVision starting on the read-only root:** its native libraries are extracted during the
  build's smoke test (`/root/.wpilib/nativecache`), and PhotonVision should load them from there
  without writing.
- **The sandboxes:** the drive-health helper writing `/run/coprocessor` with only read access to
  `/dev/nvme0` and `/dev/mtd0`; polkit applying the soft-off rule to the agent's account.
- **Soft-off's timing:** PhotonVision's stop is bounded at 15 s (a drop-in for
  `photonvision.service`), so the agent's "safe to switch off" comes quickly; bench test B3 checks
  it stops well inside that.

- **Booting it:** from NVMe with the SPI bootloader, per board (`boards/*.env`, `BOARD_VERIFIED=no`);
  Armbian's boot with the root read-only; `/data` mounted, checked, and bind-mounted before
  PhotonVision starts; the journal kept across a power cut (bench test B3).
- **PhotonVision's image as `provision.sh` expects it:** its partition layout (one root partition,
  then free space; `layout.sh` handles MBR or GPT), the Armbian units it turns off, netplan and
  systemd-networkd, and that the smoke test leaves `photon.sqlite` (PhotonVision's own CI suggests
  so).
- **The network:** NetworkManager taking the robot profile on whichever Ethernet port is plugged in
  (the 5 Plus has two), and its duplicate-address detection keeping a second drive for the same
  computer off the address.
- **Windows:** that it opens `COPROC` from a USB enclosure and leaves the other partitions alone;
  Imager and Rufus writing through an enclosure.
- **Sizes and times:** the raw image is about the base image plus 1 GiB of root headroom plus 8 GiB
  of data; whether CI's runners have the room and time for several boards at once.
- **The bootloader** steps, per board, above.
