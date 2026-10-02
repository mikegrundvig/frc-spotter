package com.michaelgrundvig.frc.spotter.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The agent's answers read back as the robot reads them, and fit what the API promises. */
class ApiRecordsTest {
  static final Stamp STAMP =
      new Stamp(
          "vision-front",
          List.of("10.12.34.11", "fd00::11"),
          "c0:74:2b:fe:12:34",
          "3c1e6a2e-6f6c-4a1d-9a53-8c1f0c7b8e21",
          1234.5,
          Map.of(
              "ID", "debian",
              "VERSION_ID", "13",
              "IMAGE_ID", "vision-orangepi",
              "IMAGE_VERSION", "2027.1",
              "PADDOCK_PHOTONVISION_VERSION", "v2027.1.0"));

  static Health health() {
    JournalEntry usb =
        new JournalEntry(
            "s=abc;i=1f2",
            1_790_000_000_000_000L,
            12_345_678,
            STAMP.bootId(),
            6,
            "",
            "kernel",
            "usb 7-1: USB disconnect, device number 2",
            "usb");
    return new Health(
        STAMP,
        new Boot(STAMP.bootId(), 1234.5, 1_234_500_000L, true, "/dev/nvme0n1p2", true),
        new Cpu(
            1.0,
            List.of(3.0, 5.5, 2.0, 4.0, 97.5, 99.0, 98.2, 96.1),
            List.of(
                new CpuCluster(List.of(0, 1, 2, 3), 1008, 1800, 1800),
                new CpuCluster(List.of(4, 5), 2256, 2256, 2400),
                new CpuCluster(List.of(6, 7), 2400, 2400, 2400))),
        List.of(
            new ThermalZone(
                "soc-thermal",
                61.2,
                List.of(new TripPoint("passive", 85), new TripPoint("critical", 115))),
            new ThermalZone("bigcore0-thermal", 72.8, List.of(new TripPoint("passive", 85))),
            new ThermalZone("bigcore1-thermal", 71.9, List.of(new TripPoint("passive", 85))),
            new ThermalZone("littlecore-thermal", 58.1, List.of(new TripPoint("passive", 85))),
            new ThermalZone("center-thermal", 60.0, List.of(new TripPoint("passive", 85))),
            new ThermalZone("gpu-thermal", 57.3, List.of(new TripPoint("passive", 85))),
            new ThermalZone("npu-thermal", 57.8, List.of(new TripPoint("passive", 85)))),
        new JournalSummary(Map.of("usb", 1, "uvc", 0, "error", 2), List.of(usb)),
        new Drive("/dev/nvme0", 41, 1, 23, 140, 75, 0, 0),
        List.of("journal: journalctl took longer than 2 s"),
        new Memory(7_928, 5_120),
        List.of(new Disk("/", 3_900, 1_200, true), new Disk("/data", 8_100, 7_600, false)),
        List.of(
            new ProbeResult(
                "board.thermal-margin",
                "threshold",
                ProbeResult.PASS,
                "12.2",
                "",
                1_234_400_000L,
                0.1),
            new ProbeResult(
                "vision.front-right",
                "command",
                ProbeResult.FAIL,
                "",
                "exit 1: missing /dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0",
                1_234_000_000L,
                412)),
        List.of(new NetworkLink("end0", true, "up", 1000, 4, 0, 2)),
        List.of(
            new UsbDevice("7-1", true, "0c45", "6366", "Arducam OV9281 USB Camera", 5000, 1),
            new UsbDevice("3-1", false, "", "", "", 0, 2)),
        new ClockSync(true, "chrony", 0.4125));
  }

  @Test
  void aStampRoundTrips() {
    assertThat(Stamp.parse(Json.compact(STAMP.toJson()))).isEqualTo(STAMP);
    assertThat(Stamp.parse("{}")).isEqualTo(Stamp.NONE);
  }

  @Test
  void aStampCarriesOsReleaseAsItIs() {
    Stamp read =
        Stamp.parse(
            "{\"hostname\":\"vision-front\",\"osRelease\":{\"IMAGE_ID\":\"x\",\"ID\":\"debian\"},"
                + "\"extra\":1}");
    assertThat(read.hostname()).isEqualTo("vision-front");
    assertThat(read.bootId()).isEmpty();
    assertThat(read.addresses()).isEmpty();
    assertThat(read.uptimeSeconds()).isNaN();
    assertThat(read.osRelease("IMAGE_ID")).isEqualTo("x");
    assertThat(read.osRelease("NOTHING")).isEmpty();
    // Its keys are kept sorted, so the stamp's JSON is the same however they were given.
    assertThat(read.osRelease().keySet()).containsExactly("ID", "IMAGE_ID");
  }

  @Test
  void healthRoundTripsAndIsAFewKilobytes() {
    Health health = health();
    String json = Json.compact(health.toJson());
    assertThat(Health.parse(json)).isEqualTo(health);
    // Each probe adds about 150 bytes: a computer's 64 at most keep it well under the robot's
    // 64 KB.
    assertThat(json.getBytes(StandardCharsets.UTF_8).length).isBetween(1500, 4500);
  }

