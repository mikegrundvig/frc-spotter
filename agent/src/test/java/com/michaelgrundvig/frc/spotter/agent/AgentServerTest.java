package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.ShutdownAnswer;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import com.michaelgrundvig.frc.spotter.table.Pack;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The API over HTTP, as the robot uses it. */
class AgentServerTest {
  @TempDir Path dir;
  Fixture fixture;
  AgentServer server;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** A pack for some vision software: its unit, its journal, a stop before power-off, backups. */
  static final String VISION =
      """
      pack: vision
      journalUnits: [vision.service]
      probes:
        - id: vision.unit
          kind: unit
          unit: vision.service
          every: 2
        - id: vision.front left
          kind: usb
          path: /dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0
      beforeShutdown:
        - name: vision.stop
          argv: [systemctl, stop, vision.service]
          timeout: 120
      downloads:
        - name: settings.json
          argv: ['{pack}/bin/backup', json]
          contentType: application/json; charset=utf-8
          maxBytes: 64
          timeout: 5
        - name: settings.zip
          argv: ['{pack}/bin/backup', zip, '{computer}']
          contentType: application/zip
          maxBytes: 64
          timeout: 5
      """;

  static final String BACKUP = "/usr/lib/frc-coprocessor/packs/vision/bin/backup";

  @BeforeEach
  void anAgent() throws IOException {
    fixture = new Fixture(dir);
    fixture.commands.answer(List.of("journalctl"), Fixture.lines("journal-this-boot.json"));
    fixture.commands.answer(List.of("systemctl", "stop"), List.of());
    fixture.commands.answer(List.of("systemctl", "poweroff"), List.of());
    fixture.commands.answer(List.of(BACKUP, "json"), List.of("{\"hash\":\"abc\"}"));
    fixture.commands.answer(List.of(BACKUP, "zip", "vision-front"), List.of("PK"));
    fixture.pack(Pack.parseYaml(VISION, "vision"));
    fixture.config(withPacks(List.of("vision")));
    serve(fixture.agent());
  }

  static AgentConfig withPacks(List<String> packs) {
    return new AgentConfig(
        Fixture.CONFIG.name(),
        Fixture.CONFIG.controller(),
        Fixture.CONFIG.port(),
        packs,
        Fixture.CONFIG.cameras(),
        List.of());
  }

