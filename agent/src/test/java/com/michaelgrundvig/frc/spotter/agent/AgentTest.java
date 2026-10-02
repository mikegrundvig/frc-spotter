package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.CpuCluster;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.ExpectedCamera;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.api.TripPoint;
import com.michaelgrundvig.frc.spotter.api.UsbCamera;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.settings.PhotonVisionDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent's health of an RK3588 coprocessor, read from a fixture tree. */
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
  void healthReadsEverythingAndIsAboutTwoKilobytes() throws SQLException {
    Health health = fixture.agent().health();

    assertThat(health.problems()).isEmpty();
    assertThat(health.stamp().name()).isEqualTo("vision-front");
    assertThat(health.stamp().bootId()).isEqualTo("3c1e6a2e-6f6c-4a1d-9a53-8c1f0c7b8e21");
    assertThat(health.stamp().mac()).isEqualTo("c0:74:2b:fe:12:34");

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

    assertThat(health.photonvision().running()).isTrue();
    assertThat(health.photonvision().restarts()).isEqualTo(1);
    assertThat(health.photonvision().activeSinceMicros()).isEqualTo(9_000_000);

    assertThat(health.cameras().present()).hasSize(2);
    assertThat(health.cameras().present().get(1).speedMbps()).isEqualTo(5000);
    assertThat(health.cameras().present().get(1).port()).isEqualTo("7-1");
    assertThat(health.cameras().present().get(0).speedMbps()).isEqualTo(480);
    // front-left is where its settings expect it; front-right's settings name another port.
    assertThat(health.cameras().expected())
        .containsExactly(
            new ExpectedCamera(
                "front-left",
                "/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0",
                true),
            new ExpectedCamera(
                "front-right",
                "/dev/v4l/by-path/platform-xhci-hcd.1.auto-usb-0:1:1.0-video-index0",
                false));

    assertThat(health.journal().counts())
        .containsEntry("usb", 1)
        .containsEntry("uvc", 1)
        .containsEntry("filesystem", 1)
        .containsEntry("oom", 1)
        .containsEntry("thermal", 1)
        .containsEntry("error", 1);
    assertThat(health.journal().latest()).hasSize(3);
    assertThat(health.journal().latest().get(2).message()).isEqualTo("Error é");
    assertThat(health.journal().latest().get(2).bootId()).isEmpty();

    Drive drive = Objects.requireNonNull(health.drive());
    assertThat(drive.device()).isEqualTo("/dev/nvme0 (Samsung SSD 980 250GB)");
    assertThat(drive.celsius()).isEqualTo(40.9);
    assertThat(drive.percentUsed()).isEqualTo(1);
    assertThat(drive.unsafeShutdowns()).isEqualTo(23);

    String liveHash;
    try (Connection connection = PhotonVisionDatabase.open(fixture.host.path(Fixture.DATABASE))) {
      liveHash = SettingsDatabase.read(connection).hash();
    }
    assertThat(health.settings().liveHash()).isEqualTo(liveHash);
    assertThat(health.settings().stampedHash()).isEmpty();
    assertThat(health.settings().matches()).isFalse();

    assertThat(Json.compact(health.toJson()).getBytes(StandardCharsets.UTF_8).length)
        .isBetween(2000, 4000);
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
        List.of("systemctl", "show"), new Commands.Output(-1, List.of(), false, true));
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"), new Commands.Output(1, List.of(), false, false));
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), new Commands.Output(1, List.of(), false, false));
    fixture.delete("/run/coprocessor/nvme-smart-log.json");
    fixture.delete("/etc/coprocessor/stamp.json");
    fixture.write("/run/coprocessor/nvme-smart-log.json", "{broken");
    fixture.delete(Fixture.DATABASE);

    Agent agent = fixture.agent();
    Health health = agent.health();
    assertThat(health.problems())
        .containsExactly(
            "photonvision: systemctl show photonvision.service timed out",
            "journal: journalctl failed",
            "drive: line 1, column 2: expected a member's name in quotes");
    assertThat(health.boot().lastShutdownClean()).isNull();
    assertThat(health.stamp().name()).isEmpty();
    assertThat(health.settings().liveHash()).isEmpty();
    assertThat(health.drive()).isNull();
    assertThat(fixture.log).hasSize(3);

    // The same problems again a second later: logged once, still reported.
    fixture.micros.addAndGet(1_000_000);
    assertThat(agent.health().problems()).hasSize(3);
    assertThat(fixture.log).hasSize(3);
  }

  @Test
  void aComputerWithoutCamerasOrADriveHasNone() throws IOException {
    fixture.delete("/run/coprocessor/nvme-smart-log.json");
    fixture.write("/run/coprocessor/nvme-id-ctrl.json", "");
    for (String name : fixture.host.list(CameraSource.BY_PATH)) {
      fixture.delete(CameraSource.BY_PATH + "/" + name);
    }
    Health health = fixture.agent().health();
    assertThat(health.drive()).isNull();
    assertThat(health.cameras().present()).isEmpty();
    assertThat(health.cameras().missing()).hasSize(2);
  }

  @Test
  void aCameraWhoseDeviceCantBeFollowedIsStillPresent() throws IOException {
    Host host =
        fixture.host(
            fixture.root,
            path -> {
              if (path.endsWith("video0/device")) {
                throw new IOException("unplugged as it was read");
              }
              return Fixture.LINKS.getOrDefault(path, path);
            });
    List<UsbCamera> present = new CameraSource(host).present();
    assertThat(present).hasSize(2);
    assertThat(present.get(1))
        .isEqualTo(
            new UsbCamera(
                "/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0",
                "",
                0,
                "",
                "",
                ""));
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
}