  @Test
  void probesAreFoundByIdAndTheirTextIsBounded() {
    Health health = health();
    assertThat(health.probe("board.thermal-margin").orElseThrow().passed()).isTrue();
    assertThat(health.probe("vision.front-right").orElseThrow().passed()).isFalse();
    assertThat(health.probe("none")).isEmpty();
    assertThat(health.memory().availablePercent())
        .isCloseTo(64.6, org.assertj.core.data.Offset.offset(0.1));
    assertThat(Memory.UNKNOWN.availablePercent()).isNaN();
    assertThat(health.disks().get(1).freePercent())
        .isCloseTo(93.8, org.assertj.core.data.Offset.offset(0.1));
    assertThat(new Disk("/x", 0, 0, false).freePercent()).isNaN();
    ProbeResult longOne =
        new ProbeResult("x", "command", ProbeResult.PASS, "v".repeat(1000), "", 0, 0);
    assertThat(longOne.value()).hasSize(ProbeResult.MAX_TEXT).endsWith("…");
    assertThat(ProbeResult.pending("y", "unit").status()).isEqualTo(ProbeResult.PENDING);
  }

  @Test
  void theLinkUsbAndClockReadBack() {
    Health health = health();
    assertThat(health.network().get(0).up()).isTrue();
    assertThat(new NetworkLink("eth0", false, "down", -1, 0, 0, 0).up()).isFalse();
    assertThat(health.usb().get(1).present()).isFalse();
    assertThat(health.clock().synced()).isTrue();
    assertThat(NetworkLink.fromJson(Json.parse("{}")))
        .isEqualTo(new NetworkLink("", false, "unknown", -1, 0, 0, 0));
    assertThat(UsbDevice.fromJson(Json.parse("{}")))
        .isEqualTo(new UsbDevice("", false, "", "", "", 0, 0));
    assertThat(ClockSync.fromJson(Json.parse("{}"))).isEqualTo(ClockSync.UNKNOWN);
  }

  @Test
  void healthWithoutADriveOrAnythingElseStillReads() {
    Health empty = Health.parse("{\"drive\":null}");
    assertThat(empty.drive()).isNull();
    assertThat(empty.stamp()).isEqualTo(Stamp.NONE);
    assertThat(empty.cpu()).isEqualTo(Cpu.UNKNOWN);
    assertThat(empty.journal()).isEqualTo(JournalSummary.EMPTY);
    assertThat(empty.boot().lastShutdownClean()).isNull();
    assertThat(empty.hottest()).isEmpty();
    assertThat(empty.network()).isEmpty();
    assertThat(empty.usb()).isEmpty();
    assertThat(empty.clock()).isEqualTo(ClockSync.UNKNOWN);
    Health same = Health.parse(Json.compact(empty.toJson()));
    assertThat(same).isEqualTo(empty);
  }

  @Test
  void theHelpersAnswerWhatTheRobotAsks() {
    Health health = health();
    assertThat(health.hottest().orElseThrow().type()).isEqualTo("bigcore0-thermal");
    assertThat(health.thermal().get(0).firstPassive().orElseThrow().celsius()).isEqualTo(85);
    assertThat(new ThermalZone("x", 1, List.of()).firstPassive()).isEmpty();
    assertThat(health.cpu().bigClusters()).hasSize(2);
    assertThat(health.cpu().bigCoresCapped()).isTrue();
    assertThat(health.journal().count("usb")).isEqualTo(1);
    assertThat(health.journal().count("oom")).isZero();
  }

  @Test
  void aJournalPageRoundTrips() {
    JournalPage page = new JournalPage(health().journal().latest(), "s=abc;i=1f2", true);
    assertThat(JournalPage.parse(Json.compact(page.toJson()))).isEqualTo(page);
    JournalEntry entry = page.entries().get(0);
    assertThat(JournalEntry.parse(Json.compact(entry.toJson()))).isEqualTo(entry);
    assertThat(JournalPage.parse("{}").entries()).isEmpty();
  }

  @Test
  void theJournalIsServedForTheKernelAndTheAgentAndTheirPacksUnitsOnly() {
    // A computer's packs add their own (ProbeSet.journalUnits): the units they watch.
    assertThat(AgentApi.JOURNAL_UNITS).containsExactly("kernel", "frc-spotter.service");
  }

  @Test
  void aShutdownAnswerRoundTrips() {
    ShutdownAnswer answer = new ShutdownAnswer(true);
    assertThat(Json.compact(answer.toJson()))
        .isEqualTo("{\"shuttingDown\":true,\"alreadyRequested\":true}");
    assertThat(ShutdownAnswer.parse(Json.compact(answer.toJson()))).isEqualTo(answer);
  }

  @Test
  void numbersThatArentNumbersAreSentAsNull() {
    JsonValue json = new ThermalZone("broken", Double.NaN, List.of()).toJson();
    assertThat(Json.compact(json)).contains("\"celsius\":null");
    assertThat(ThermalZone.fromJson(json).celsius()).isNaN();
  }
}
