package com.michaelgrundvig.frc.spotter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Boot;
import com.michaelgrundvig.frc.spotter.api.Cameras;
import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalSummary;
import com.michaelgrundvig.frc.spotter.api.Memory;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.Service;
import com.michaelgrundvig.frc.spotter.api.SettingsState;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client against a stand-in agent on this computer: polled on its own thread, its probes run
 * and its files downloaded when asked, its requirements judged, and powered down.
 */
class AgentClientTest {
  /** Fast polls, for a test: ten a second, missing after half a second. */
  static final ClientSettings FAST = new ClientSettings(0.1, 0.04, 0.05, 0.5, 2, 2);

  static final ProbeResult VERSION =
      new ProbeResult("photonvision.version", "command", ProbeResult.PASS, "v1", "", 5, 400);

  static Health health() {
    return new Health(
        new Stamp(
            "vision-front",
            1234,
            "10.12.34.11",
            "orangepi-5",
            List.of(),
            "r",
            "",
            "v1",
            "",
            "",
            ""),
        new Boot("boot", 10, 10_000_000, true, "/dev/nvme0n1p2", true, ""),
        Cpu.UNKNOWN,
        List.of(),
        new Service("", "", "", "", 0, 0),
        Cameras.UNKNOWN,
        JournalSummary.EMPTY,
        null,
        SettingsState.UNKNOWN,
        List.of(),
        new Memory(8000, 6000),
        List.of(),
        List.of(VERSION));
  }

  final AtomicLong robot = new AtomicLong();
  volatile boolean garbled;
  volatile int shutdownStatus = 202;
  HttpServer server;
  AgentClient client;

  @BeforeEach
  void anAgent() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        AgentApi.HEALTH,
        exchange ->
            send(exchange, 200, garbled ? "{\"stamp\": [" : Json.compact(health().toJson())));
    server.createContext(
        AgentApi.PROBES + "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          if (path.equals(AgentApi.PROBES + "/photonvision.version")) {
            send(exchange, 200, Json.compact(VERSION.toJson()));
          } else {
            send(exchange, 404, "{\"error\":\"no such probe\"}");
          }
        });
    server.createContext(
        AgentApi.DOWNLOADS + "/settings.zip", exchange -> send(exchange, 200, "PK"));
    server.createContext(
        AgentApi.JOURNAL,
        exchange ->
            send(
                exchange,
                200,
                "{\"entries\": [], \"cursor\": \""
                    + exchange.getRequestURI().getRawQuery()
                    + "\"}"));
    server.createContext(AgentApi.SHUTDOWN, exchange -> send(exchange, shutdownStatus, "{}"));
    server.start();
    client =
        new AgentClient(
            "vision-front",
            "127.0.0.1",
            server.getAddress().getPort(),
            closedPort(),
            FAST,
            robot::get);
  }

  @AfterEach
  void stop() {
    client.close();
    server.stop(0);
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      in.readAllBytes();
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static int closedPort() throws IOException {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  @Test
  void itsHealthIsPolledAndJudgedAgainstWhatTheRobotNeeds() throws Exception {
    assertThat(client.missing()).isTrue();
    await(() -> client.latest().answer() != null);
    assertThat(client.name()).isEqualTo("vision-front");
    assertThat(client.missing()).isFalse();
    Health health = java.util.Objects.requireNonNull(client.latest().answer());
    List<Verdict> verdicts =
        Verdict.judge(
            List.of(
                Requirement.equals(
                    "photonvision.version", "v1", Requirement.Level.HIGH, "the version"),
                Requirement.equals(
                    "photonvision.version", "v2", Requirement.Level.HIGH, "the version"),
                Requirement.passes("camera.front-left", Requirement.Level.MEDIUM, "a camera")),
            health);
    assertThat(verdicts).extracting(Verdict::met).containsExactly(true, false, false);
    assertThat(verdicts.get(1).said()).isEqualTo("photonvision.version is \"v1\", not \"v2\"");
    assertThat(verdicts.get(2).said()).contains("isn't defined on this computer");

    // Answers it can't read keep the last, and say why; it goes missing as the robot's time passes.
    garbled = true;
    await(() -> client.latest().error().startsWith("its health can't be read"));
    assertThat(client.latest().answer()).isEqualTo(health);
    robot.addAndGet(1_000_000_000L);
    assertThat(client.ageSeconds()).isGreaterThan(0.5);
    assertThat(client.missing()).isTrue();
  }

  @Test
  void itsProbesRunAndItsFilesDownloadWhenAsked() throws Exception {
    assertThat(client.runProbe("photonvision.version")).isEqualTo(VERSION);
    assertThatThrownBy(() -> client.runProbe("none"))
        .isInstanceOf(AgentHttp.Answered.class)
        .hasMessageContaining("404");
    AgentHttp.Streamed zip = client.download("settings.zip");
    try (InputStream in = zip.body()) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("PK");
    }
    assertThat(client.journal("priority=3").cursor()).isEqualTo("priority=3");
  }

  @Test
  void poweredDownItsGoneFromBothPorts() throws Exception {
    client.powerDown();
    await(() -> client.powerDownState().accepted());
    // Its agent's port still answers (the stand-in doesn't go away): not gone.
    assertThat(client.powerDownState().gone()).isFalse();
    client.stopPowerDown();
    shutdownStatus = 403;
    client.powerDown();
    await(() -> client.powerDownState().answer().startsWith("refused"));
  }

  @Test
  void settingsThatCantWorkAreRefused() {
    assertThatThrownBy(() -> new ClientSettings(1, 0.6, 0.6, 3, 10, 30))
        .hasMessageContaining("must fit in pollPeriodSeconds");
    assertThatThrownBy(() -> new ClientSettings(1, 0.25, 0.5, 1, 10, 30))
        .hasMessageContaining("staleAfterSeconds must be longer");
    assertThatThrownBy(() -> new ClientSettings(-1, 0.25, 0.5, 3, 10, 30))
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> Requirement.passes("", Requirement.Level.LOW, "x"))
        .hasMessageContaining("names a probe");
  }

  @Test
  void aVerdictSaysWhyAProbeIsntMet() {
    Requirement needed = Requirement.passes("p", Requirement.Level.LOW, "p");
    assertThat(
            Verdict.judge(needed, java.util.Optional.of(ProbeResult.pending("p", "unit"))).said())
        .isEqualTo("p hasn't run yet");
    assertThat(
            Verdict.judge(
                    needed,
                    java.util.Optional.of(
                        new ProbeResult("p", "unit", ProbeResult.ERROR, "", "timed out", 1, 1)))
                .said())
        .isEqualTo("p couldn't run: timed out");
    assertThat(
            Verdict.judge(
                    needed,
                    java.util.Optional.of(
                        new ProbeResult("p", "unit", ProbeResult.FAIL, "", "inactive", 1, 1)))
                .said())
        .isEqualTo("p failed: inactive");
  }
}