  private void serve(Agent agent) throws IOException {
    if (server != null) {
      server.close();
    }
    server =
        new AgentServer(
            agent,
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            new RateLimitedLog(
                fixture.host::log, fixture.micros::get, AgentServer.REFUSAL_LOG_MICROS));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpResponse<byte[]> get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri(path)).GET());
  }

  private HttpResponse<byte[]> post(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()));
  }

  private HttpResponse<byte[]> send(HttpRequest.Builder request)
      throws IOException, InterruptedException {
    return client.send(
        request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private static String text(HttpResponse<byte[]> response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  @Test
  void theStampAndHealthAreJson() throws Exception {
    HttpResponse<byte[]> stamp = get(AgentApi.STAMP);
    assertThat(stamp.statusCode()).isEqualTo(200);
    assertThat(stamp.headers().firstValue("Content-Type"))
        .contains("application/json; charset=utf-8");
    assertThat(stamp.headers().firstValue("Cache-Control")).contains("no-store");
    Stamp read = Stamp.parse(text(stamp));
    assertThat(read.name()).isEqualTo("vision-front");
    assertThat(read.mac()).isEqualTo("c0:74:2b:fe:12:34");

    HttpResponse<byte[]> health = get(AgentApi.HEALTH);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(Health.parse(text(health)).thermal()).hasSize(7);
  }

  @Test
  void theJournalIsPagedAndItsQueryChecked() throws Exception {
    HttpResponse<byte[]> page = get(AgentApi.JOURNAL + "?priority=err&unit=vision.service&limit=2");
    assertThat(page.statusCode()).isEqualTo(200);
    JournalPage read = JournalPage.parse(text(page));
    assertThat(read.entries()).hasSize(2);
    assertThat(read.more()).isTrue();
    assertThat(fixture.commands.ran().get(fixture.commands.ran().size() - 1))
        .contains("--priority=3", "_SYSTEMD_UNIT=vision.service", "--lines=2");

    assertThat(get(AgentApi.JOURNAL + "?cursor=" + "s%3Da1%3Bi%3D101&priority=4").statusCode())
        .isEqualTo(200);
    assertThat(fixture.commands.ran().get(fixture.commands.ran().size() - 1))
        .contains("--after-cursor=s=a1;i=101", "--priority=4");

    assertThat(get(AgentApi.JOURNAL + "?before=s%3Da1%3Bi%3D105&limit=2").statusCode())
        .isEqualTo(200);
    assertThat(fixture.commands.ran().get(fixture.commands.ran().size() - 1))
        .contains("--reverse", "--after-cursor=s=a1;i=105");
    assertThat(get(AgentApi.JOURNAL + "?from=boot").statusCode()).isEqualTo(200);
    assertThat(fixture.commands.ran().get(fixture.commands.ran().size() - 1)).contains("--boot");

    for (String bad :
        List.of(
            "?cursor=s%3D1&before=s%3D2",
            "?from=boot&cursor=s%3D1",
            "?from=start",
            "?before=a%20b",
            "?limit=0",
            "?limit=501",
            "?limit=x",
            "?priority=8",
            "?priority=loud",
            "?unit=a%20b",
            "?unit=sshd.service",
            "?cursor=a%20--since",
            "?cursor=%25zz")) {
      HttpResponse<byte[]> refused = get(AgentApi.JOURNAL + bad);
      assertThat(refused.statusCode()).as(bad).isEqualTo(400);
      assertThat(Json.parse(text(refused)).asObject("error").string("error", "")).isNotEmpty();
    }
  }

  @Test
  void aPacksDownloadsAreServedWholeAndVersionOnesSettingsPathsAreTheirAliases() throws Exception {
    HttpResponse<byte[]> json = get(AgentApi.DOWNLOADS + "/settings.json");
    assertThat(json.statusCode()).isEqualTo(200);
    assertThat(text(json)).isEqualTo("{\"hash\":\"abc\"}\n");
    assertThat(json.headers().firstValue("Content-Type"))
        .contains("application/json; charset=utf-8");
    assertThat(text(get(AgentApi.SETTINGS))).isEqualTo(text(json));

    HttpResponse<byte[]> zip = get(AgentApi.SETTINGS_ZIP);
    assertThat(zip.statusCode()).isEqualTo(200);
    assertThat(zip.headers().firstValue("Content-Type")).contains("application/zip");
    assertThat(zip.headers().firstValue("Content-Disposition"))
        .contains("attachment; filename=\"vision-front-settings.zip\"");
    assertThat(text(zip)).isEqualTo("PK\n");

    assertThat(get(AgentApi.DOWNLOADS + "/nothing").statusCode()).isEqualTo(404);
    assertThat(get(AgentApi.DOWNLOADS + "/").statusCode()).isEqualTo(404);
  }

  @Test
  void aDownloadThatFailsOrRunsOverIsAnErrorNeverAFileCutShort() throws Exception {
    fixture.commands.answer(
        List.of(BACKUP, "json"), new Commands.Output(2, List.of("{"), false, false));
    assertThat(get(AgentApi.SETTINGS).statusCode()).isEqualTo(500);
    fixture.commands.answer(List.of(BACKUP, "json"), List.of("x".repeat(100)));
    HttpResponse<byte[]> over = get(AgentApi.SETTINGS);
    assertThat(over.statusCode()).isEqualTo(500);
    assertThat(text(over)).contains("making settings.json failed");
    assertThat(fixture.log())
        .contains(
            "Download settings.json failed: exit 2",
            "Download settings.json failed: larger than 64 bytes");
    // Without a pack that defines them, version one's paths have nothing behind them.
    fixture.config(withPacks(List.of()));
    serve(fixture.agent());
    assertThat(get(AgentApi.SETTINGS_ZIP).statusCode()).isEqualTo(404);
  }

  @Test
  void probesAreListedAndRunWhenAskedAtMostOnceInTwoSeconds() throws Exception {
    HttpResponse<byte[]> listed = get(AgentApi.PROBES);
    assertThat(listed.statusCode()).isEqualTo(200);
    List<ProbeResult> all =
        Json.parse(text(listed)).asObject("probes").list("probes", ProbeResult::fromJson);
    assertThat(all).extracting(ProbeResult::id).contains("camera.front-left", "vision.unit");
    assertThat(all).allMatch(result -> result.status().equals(ProbeResult.PENDING));

    HttpResponse<byte[]> ran = get(AgentApi.PROBES + "/vision.unit");
    assertThat(ran.statusCode()).isEqualTo(200);
    ProbeResult unit = ProbeResult.fromJson(Json.parse(text(ran)));
    assertThat(unit.status()).isEqualTo(ProbeResult.PASS);
    assertThat(unit.value()).isEqualTo("active/running, restarts 1");
    int commands = fixture.commands.ran().size();
    // Asked again at once: its last result, not another run.
    assertThat(ProbeResult.fromJson(Json.parse(text(get(AgentApi.PROBES + "/vision.unit")))))
        .isEqualTo(unit);
    assertThat(fixture.commands.ran()).hasSize(commands);
    fixture.micros.addAndGet(AgentApi.PROBE_RUN_SECONDS * 1_000_000L);
    get(AgentApi.PROBES + "/vision.unit");
    assertThat(fixture.commands.ran()).hasSize(commands + 1);

    // An id with a space, encoded.
    assertThat(
            ProbeResult.fromJson(Json.parse(text(get(AgentApi.PROBES + "/vision.front%20left"))))
                .status())
        .isEqualTo(ProbeResult.PASS);
    assertThat(get(AgentApi.PROBES + "/nothing").statusCode()).isEqualTo(404);
    assertThat(post(AgentApi.PROBES + "/vision.unit").statusCode()).isEqualTo(405);
  }

  @Test
  void unknownPathsAndMethodsAreRefused() throws Exception {
    assertThat(get("/v2/health").statusCode()).isEqualTo(404);
    HttpResponse<byte[]> post = post(AgentApi.HEALTH);
    assertThat(post.statusCode()).isEqualTo(405);
    assertThat(post.headers().firstValue("Allow")).contains("GET");
    HttpResponse<byte[]> get = get(AgentApi.SHUTDOWN);
    assertThat(get.statusCode()).isEqualTo(405);
    assertThat(get.headers().firstValue("Allow")).contains("POST");
  }

  @Test
  void onlyTheRobotControllerMayShutTheComputerDown() throws Exception {
    // The stamp's team is 1234: the robot controller is 10.12.34.2, and this test isn't.
    HttpResponse<byte[]> refused = post(AgentApi.SHUTDOWN);
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(text(refused)).contains("only the robot controller (10.12.34.2)");
    assertThat(fixture.commands.ran()).noneMatch(command -> command.contains("poweroff"));
  }

  @Test
  void aShutdownRunsItsPacksStepsThenPowersOffOnce() throws Exception {
    serve(fixture.agent("127.0.0.1"));
    HttpResponse<byte[]> first = post(AgentApi.SHUTDOWN);
    assertThat(first.statusCode()).isEqualTo(202);
    assertThat(ShutdownAnswer.parse(text(first)).alreadyRequested()).isFalse();
    List<List<String>> ran = fixture.commands.ran();
    assertThat(ran.subList(ran.size() - 2, ran.size()))
        .containsExactly(
            List.of("systemctl", "stop", "vision.service"), List.of("systemctl", "poweroff"));
    assertThat(fixture.log.get(0))
        .isEqualTo("Shutdown asked for by 127.0.0.1: running vision.stop, then powering off");

    HttpResponse<byte[]> again = post(AgentApi.SHUTDOWN);
    assertThat(again.statusCode()).isEqualTo(202);
    assertThat(ShutdownAnswer.parse(text(again)).alreadyRequested()).isTrue();
    assertThat(fixture.commands.ran()).hasSize(ran.size());

    fixture.micros.addAndGet(1_000_000);
    assertThat(Health.parse(text(get(AgentApi.HEALTH))).problems())
        .first()
        .isEqualTo("shutting down: asked for by the robot");
  }

  @Test
  void theControllerIsTheConfigurationsWhenItNamesOne() throws Exception {
    fixture.config(
        new AgentConfig("vision-front", "127.0.0.1", 5808, List.of(), List.of(), List.of()));
    serve(fixture.agent());
    assertThat(post(AgentApi.SHUTDOWN).statusCode()).isEqualTo(202);
    assertThat(fixture.log.get(0)).isEqualTo("Shutdown asked for by 127.0.0.1: powering off");
  }

  @Test
  void aShutdownGoesOnWhenAStepFails() throws Exception {
    fixture.commands.answer(
        List.of("systemctl", "stop"), new Commands.Output(1, List.of(), false, false));
    fixture.commands.answer(
        List.of("systemctl", "poweroff"), new Commands.Output(-1, List.of(), false, true));
    serve(fixture.agent("127.0.0.1"));
    assertThat(post(AgentApi.SHUTDOWN).statusCode()).isEqualTo(202);
    assertThat(fixture.log)
        .contains(
            "Step vision.stop failed (exit 1); powering off anyway",
            "Powering off failed (timed out); a shutdown may be asked for again");
  }

  @Test
  void aFailureIsAServerErrorThatSaysOnlyThatAndIsLogged() throws Exception {
    fixture.write("/etc/coprocessor/stamp.json", "[]");
    HttpResponse<byte[]> stamp = get(AgentApi.STAMP);
    assertThat(stamp.statusCode()).isEqualTo(500);
    assertThat(text(stamp))
        .isEqualTo("{\"error\":\"the agent couldn't answer; its journal says why\"}");
    assertThat(fixture.log()).anyMatch(line -> line.contains("expected an object"));
  }

  @Test
  void evenAnErrorIsAnswered() throws Exception {
    Host host =
        fixture.host(
            fixture.root,
            path -> {
              throw new AssertionError("not a link anyone expected");
            });
    serve(new Agent(host, Configuration.read(host), Duration.ofSeconds(2), Runnable::run, null));
    assertThat(get(AgentApi.PROBES + "/camera.front-left").statusCode()).isEqualTo(500);
    // The probes' turns were let go: the next request is answered (and fails) the same way.
    assertThat(get(AgentApi.PROBES + "/camera.front-left").statusCode()).isEqualTo(500);
    assertThat(get(AgentApi.HEALTH).statusCode()).isEqualTo(200);
  }

  @Test
  void answersCarryNoSniffing() throws Exception {
    assertThat(get(AgentApi.STAMP).headers().firstValue("X-Content-Type-Options"))
        .contains("nosniff");
  }

  @Test
  void onlyRequestsAddressedToTheComputerAreAnswered() throws Exception {
    for (String host :
        List.of(
            "10.12.34.11:5808",
            "10.0.0.11",
            "169.254.3.4:5808",
            "127.0.0.1",
            "localhost:5808",
            "vision-front",
            "VISION-FRONT.local:5808",
            "[::1]:5808")) {
      assertThat(raw("GET " + AgentApi.STAMP, host)).as(host).startsWith("HTTP/1.1 200");
    }
    for (String host :
        List.of(
            "evil.example", "vision-front.evil.example", "192.168.1.5", "[fe80::1]", "10.0.0")) {
      assertThat(raw("GET " + AgentApi.STAMP, host)).as(host).startsWith("HTTP/1.1 421");
    }
  }

  /** A request sent by hand, with this Host: what comes back. */
  private String raw(String request, String host) throws IOException {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
      socket.setSoTimeout(10_000);
      socket
          .getOutputStream()
          .write(
              (request + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void connectionsThatNeverFinishTheirRequestHoldUpNobody() throws Exception {
    List<Socket> stalled = new ArrayList<>();
    try {
      for (int i = 0; i < 4; i++) {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port());
        socket.getOutputStream().write('G');
        socket.getOutputStream().flush();
        stalled.add(socket);
      }
      long start = System.nanoTime();
      assertThat(get(AgentApi.HEALTH).statusCode()).isEqualTo(200);
      assertThat(get(AgentApi.STAMP).statusCode()).isEqualTo(200);
      assertThat(System.nanoTime() - start).isLessThan(3_000_000_000L);
    } finally {
      for (Socket socket : stalled) {
        socket.close();
      }
    }
  }

  @Test
  void heavyRequestsTakeTurnsAndTheLightOnesNeverWait() throws Exception {
    CountDownLatch slow = new CountDownLatch(1);
    fixture.commands.hold(List.of("journalctl", "-o"), slow);
    CompletableFuture<HttpResponse<byte[]>> first =
        client.sendAsync(
            HttpRequest.newBuilder(uri(AgentApi.JOURNAL)).timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    try {
      awaitWaiting();
      // A journal page is under way, held: another heavy request is refused at once.
      HttpResponse<byte[]> busy = get(AgentApi.SETTINGS_ZIP);
      assertThat(busy.statusCode()).isEqualTo(503);
      assertThat(busy.headers().firstValue("Retry-After")).contains("1");
      long start = System.nanoTime();
      assertThat(get(AgentApi.HEALTH).statusCode()).isEqualTo(200);
      assertThat(System.nanoTime() - start).isLessThan(3_000_000_000L);
    } finally {
      slow.countDown();
    }
    assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    assertThat(get(AgentApi.SETTINGS_ZIP).statusCode()).isEqualTo(200);
  }

  @Test
  void healthBeingReadAfreshHoldsUpNoOtherHealthRequest() throws Exception {
    Health first = Health.parse(text(get(AgentApi.HEALTH)));
    fixture.micros.addAndGet(1_000_000);
    CountDownLatch slow = new CountDownLatch(1);
    fixture.commands.hold(List.of("journalctl", "-b"), slow);
    CompletableFuture<HttpResponse<byte[]>> refreshing =
        client.sendAsync(
            HttpRequest.newBuilder(uri(AgentApi.HEALTH)).timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    try {
      awaitWaiting();
      long start = System.nanoTime();
      Health meanwhile = Health.parse(text(get(AgentApi.HEALTH)));
      assertThat(System.nanoTime() - start).isLessThan(3_000_000_000L);
      assertThat(meanwhile).isEqualTo(first);
    } finally {
      slow.countDown();
    }
    assertThat(refreshing.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
  }

  private void awaitWaiting() throws InterruptedException {
    for (int i = 0; i < 1000 && fixture.commands.waiting() == 0; i++) {
      Thread.sleep(10);
    }
    assertThat(fixture.commands.waiting()).isPositive();
  }

  @Test
  void anAnswerOverItsLimitIsRefused() throws Exception {
    Fixture small = new Fixture(dir.resolve("small"), new Limits(256 * 1024, 4 << 20, 2000));
    small.commands.answer(List.of("journalctl"), Fixture.lines("journal-this-boot.json"));
    fixture = small;
    serve(small.agent());
    // A health answer is over 2000 bytes.
    HttpResponse<byte[]> health = get(AgentApi.HEALTH);
    assertThat(health.statusCode()).isEqualTo(500);
    assertThat(text(health)).contains("larger than the agent sends");
  }

  @Test
  void aFileOverItsLimitIsCutShort() throws Exception {
    Fixture small = new Fixture(dir.resolve("small"), new Limits(64, 4 << 20, 4 << 20));
    small.commands.answer(List.of("journalctl"), Fixture.lines("journal-this-boot.json"));
    fixture = small;
    serve(small.agent());
    // The stamp file is longer than 64 bytes: cut short, it isn't JSON.
    assertThat(get(AgentApi.STAMP).statusCode()).isEqualTo(500);
    assertThat(small.log()).anyMatch(line -> line.contains("never ends"));
  }

  @Test
  void aComputerWithoutAStampOrAControllerTakesNoShutdown() throws Exception {
    fixture.delete("/etc/coprocessor/stamp.json");
    serve(fixture.agent());
    HttpResponse<byte[]> refused = post(AgentApi.SHUTDOWN);
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(text(refused)).contains("no stamp");
    fixture.write("/etc/coprocessor/stamp.json", "{\"name\":\"vision-front\",\"team\":0}");
    assertThat(post(AgentApi.SHUTDOWN).statusCode()).isEqualTo(403);
    assertThat(fixture.commands.ran()).noneMatch(command -> command.contains("poweroff"));
  }

  @Test
  void refusedShutdownsAreLoggedAtMostOnceInTenSeconds() throws Exception {
    for (int i = 0; i < 3; i++) {
      assertThat(post(AgentApi.SHUTDOWN).statusCode()).isEqualTo(403);
    }
    assertThat(fixture.log())
        .containsExactly("Refused a shutdown from 127.0.0.1: not the robot controller");
    fixture.micros.addAndGet(AgentServer.REFUSAL_LOG_MICROS);
    assertThat(post(AgentApi.SHUTDOWN).statusCode()).isEqualTo(403);
    assertThat(fixture.log())
        .last()
        .isEqualTo(
            "Refused a shutdown from 127.0.0.1: not the robot controller (and 2 more like it"
                + " before)");
  }

  @Test
  void aShutdownThatFailsToPowerOffSaysSoAndMayBeAskedForAgain() throws Exception {
    fixture.commands.answer(
        List.of("systemctl", "poweroff"), new Commands.Output(1, List.of(), false, false));
    serve(fixture.agent("127.0.0.1"));
    assertThat(post(AgentApi.SHUTDOWN).statusCode()).isEqualTo(202);
    fixture.micros.addAndGet(1_000_000);
    assertThat(Health.parse(text(get(AgentApi.HEALTH))).problems())
        .first()
        .isEqualTo("shutdown failed: powering off failed (exit 1); ask again to retry");

    fixture.commands.answer(List.of("systemctl", "poweroff"), List.of());
    HttpResponse<byte[]> again = post(AgentApi.SHUTDOWN);
    assertThat(ShutdownAnswer.parse(text(again)).alreadyRequested()).isFalse();
    assertThat(fixture.commands.ran().stream().filter(c -> c.contains("poweroff")).count())
        .isEqualTo(2);
  }
}
