package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.AgentHttp;
import com.michaelgrundvig.frc.spotter.client.ClientSettings;
import com.michaelgrundvig.frc.spotter.client.PowerDowner;
import com.michaelgrundvig.frc.spotter.layout.LayoutFingerprint;
import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * PhotonVision's pack against the real PhotonVision (the pinned build, {@code
 * harness/photonvision-x86.json}), with no cameras: its unit, its page, its version from its jar,
 * its settings' hash steady across restarts and equal to the robot's own hash of the settings it
 * downloads, its AprilTag layout's fingerprint before and after a layout is uploaded (and how long
 * PhotonVision's restart for it takes), and soft-off with the pack's step stopping PhotonVision
 * first. In order: each step changes what the next one sees.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PhotonVisionContainerTest {
  Network network;
  Coprocessor coprocessor;
  AgentClient client;
  String robot = "";
  double startedSeconds;

  @BeforeAll
  void photonVision() throws Exception {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(Images.photonVision(), network, 11);
    long starting = System.nanoTime();
    coprocessor.start();
    robot = coprocessor.robotAddress();
    coprocessor.configure(
        new AgentConfig(
            "vision-front", robot, 5808, List.of("photonvision"), List.of(), List.of()));
    client =
        new AgentClient(
            "vision-front",
            coprocessor.agentHost(),
            coprocessor.agentPort(),
            coprocessor.softwarePort(),
            ClientSettings.DEFAULTS,
            System::nanoTime);
    for (int i = 0; i < 1800 && !answers(); i++) {
      Thread.sleep(100);
    }
    assertThat(answers()).as("PhotonVision's page answers within 3 minutes").isTrue();
    startedSeconds = (System.nanoTime() - starting) / 1e9;
    awaitProbe("photonvision.http", ProbeResult::passed, 10);
    System.out.printf("PhotonVision answered %.1f s after its container started%n", startedSeconds);
  }

  @AfterAll
  void stop() {
    if (client != null) {
      client.close();
    }
    for (AutoCloseable each : new AutoCloseable[] {coprocessor, network}) {
      try {
        if (each != null) {
          each.close();
        }
      } catch (Exception e) {
        // stopped anyway
      }
    }
  }

  /** Asks for a probe until what it found is what's wanted, or the time's up: its last result. */
  ProbeResult awaitProbe(String id, Predicate<ProbeResult> wanted, double seconds)
      throws IOException, InterruptedException {
    long end = System.nanoTime() + Math.round(seconds * 1e9);
    ProbeResult result = client.runProbe(id);
    while (!wanted.test(result) && System.nanoTime() < end) {
      Thread.sleep(500);
      result = client.runProbe(id);
    }
    assertThat(wanted.test(result)).as("%s within %.0f s: %s", id, seconds, result).isTrue();
    return result;
  }

  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /** Whether PhotonVision's page answers now, asked directly (a probe's result may be 2 s old). */
  boolean answers() {
    try {
      return http.send(
                  HttpRequest.newBuilder(software("/api/status"))
                      .timeout(Duration.ofSeconds(2))
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode()
          == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  URI software(String path) {
    return URI.create(
        "http://" + coprocessor.agentHost() + ":" + coprocessor.softwarePort() + path);
  }

  /** PhotonVision's process, as systemd knows it. */
  String mainPid() {
    return coprocessor
        .run("systemctl", "show", "photonvision.service", "--property=MainPID", "--value")
        .strip();
  }

  /**
   * Waits until PhotonVision runs as a process other than {@code before} and its page answers;
   * answers how long that took from {@code since} (System.nanoTime).
   */
  double awaitRestarted(String before, long since, double seconds) throws InterruptedException {
    long end = since + Math.round(seconds * 1e9);
    while (System.nanoTime() < end) {
      String now = mainPid();
      if (!now.equals(before) && !now.equals("0") && answers()) {
        return (System.nanoTime() - since) / 1e9;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("PhotonVision didn't restart and answer within " + seconds + " s");
  }

  /** Restarts PhotonVision's unit and answers how long until its page answers again. */
  double restartPhotonVision() throws Exception {
    String before = mainPid();
    long asked = System.nanoTime();
    coprocessor.run("systemctl", "restart", "photonvision.service");
    return awaitRestarted(before, asked, 120);
  }

  byte[] download(String name) throws IOException {
    AgentHttp.Streamed streamed = client.download(name);
    try (InputStream body = streamed.body()) {
      return body.readAllBytes();
    }
  }

  @Test
  @Order(1)
  void itsUnitAndVersionAreWhatTheImageInstalled() throws Exception {
    ProbeResult unit = awaitProbe("photonvision.unit", ProbeResult::passed, 10);
    assertThat(unit.value()).startsWith("active/running");
    ProbeResult version = awaitProbe("photonvision.version", ProbeResult::passed, 30);
    System.out.printf(
        "PhotonVision's version, from its jar: %s (%.0f ms)%n",
        version.value(), version.durationMillis());
    assertThat(version.value()).isEqualTo(Images.PhotonVisionJar.version());
  }

  @Test
  @Order(2)
  void itsSettingsHashIsSteadyAcrossRestartsAndIsTheRobotsOwn(@TempDir Path folder)
      throws Exception {
    // PhotonVision writes its database at its first start; give it a moment to settle.
    ProbeResult first = awaitProbe("photonvision.settings-hash", ProbeResult::passed, 60);
    Thread.sleep(5_000);
    first = awaitProbe("photonvision.settings-hash", ProbeResult::passed, 10);
    System.out.printf(
        "Settings hash after its first start: %s (%.0f ms)%n",
        first.value(), first.durationMillis());
    for (int restart = 1; restart <= 2; restart++) {
      double seconds = restartPhotonVision();
      Thread.sleep(5_000);
      ProbeResult again = awaitProbe("photonvision.settings-hash", ProbeResult::passed, 10);
      System.out.printf(
          "  after restart %d (answered in %.1f s): %s%n", restart, seconds, again.value());
      assertThat(again.value()).as("the hash after restart %d", restart).isEqualTo(first.value());
    }

    // The robot's side: the settings it downloads, hashed by the robot's own code, as JSON and as
    // the repository's files from the zip.
    String json = new String(download("settings.json"), StandardCharsets.UTF_8);
    Settings fromJson = Settings.parse(json);
    assertThat(fromJson.hash()).isEqualTo(first.value());
    byte[] zip = download("settings.zip");
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        Path file = folder.resolve(entry.getName());
        Files.createDirectories(file.getParent());
        Files.write(file, in.readAllBytes());
      }
    }
    Settings fromZip =
        SettingsFiles.read(folder.resolve(SettingsFiles.folder("vision-front"))).orElseThrow();
    assertThat(fromZip.hash()).isEqualTo(first.value());
    System.out.printf(
        "The robot's hash of settings.json (%d bytes, %d rows) and settings.zip (%d bytes): equal%n",
        json.length(), fromJson.rows().size(), zip.length);
  }

  /**
   * A layout of three tags, one turned to face back down the field (a yaw of 180°, where -180° and
   * 180° must agree), one tilted, written as WPILib writes it.
   */
  static final String LAYOUT =
      """
      {"tags": [
        {"ID": 3, "pose": {"translation": {"x": 1.25, "y": 2.5, "z": 0.75},
          "rotation": {"quaternion": {"W": 0.0, "X": 0.0, "Y": 0.0, "Z": 1.0}}}},
        {"ID": 1, "pose": {"translation": {"x": 0.5, "y": 0.25, "z": 1.0},
          "rotation": {"quaternion": {"W": 1.0, "X": 0.0, "Y": 0.0, "Z": 0.0}}}},
        {"ID": 7, "pose": {"translation": {"x": 4.0001, "y": 3.0, "z": 0.5},
          "rotation": {"quaternion": {"W": 0.9659258262890683, "X": 0.0, "Y": -0.25881904510252074, "Z": 0.0}}}}
      ],
      "field": {"length": 8.0, "width": 4.0}}
      """;

  @Test
  @Order(3)
  void anUploadedLayoutShowsInItsFingerprintAfterItsRestart() throws Exception {
    ProbeResult layoutBefore = client.runProbe("photonvision.layout");
    System.out.printf(
        "Layout before: %s %s%s%n",
        layoutBefore.status(),
        layoutBefore.value(),
        layoutBefore.detail().isEmpty() ? "" : " (" + layoutBefore.detail() + ")");
    String expected = LayoutFingerprint.ofJson(LAYOUT);
    assertThat(layoutBefore.value()).isNotEqualTo(expected);

    String boundary = "spotter" + System.nanoTime();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"data\"; filename=\"layout.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(LAYOUT.getBytes(StandardCharsets.UTF_8));
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    String before = mainPid();
    long uploaded = System.nanoTime();
    HttpResponse<String> answer =
        http.send(
            HttpRequest.newBuilder(software("/api/settings/aprilTagFieldLayout"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    System.out.printf("Upload answered %d: %s%n", answer.statusCode(), answer.body());
    assertThat(answer.statusCode()).isEqualTo(200);

    // It restarts itself to load it: a new process, answering.
    double restarted = awaitRestarted(before, uploaded, 120);
    ProbeResult after = awaitProbe("photonvision.layout", r -> expected.equals(r.value()), 60);
    System.out.printf(
        "PhotonVision restarted and answering %.1f s after the upload; its layout's fingerprint"
            + " the robot's: %s%n",
        restarted, after.value());
    ProbeResult hash = awaitProbe("photonvision.settings-hash", ProbeResult::passed, 10);
    System.out.printf("  its settings hash now %s%n", hash.value());
  }

  @Test
  @Order(4)
  void itsMemory() {
    long[] container = coprocessor.memoryMb();
    String photonVision =
        coprocessor
            .run("systemctl", "show", "photonvision.service", "--property=MemoryCurrent", "--value")
            .strip();
    System.out.printf(
        "Memory: the container %d MiB now (%d MiB at most); the agent's unit %d MiB;"
            + " PhotonVision's %s MiB%n",
        container[0],
        container[1],
        coprocessor.agentMemoryMb(),
        photonVision.matches("\\d+") ? Long.toString(Long.parseLong(photonVision) >> 20) : "?");
  }

  @Test
  @Order(5)
  void softOffStopsPhotonVisionFirstThenPowersOff() throws Exception {
    long asked = System.nanoTime();
    client.powerDown();
    PowerDowner.State state = client.powerDownState();
    for (int i = 0; i < 1200 && !state.gone(); i++) {
      Thread.sleep(100);
      state = client.powerDownState();
    }
    System.out.printf(
        "Soft-off with PhotonVision's step: gone from both ports %.1f s after it was asked%n",
        (System.nanoTime() - asked) / 1e9);
    assertThat(state.accepted()).as("%s", state).isTrue();
    assertThat(state.gone()).as("%s", state).isTrue();
    for (int i = 0; i < 300 && coprocessor.isRunning(); i++) {
      Thread.sleep(100);
    }
    assertThat(coprocessor.isRunning()).isFalse();
    System.out.printf(
        "The container exited with %s%n",
        coprocessor.getCurrentContainerInfo().getState().getExitCodeLong());
  }
}
