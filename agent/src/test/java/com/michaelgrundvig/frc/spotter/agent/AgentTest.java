package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.CpuCluster;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.Memory;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.api.TripPoint;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.probes.Pack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's health of an RK3588 coprocessor, read from a fixture tree: the computer's own, which
 * knows nothing of the software it runs and needs nothing configured, and its packs' probes on it.
 */
class AgentTest {
  @TempDir Path dir;
  Fixture fixture;

  /** A pack of a board's measurements and its cameras' ports, as a team would write one. */
  static final String BOARD =
      """
      pack: board
      probes:
        - id: camera.front-left
          kind: usb
          path: /dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0
          every: 2
        - id: camera.front-right
          kind: usb
          path: /dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0
          minSpeedMbps: 5000
          every: 2
        - id: board.thermal-margin
          kind: threshold
          metric: thermal.margin.celsius
          min: 5
          every: 1
        - id: board.cpu-capped
          kind: threshold
          metric: cpu.capped
          max: 0
          every: 1
        - id: board.memory
          kind: threshold
          metric: memory.available.percent
          min: 10
          every: 5
        - id: board.data-free
          kind: threshold
          metric: disk.data.free.percent
          min: 10
          every: 30
        - id: board.root-read-only
          kind: threshold
          metric: boot.root.readonly
          min: 1
          every: 60
      """;

