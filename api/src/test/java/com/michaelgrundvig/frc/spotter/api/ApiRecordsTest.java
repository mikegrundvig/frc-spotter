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
          1234,
          "10.12.34.11",
          "orangepi-5",
          List.of("front-left", "front-right"),
          "coprocessors-2027.1",
          "4f2c9d6e8a1b3c5d7e9f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d",
          "v2027.1.0",
          "9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b",
          "3c1e6a2e-6f6c-4a1d-9a53-8c1f0c7b8e21",
          "c0:74:2b:fe:12:34");

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
        new Boot(STAMP.bootId(), 1234.5, 1_234_500_000L, true, "/dev/nvme0n1p2", true, "2024.10"),
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
        new Service("photonvision.service", "active", "running", "success", 1, 9_000_000),
        new Cameras(
            List.of(
                new ExpectedCamera(
                    "front-left",
                    "/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0",
                    true),
                new ExpectedCamera("front-right", "", false)),
            List.of(
                new UsbCamera(
                    "/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0",
                    "7-1",
                    5000,
                    "0c45",
                    "6366",
                    "Arducam OV9281 USB Camera"))),
        new JournalSummary(Map.of("usb", 1, "uvc", 0, "error", 2), List.of(usb)),
        new Drive("/dev/nvme0", 41, 1, 23, 140, 75, 0, 0),
        new SettingsState(STAMP.settingsHash(), STAMP.settingsHash()),
        List.of("journal: journalctl took longer than 2 s"),
        new Memory(7_928, 5_120),
        List.of(new Disk("/", 3_900, 1_200, true), new Disk("/data", 8_100, 7_600, false)),
        List.of(
            new ProbeResult(
                "builtin.thermal-margin",
                "threshold",
                ProbeResult.PASS,
                "12.2",
                "",
                1_234_400_000L,
                0.1),
            new ProbeResult(
                "photonvision.camera.front-right",
                "command",
                ProbeResult.FAIL,
                "",
                "exit 1: missing /dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0",
                1_234_000_000L,
                412)));
  }

  @Test
  void aStampRoundTrips() {
    assertThat(Stamp.parse(Json.compact(STAMP.toJson()))).isEqualTo(STAMP);
    assertThat(Stamp.parse("{}")).isEqualTo(Stamp.NONE);
  }

  @Test
  void theStampFileLeavesOutWhatTheAgentAdds() {
    Stamp file = Stamp.parse("{\"name\":\"vision-front\",\"cameras\":[\"a\"],\"extra\":1}");
    assertThat(file.bootId()).isEmpty();
    Stamp live = file.withRuntime("boot", "mac");
    assertThat(live.bootId()).isEqualTo("boot");
    assertThat(live.mac()).isEqualTo("mac");
    assertThat(live.cameras()).containsExactly("a");
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
    assertThat(health.probe("builtin.thermal-margin").orElseThrow().passed()).isTrue();
    assertThat(health.probe("photonvision.camera.front-right").orElseThrow().passed()).isFalse();
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
  void aStampCarriesItsProbeDefinitionsHash() {
    Stamp stamped = STAMP.withProbesHash("abc");
    assertThat(stamped.probesHash()).isEqualTo("abc");
    assertThat(Stamp.parse(Json.compact(stamped.toJson()))).isEqualTo(stamped);
    assertThat(STAMP.probesHash()).isEmpty();
  }

  @Test
  void healthWithoutADriveOrAnythingElseStillReads() {
    Health empty = Health.parse("{\"drive\":null}");
    assertThat(empty.drive()).isNull();
    assertThat(empty.stamp()).isEqualTo(Stamp.NONE);
    assertThat(empty.cpu()).isEqualTo(Cpu.UNKNOWN);
    assertThat(empty.cameras()).isEqualTo(Cameras.UNKNOWN);
    assertThat(empty.journal()).isEqualTo(JournalSummary.EMPTY);
    assertThat(empty.settings()).isEqualTo(SettingsState.UNKNOWN);
    assertThat(empty.boot().lastShutdownClean()).isNull();
    assertThat(empty.hottest()).isEmpty();
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
    assertThat(health.photonvision().running()).isTrue();
    assertThat(health.cameras().missing())
        .extracting(ExpectedCamera::name)
        .containsExactly("front-right");
    assertThat(health.journal().count("usb")).isEqualTo(1);
    assertThat(health.journal().count("oom")).isZero();
    assertThat(health.settings().matches()).isTrue();
    assertThat(new SettingsState("", "").matches()).isFalse();
    assertThat(new SettingsState("a", "b").matches()).isFalse();
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
    // A computer's packs add their own (ProbeSet.journalUnits): PhotonVision's adds its unit.
    assertThat(AgentApi.JOURNAL_UNITS).containsExactly("kernel", "frc-coprocessor-agent.service");
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
