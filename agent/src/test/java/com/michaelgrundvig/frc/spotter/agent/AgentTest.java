package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.CpuCluster;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.Memory;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.api.TripPoint;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's health of an RK3588 coprocessor, read from a fixture tree: the computer's own, which
 * knows nothing of the software it runs, and the built-in pack's probes on it.
 */
class AgentTest {
  @TempDir Path dir;
  Fixture fixture;

  @BeforeEach
  void aCoprocessor() throws IOException {
    fixture = new Fixture(dir);
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"), Fixture.lines("journal-this-boot.json"));
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), Fixture.lines("journal-previous-clean.json"));
  }

  @Test
  void healthReadsTheComputerAndIsAFewKilobytes() {
    Agent agent = fixture.agent();
    Health health = agent.health();

    assertThat(health.problems()).isEmpty();
    assertThat(health.stamp().name()).isEqualTo("vision-front");
    assertThat(health.stamp().bootId()).isEqualTo("3c1e6a2e-6f6c-4a1d-9a53-8c1f0c7b8e21");
    assertThat(health.stamp().mac()).isEqualTo("c0:74:2b:fe:12:34");
    assertThat(health.stamp().probesHash()).isEqualTo(agent.probeSet().hash());

    assertThat(health.boot().uptimeSeconds()).isEqualTo(1234.56);
    assertThat(health.boot().monotonicMicros()).isEqualTo(1_234_560_000L);
    assertThat(health.boot().rootDevice()).isEqualTo("/dev/nvme0n1p2");
    assertThat(health.boot().rootReadOnly()).isTrue();
    assertThat(health.boot().lastShutdownClean()).isTrue();
    assertThat(health.boot().bootloader()).startsWith("U-Boot 2017.09");

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

    // The built-in pack's probes, not yet run.
    assertThat(health.probes())
        .extracting(ProbeResult::id)
        .containsExactly(
            "camera.front-left",
            "camera.front-right",
            "builtin.thermal-margin",
            "builtin.cpu-capped",
            "builtin.memory",
            "builtin.data-free",
            "builtin.root-read-only");
    assertThat(health.probes()).allMatch(probe -> probe.status().equals(ProbeResult.PENDING));

    assertThat(Json.compact(health.toJson()).getBytes(StandardCharsets.UTF_8).length)
        .isBetween(2500, 5000);
  }

  @Test
  void theBuiltInPackHoldsTheComputersMeasurementsAndCamerasToTheirLimits() {
    Agent agent = fixture.agent();
    agent.probes().runAll();
    fixture.micros.addAndGet(1_000_000);
    Health health = agent.health();
    assertThat(result(health, "camera.front-left"))
        .isEqualTo(pass("7-1 5000 Mb/s Arducam OV9281 USB Camera"));
    assertThat(result(health, "camera.front-right"))
        .isEqualTo(pass("3-1 480 Mb/s Arducam OV9281 USB Camera"));
    assertThat(result(health, "builtin.thermal-margin")).isEqualTo(pass("12.2"));
    // Its big cores are held to 2016 of 2400 MHz.
    ProbeResult capped = result(health, "builtin.cpu-capped");
    assertThat(capped.status()).isEqualTo(ProbeResult.FAIL);
    assertThat(capped.detail()).isEqualTo("cpu.capped is 1, above 0");
    assertThat(result(health, "builtin.memory")).isEqualTo(pass("64.3"));
    ProbeResult data = result(health, "builtin.data-free");
    assertThat(data.status()).isEqualTo(ProbeResult.ERROR);
    assertThat(data.detail()).isEqualTo("disk.data.free.percent isn't measured on this computer");
    assertThat(result(health, "builtin.root-read-only")).isEqualTo(pass("1"));
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
    fixture.delete("/run/coprocessor/nvme-smart-log.json");
    fixture.delete("/etc/coprocessor/stamp.json");
    fixture.delete(MemorySource.MEMINFO);
    fixture.write("/run/coprocessor/nvme-smart-log.json", "{broken");

    Agent agent = fixture.agent();
    Health health = agent.health();
    assertThat(health.problems())
        .containsExactly(
            "memory: /proc/meminfo isn't there",
            "journal: journalctl failed",
            "drive: line 1, column 2: expected a member's name in quotes");
    assertThat(health.boot().lastShutdownClean()).isNull();
    // Without its stamp, it's named from its configuration.
    assertThat(health.stamp().name()).isEqualTo("vision-front");
    assertThat(health.stamp().team()).isZero();
    assertThat(health.memory()).isEqualTo(Memory.UNKNOWN);
    assertThat(health.drive()).isNull();
    assertThat(fixture.log).hasSize(3);

    // The same problems again a second later: logged once, still reported.
    fixture.micros.addAndGet(1_000_000);
    assertThat(agent.health().problems()).hasSize(3);
    assertThat(fixture.log).hasSize(3);
  }

  @Test
  void aConfigurationOrPackThatCantBeReadIsAProblemAndTheAgentRunsOn() throws IOException {
    fixture.write(AgentConfig.PATH, "{\"name\": \"Not A Hostname\"}");
    Health broken = fixture.agent().health();
    assertThat(broken.problems())
        .singleElement()
        .asString()
        .startsWith("configuration: /etc/frc-coprocessor/agent.json: name \"Not A Hostname\"");
    // It runs the built-in pack, with no cameras it knows of.
    assertThat(broken.probes()).extracting(ProbeResult::id).doesNotContain("camera.front-left");

    fixture.config(
        new AgentConfig("vision-front", "", 5808, List.of("missing"), List.of(), List.of()));
    assertThat(fixture.agent().health().problems())
        .containsExactly(
            "pack missing: not installed (no /usr/lib/frc-coprocessor/packs/missing/pack.json)");

    // Configured on /data when the root is read-only.
    fixture.delete(AgentConfig.PATH);
    fixture.write(
        AgentConfig.DATA_PATH,
        new AgentConfig("vision-back", "10.12.34.2", 5809, List.of(), List.of(), List.of()).text());
    Configuration configuration = Configuration.read(fixture.host);
    assertThat(configuration.source()).isEqualTo(AgentConfig.DATA_PATH);
    assertThat(configuration.config().port()).isEqualTo(5809);
  }

  @Test
  void aCameraThatIsntPluggedInFailsAtItsPort() throws IOException {
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
  void memoryThatSaysNothingUsableIsAProblem() throws IOException {
    fixture.write(MemorySource.MEMINFO, "MemTotal: 100 kB\n");
    assertThat(fixture.agent().health().problems())
        .contains("memory: /proc/meminfo has no MemTotal or MemAvailable");
    fixture.write(MemorySource.MEMINFO, "MemTotal: x kB\nMemAvailable: 1 kB\n");
    assertThat(fixture.agent().health().problems())
        .contains("memory: /proc/meminfo: not a number of kB: x");
  }
}