  @BeforeEach
  void aCoprocessor() throws IOException {
    fixture = new Fixture(dir);
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"), Fixture.lines("journal-this-boot.json"));
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), Fixture.lines("journal-previous-clean.json"));
  }

  @Test
  void healthReadsTheComputerWithNothingConfigured() {
    Agent agent = fixture.agent();
    Health health = agent.health();

    assertThat(health.problems()).isEmpty();
    Stamp stamp = health.stamp();
    assertThat(stamp.hostname()).isEqualTo("vision-front");
    assertThat(stamp.addresses()).containsExactly("10.12.34.11");
    assertThat(stamp.bootId()).isEqualTo("3c1e6a2e-6f6c-4a1d-9a53-8c1f0c7b8e21");
    assertThat(stamp.mac()).isEqualTo("c0:74:2b:fe:12:34");
    assertThat(stamp.uptimeSeconds()).isEqualTo(1234.56);
    assertThat(stamp.osRelease())
        .containsEntry("ID", "debian")
        .containsEntry("PRETTY_NAME", "Armbian 25.8.1 trixie")
        .containsEntry("IMAGE_ID", "vision-orangepi")
        .containsEntry("IMAGE_VERSION", "2027.1")
        .containsEntry("PADDOCK_PHOTONVISION_VERSION", "v2027.1.0 \"stable\"")
        .hasSize(10);

    assertThat(health.boot().uptimeSeconds()).isEqualTo(1234.56);
    assertThat(health.boot().monotonicMicros()).isEqualTo(1_234_560_000L);
    assertThat(health.boot().rootDevice()).isEqualTo("/dev/nvme0n1p2");
    assertThat(health.boot().rootReadOnly()).isTrue();
    assertThat(health.boot().lastShutdownClean()).isTrue();

    Cpu cpu = health.cpu();
    assertThat(cpu.busyPercent()).hasSize(8);
    assertThat(cpu.busyPercent().get(4)).isEqualTo(80.4);
    assertThat(cpu.windowSeconds()).isEqualTo(1234.56);
    assertThat(cpu.clusters())
        .containsExactly(
            new CpuCluster(List.of(0, 1, 2, 3), 1008, 1800, 1800),
            new CpuCluster(List.of(4, 5), 2016, 2016, 2400),
            new CpuCluster(List.of(6, 7), 2400, 2400, 2400));
    assertThat(cpu.bigCoresCapped()).isTrue();

    assertThat(health.thermal()).hasSize(7);
    ThermalZone soc = health.thermal().get(0);
    assertThat(soc.type()).isEqualTo("soc-thermal");
    assertThat(soc.celsius()).isEqualTo(61.3);
    assertThat(soc.trips())
        .containsExactly(
            new TripPoint("active", 70),
            new TripPoint("passive", 85),
            new TripPoint("critical", 115));
    assertThat(health.hottest().orElseThrow().type()).isEqualTo("bigcore0-thermal");

    assertThat(health.memory()).isEqualTo(new Memory(7927, 5101));
    // The fixture's root is a folder on this computer; it has no /data.
    assertThat(health.disks()).extracting(d -> d.mount()).containsExactly("/");

    assertThat(health.journal().counts())
        .containsEntry("usb", 1)
        .containsEntry("uvc", 1)
        .containsEntry("filesystem", 1)
        .containsEntry("oom", 1)
        .containsEntry("thermal", 1)
        .containsEntry("error", 1);
    assertThat(health.journal().latest()).hasSize(3);
    assertThat(health.journal().latest().get(2).message()).isEqualTo("Error é");

    Drive drive = Objects.requireNonNull(health.drive());
    assertThat(drive.device()).isEqualTo("/dev/nvme0 (Samsung SSD 980 250GB)");
    assertThat(drive.celsius()).isEqualTo(40.9);
    assertThat(drive.unsafeShutdowns()).isEqualTo(23);

    // No packs: the computer's own report alone.
    assertThat(health.probes()).isEmpty();
    assertThat(Json.compact(health.toJson()).getBytes(StandardCharsets.UTF_8).length)
        .isBetween(2000, 5000);
  }

  @Test
  void aPacksProbesRunOnTheComputer() throws IOException {
    fixture.pack("board", BOARD);
    Agent agent = fixture.agent();
    assertThat(agent.health().probes())
        .extracting(ProbeResult::id)
        .containsExactly(
            "camera.front-left",
            "camera.front-right",
            "board.thermal-margin",
            "board.cpu-capped",
            "board.memory",
            "board.data-free",
            "board.root-read-only");
    assertThat(agent.health().probes())
        .allMatch(probe -> probe.status().equals(ProbeResult.PENDING));
    agent.probes().runAll();
    fixture.micros.addAndGet(1_000_000);
    Health health = agent.health();
    assertThat(result(health, "camera.front-left"))
        .isEqualTo(pass("7-1 5000 Mb/s Arducam OV9281 USB Camera"));
    ProbeResult slow = result(health, "camera.front-right");
    assertThat(slow.status()).isEqualTo(ProbeResult.FAIL);
    assertThat(slow.value()).isEqualTo("3-1 480 Mb/s Arducam OV9281 USB Camera");
    assertThat(slow.detail()).isEqualTo("linked at 480 Mb/s, below 5000");
    assertThat(result(health, "board.thermal-margin")).isEqualTo(pass("12.2"));
    // Its big cores are held to 2016 of 2400 MHz.
    ProbeResult capped = result(health, "board.cpu-capped");
    assertThat(capped.status()).isEqualTo(ProbeResult.FAIL);
    assertThat(capped.detail()).isEqualTo("cpu.capped is 1, above 0");
    assertThat(result(health, "board.memory")).isEqualTo(pass("64.3"));
    ProbeResult data = result(health, "board.data-free");
    assertThat(data.status()).isEqualTo(ProbeResult.ERROR);
    assertThat(data.detail()).isEqualTo("disk.data.free.percent isn't measured on this computer");
    assertThat(result(health, "board.root-read-only")).isEqualTo(pass("1"));
  }

  /** A result's status, value, and detail: when it ran and how long it took left out. */
  private static ProbeResult result(Health health, String id) {
    ProbeResult result = health.probe(id).orElseThrow();
    return new ProbeResult("", "", result.status(), result.value(), result.detail(), 0, 0);
  }

  private static ProbeResult pass(String value) {
    return new ProbeResult("", "", ProbeResult.PASS, value, "", 0, 0);
  }

  @Test
  void healthAskedAgainSoonIsTheSameAnswerAndLaterANewOne() throws IOException {
    Agent agent = fixture.agent();
    Health first = agent.health();
    fixture.micros.addAndGet(100_000);
    assertThat(agent.health()).isSameAs(first);

    // A second later, with the big cores busier: the window is the second between.
    fixture.micros.addAndGet(900_000);
    fixture.write(
        CpuSource.STAT,
        """
        cpu  0 0 0 0 0 0 0 0 0 0
        cpu0 2000 0 1000 96100 500 0 100 0 0 0
        cpu1 1500 0 800 97100 400 0 100 0 0 0
        cpu2 1200 0 700 97600 300 0 100 0 0 0
        cpu3 1300 0 500 97900 200 0 100 0 0 0
        cpu4 10600 0 2200 3000 100 0 50 0 0 0
        cpu5 10100 0 2300 3400 100 0 50 0 0 0
        cpu6 10900 0 2200 2700 200 0 50 0 0 0
        cpu7 10800 0 2300 2800 200 0 50 0 0 0
        """);
    Health second = agent.health();
    assertThat(second).isNotSameAs(first);
    assertThat(second.cpu().windowSeconds()).isEqualTo(1.0);
    assertThat(second.cpu().busyPercent())
        .containsExactly(0.0, 0.0, 0.0, 0.0, 100.0, 100.0, 100.0, 100.0);
  }

  @Test
  void whatCantBeReadIsUnknownAndSaysWhy() throws IOException {
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"), new Commands.Output(1, List.of(), false, false));
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), new Commands.Output(1, List.of(), false, false));
    fixture.delete(StampSource.HOSTNAME);
    fixture.delete(StampSource.OS_RELEASE);
    fixture.delete(MemorySource.MEMINFO);
    fixture.write(DriveSource.SMART_LOG, "{broken");

    Agent agent = fixture.agent();
    Health health = agent.health();
    assertThat(health.problems())
        .containsExactly(
            "memory: /proc/meminfo isn't there",
            "journal: journalctl failed",
            "drive: line 1, column 2: expected a member's name in quotes");
    assertThat(health.boot().lastShutdownClean()).isNull();
    assertThat(health.stamp().hostname()).isEmpty();
    assertThat(health.stamp().osRelease()).isEmpty();
    assertThat(health.memory()).isEqualTo(Memory.UNKNOWN);
    assertThat(health.drive()).isNull();
    assertThat(fixture.log).hasSize(3);

    // The same problems again a second later: logged once, still reported.
    fixture.micros.addAndGet(1_000_000);
    assertThat(agent.health().problems()).hasSize(3);
    assertThat(fixture.log).hasSize(3);

    // No NVMe drive, or nothing reading it: no drive, and no problem.
    fixture.delete(DriveSource.SMART_LOG);
    fixture.micros.addAndGet(1_000_000);
    assertThat(fixture.agent().health().drive()).isNull();
  }

  @Test
  void osReleaseIsUsrLibsWhenEtcHasNone() throws IOException {
    fixture.delete(StampSource.OS_RELEASE);
    fixture.write(StampSource.OS_RELEASE_FALLBACK, "ID=ubuntu\n");
    assertThat(new StampSource(fixture.host).osRelease())
        .containsExactly(Map.entry("ID", "ubuntu"));
  }

  @Test
  void osReleaseIsReadAsItsWrittenAndNothingMore() {
    assertThat(
            StampSource.parseOsRelease(
                """
                # a comment
                A=plain
                B="double \\"quoted\\" \\$x \\\\ \\n"
                C='single "quoted"'
                  D=indented\r
                not an assignment
                9X=bad key
                =nothing
                E=
                A=again
                """))
        .containsExactly(
            Map.entry("A", "again"),
            Map.entry("B", "double \"quoted\" $x \\ \\n"),
            Map.entry("C", "single \"quoted\""),
            Map.entry("D", "indented"),
            Map.entry("E", ""));
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      many.append("K").append(i).append('=').append("v".repeat(1000)).append('\n');
    }
    Map<String, String> bounded = StampSource.parseOsRelease(many.toString());
    assertThat(bounded).hasSize(StampSource.MAX_OS_RELEASE_KEYS);
    assertThat(bounded.get("K0")).hasSize(StampSource.MAX_OS_RELEASE_VALUE);
  }

  @Test
  void packsAreEveryYamlFileInTheirFolderInOrder() throws IOException {
    fixture.pack(
        "b-second",
        "pack: second\njournalUnits: [second.service]\nprobes:\n"
            + "  - id: second.unit\n    kind: unit\n    unit: second.service\n");
    fixture.pack(
        "a-first",
        "pack: first\nprobes:\n" + "  - id: first.unit\n    kind: unit\n    unit: first.service\n");
    fixture.write(Pack.DIRECTORY + "/notes.txt", "not a pack");
    fixture.write(Pack.DIRECTORY + "/old.yaml.bak", "not a pack either");
    Configuration configuration = Configuration.read(fixture.host);
    assertThat(configuration.problems()).isEmpty();
    assertThat(configuration.probes().packs()).containsExactly("first", "second");
    assertThat(configuration.probes().probes())
        .extracting(probe -> probe.id())
        .containsExactly("first.unit", "second.unit");
    assertThat(fixture.agent().journalUnits())
        .containsExactly("kernel", "frc-spotter.service", "second.service");
  }

  @Test
  void aPackFileNotRootsAloneIsIgnoredAndSaysWhy() throws IOException {
    fixture.pack("mine", "pack: mine\n");
    fixture.pack("shared", "pack: shared\n");
    fixture.pack("trusted", "pack: trusted\n");
    fixture.owners.put(Pack.DIRECTORY + "/mine.yaml", new Host.Owner(1000, 0644));
    fixture.owners.put(Pack.DIRECTORY + "/shared.yaml", new Host.Owner(0, 0664));
    Health health = fixture.agent().health();
    assertThat(health.problems())
        .containsExactly(
            "pack ignored: /etc/frc-spotter/packs/mine.yaml isn't root's (its owner is user 1000)",
            "pack ignored: /etc/frc-spotter/packs/shared.yaml may be written by its group or"
                + " others (mode 664): chmod go-w it");
    assertThat(fixture.agent().probeSet().packs()).containsExactly("trusted");
  }

  @Test
  void aPackThatCantBeReadOrClashesIsIgnoredAndTheRestRun() throws IOException {
    fixture.pack("a", "pack: a\nprobes:\n  - id: a.unit\n    kind: unit\n    unit: a.service\n");
    fixture.pack("b", "pack: b\nprobes:\n  - id: a.unit\n    kind: unit\n    unit: b.service\n");
    fixture.pack("c", "pack: a\n");
    fixture.pack("d", "pack: d\nbeforeShutdown: []\n");
    fixture.pack("e", "pack: e\nprobes:\n  - id: e.unit\n    kind: unit\n    unit: e.service\n");
    java.nio.file.Files.createDirectories(fixture.host.path(Pack.DIRECTORY + "/f.yaml"));
    Health health = fixture.agent().health();
    assertThat(health.problems().subList(0, 3))
        .containsExactly(
            "pack ignored: /etc/frc-spotter/packs/b.yaml: probe a.unit is defined already, by pack"
                + " a",
            "pack ignored: /etc/frc-spotter/packs/c.yaml: a pack named a was read already",
            "pack ignored: /etc/frc-spotter/packs/d.yaml:2: unknown key \"beforeShutdown\" in a"
                + " pack; known: journalUnits, pack, probes");
    // A folder named as a pack can't be read as one.
    assertThat(health.problems().get(3))
        .startsWith("pack ignored: /etc/frc-spotter/packs/f.yaml: ");
    assertThat(health.problems()).hasSize(4);
    assertThat(health.probes()).extracting(ProbeResult::id).containsExactly("a.unit", "e.unit");
  }

  @Test
  void theOverridesAreThePortTheControllerAndTheBindAlone() throws IOException {
    fixture.config("{\"port\": 5809, \"controller\": \"10.12.34.3\", \"bind\": \"10.12.34.11\"}");
    Configuration configuration = Configuration.read(fixture.host);
    assertThat(configuration.source()).isEqualTo(AgentConfig.PATH);
    assertThat(configuration.config())
        .isEqualTo(new AgentConfig(5809, "10.12.34.3", "10.12.34.11"));
    assertThat(fixture.agent().controller().address()).isEqualTo("10.12.34.3");

    // An agent.json from before 0.3.0 says what it may set, and is ignored.
    fixture.config("{\"name\": \"vision-front\", \"packs\": [\"vision\"], \"port\": 5809}");
    Health old = fixture.agent().health();
    assertThat(old.problems())
        .containsExactly(
            "configuration ignored: /etc/frc-spotter/agent.json: it may set port, controller, and"
                + " bind only, not name, packs (packs are files in /etc/frc-spotter/packs/)");
    assertThat(Configuration.read(fixture.host).config()).isEqualTo(AgentConfig.DEFAULT);
    for (String bad :
        List.of(
            "{\"port\": 80}",
            "{\"controller\": \"robot.local\"}",
            "{\"bind\": \"::\"}",
            "[]",
            "{")) {
      fixture.config(bad);
      assertThat(Configuration.read(fixture.host).problems())
          .as(bad)
          .singleElement()
          .asString()
          .startsWith("configuration ignored: /etc/frc-spotter/agent.json: ");
    }
  }

  @Test
  void theControllerIsTenTeamTwoOnItsOwnNetwork() throws IOException {
    assertThat(fixture.agent().controller())
        .isEqualTo(new Agent.Controller("10.12.34.2", "the robot controller on its own network"));
    // Its address changes (a cable moved to another robot): asked again, it follows.
    Agent agent = fixture.agent();
    fixture.addresses.set(0, "10.99.71.13");
    fixture.addresses.add("192.168.1.20");
    assertThat(agent.controller().address()).isEqualTo("10.99.71.2");
  }

  @Test
  void withNoRobotAddressItTakesAShutdownFromNobodyAndSaysSo() throws IOException {
    fixture.addresses.clear();
    fixture.addresses.add("192.168.1.20");
    Health health = fixture.agent().health();
    assertThat(health.problems())
        .containsExactly(
            "shutdown refused: this computer has no 10.TE.AM.x address to find the robot"
                + " controller (10.TE.AM.2) by; name it in /etc/frc-spotter/agent.json if it's"
                + " elsewhere");
    assertThat(fixture.agent().controller().address()).isEmpty();

    fixture.addresses.add("10.12.34.11");
    fixture.addresses.add("10.0.0.5");
    assertThat(fixture.agent().controller().why())
        .isEqualTo(
            "this computer has addresses on more than one 10.x network (10.0.0.x, 10.12.34.x), so"
                + " which robot controller it answers to isn't clear; name it in"
                + " /etc/frc-spotter/agent.json");

    // Named in its overrides, it's taken from there whatever its addresses.
    fixture.config("{\"controller\": \"10.0.0.2\"}");
    assertThat(fixture.agent().health().problems()).isEmpty();
    assertThat(fixture.agent().controller().address()).isEqualTo("10.0.0.2");
  }

  @Test
  void aCameraThatIsntPluggedInFailsAtItsPort() throws IOException {
    fixture.pack("board", BOARD);
    fixture.delete("/dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0");
    Agent agent = fixture.agent();
    agent.probes().runAll();
    ProbeResult right = agent.health().probe("camera.front-right").orElseThrow();
    assertThat(right.status()).isEqualTo(ProbeResult.FAIL);
    assertThat(right.detail()).isEqualTo("nothing at " + Fixture.FRONT_RIGHT);
  }

  @Test
  void aDeviceThatCantBeFollowedIsStillPresent() throws IOException {
    Host host =
        fixture.host(
            fixture.root,
            path -> {
              if (path.endsWith("video0/device")) {
                throw new IOException("unplugged as it was read");
              }
              return Fixture.LINKS.getOrDefault(path, path);
            });
    assertThat(new UsbDevices(host).at(Fixture.FRONT_LEFT))
        .contains(new UsbDevices.Found("", 0, "", "", ""));
    Host gone =
        fixture.host(
            fixture.root,
            path -> {
              throw new IOException("unplugged as it was read");
            });
    assertThat(new UsbDevices(gone).at(Fixture.FRONT_LEFT))
        .contains(new UsbDevices.Found("", 0, "", "", ""));
  }

  @Test
  void theRootIsFoundByItsMountWhenSysfsDoesntName() throws IOException {
    fixture.delete("/sys/dev/block/259:2/uevent");
    assertThat(
            new BootSource(
                    fixture.host,
                    new StampSource(fixture.host),
                    new JournalSource(fixture.host, Duration.ofSeconds(1)))
                .root())
        .isEqualTo(new BootSource.Root("/dev/root", true));
    fixture.write(BootSource.MOUNTS, "23 28 0:21 / /proc rw - proc proc rw\n");
    assertThat(
            new BootSource(
                    fixture.host,
                    new StampSource(fixture.host),
                    new JournalSource(fixture.host, Duration.ofSeconds(1)))
                .root())
        .isEqualTo(new BootSource.Root("", false));
  }

  @Test
  void theMacIsTheWiredInterfacesEvenWhenItsDown() throws IOException {
    fixture.write("/sys/class/net/end0/operstate", "down\n");
    fixture.write("/sys/class/net/wlan0/address", "AA:BB:CC:DD:EE:FF\n");
    assertThat(new StampSource(fixture.host).mac()).isEqualTo("c0:74:2b:fe:12:34");
  }

  @Test
  void itAnswersToItsHostnameInLowercase() throws IOException {
    fixture.write(StampSource.HOSTNAME, "Vision-Front\n");
    assertThat(fixture.agent().names()).containsExactly("vision-front", "vision-front.local");
    fixture.delete(StampSource.HOSTNAME);
    assertThat(fixture.agent().names()).isEmpty();
  }

  @Test
  void memoryThatSaysNothingUsableIsAProblem() throws IOException {
    fixture.write(MemorySource.MEMINFO, "MemTotal: 100 kB\n");
    assertThat(fixture.agent().health().problems())
        .contains("memory: /proc/meminfo has no MemTotal or MemAvailable");
    fixture.write(MemorySource.MEMINFO, "MemTotal: x kB\nMemAvailable: 1 kB\n");
    assertThat(fixture.agent().health().problems())
        .contains("memory: /proc/meminfo: not a number of kB: x");
  }
}
