package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

/**
 * Protocol 2's writes and stream on the wire: taken from the controller alone, refused while the
 * board's settings can't be read, signed when it requires it; actions, runs, logs, pushes.
 */
class WritesTest {
  @TempDir Path dir;
  Fixture fixture;
  @Nullable AgentServer server;
  final HttpClient http = HttpClient.newHttpClient();

  static final String TEAM =
      """
      pack: team
      collectors:
        - id: health
          run: [echo, "29.5"]
          every: 1h
          fields:
            fps: {type: number}
      logs:
        - id: log
          run: [./log]
      actions:
        - id: echo
          run: [cat]
          input: text
        - id: wait
          run: [sleep, "30"]
        - id: export
          run: [printf, "PK"]
          response:
            output: {type: file, name: settings.zip}
      """;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
    String folder = fixture.pack("team", TEAM);
    fixture.script(folder + "/log", "echo '{\"message\": \"hello\", \"cursor\": \"1\"}'");
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.close();
    }
  }

  /** Serves the board, taking writes from this test's address, or the board's own controller. */
  private AgentServer serve(@Nullable String controller) throws Exception {
    Agent agent = fixture.agent(controller);
    agent.start();
    AgentServer started = new AgentServer(agent, new InetSocketAddress("127.0.0.1", 0));
    server = started;
    return started;
  }

  private URI at(String path) {
    return URI.create("http://127.0.0.1:" + server().port() + path);
  }

  private AgentServer server() {
    if (server == null) {
      throw new IllegalStateException("not serving");
    }
    return server;
  }

  private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
    return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private HttpResponse<byte[]> post(String path, String body, String... headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(at(path)).POST(HttpRequest.BodyPublishers.ofString(body));
    if (headers.length > 0) {
      request.headers(headers);
    }
    return send(request);
  }

  private static String problem(HttpResponse<byte[]> response) throws Exception {
    return ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), response.body()).getMessage();
  }

  private Spotter.RunState finished(String run) throws Exception {
    for (int i = 0; i < 100; i++) {
      Spotter.RunState state =
          ProtoMessage.mergeFrom(
              Spotter.RunState.newInstance(),
              send(HttpRequest.newBuilder(at("/v2/runs/" + run))).body());
      if (!state.getRunning()) {
        return state;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("run " + run + " didn't finish");
  }

  @Test
  void theControllerRunsAnActionAndReadsItsRun() throws Exception {
    serve("127.0.0.1");
    HttpResponse<byte[]> started = post("/v2/actions/team.echo", "hello\n");
    assertThat(started.statusCode()).isEqualTo(202);
    assertThat(started.headers().firstValue(Protocol.HEADER)).hasValue("2.0");
    String run = ProtoMessage.mergeFrom(Spotter.Started.newInstance(), started.body()).getRun();
    Spotter.RunState state = finished(run);
    assertThat(state.getResult().getResponse().get(3).getText()).isEqualTo("hello\n");
    HttpResponse<byte[]> log = send(HttpRequest.newBuilder(at("/v2/runs/" + run + "/log")));
    assertThat(log.statusCode()).isEqualTo(200);
    assertThat(post("/v2/actions/team.wait", "x").statusCode()).isEqualTo(400);
    assertThat(problem(post("/v2/actions/team.wait", "x"))).isEqualTo("team.wait takes no input");
    assertThat(post("/v2/actions/team.none", "").statusCode()).isEqualTo(404);
    assertThat(send(HttpRequest.newBuilder(at("/v2/actions/team.wait"))).statusCode())
        .isEqualTo(405);

    String waiting =
        ProtoMessage.mergeFrom(
                Spotter.Started.newInstance(), post("/v2/actions/team.wait", "").body())
            .getRun();
    assertThat(post("/v2/actions/team.wait", "").statusCode()).isEqualTo(409);
    HttpResponse<byte[]> cancelled =
        send(HttpRequest.newBuilder(at("/v2/runs/" + waiting)).DELETE());
    assertThat(cancelled.statusCode()).isEqualTo(204);
    assertThat(finished(waiting).getResult().getOutcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
    assertThat(send(HttpRequest.newBuilder(at("/v2/runs/" + waiting)).DELETE()).statusCode())
        .isEqualTo(409);
    assertThat(send(HttpRequest.newBuilder(at("/v2/runs/0123456789abcdef"))).statusCode())
        .isEqualTo(404);
    assertThat(send(HttpRequest.newBuilder(at("/v2/runs/nope"))).statusCode()).isEqualTo(404);
    assertThat(
            send(HttpRequest.newBuilder(at("/v2/runs/" + waiting))
                    .PUT(HttpRequest.BodyPublishers.noBody()))
                .statusCode())
        .isEqualTo(405);

    String export =
        ProtoMessage.mergeFrom(
                Spotter.Started.newInstance(), post("/v2/actions/team.export", "").body())
            .getRun();
    finished(export);
    HttpResponse<byte[]> file =
        send(HttpRequest.newBuilder(at("/v2/runs/" + export + "/files/output")));
    assertThat(file.statusCode()).isEqualTo(200);
    assertThat(new String(file.body(), StandardCharsets.UTF_8)).isEqualTo("PK");
    assertThat(file.headers().firstValue("Content-Disposition"))
        .hasValue("attachment; filename=\"settings.zip\"");
    assertThat(send(HttpRequest.newBuilder(at("/v2/runs/" + export + "/other"))).statusCode())
        .isEqualTo(404);
  }

  @Test
  void writesAreTakenFromTheControllerAlone() throws Exception {
    // The board is at 10.12.34.11, so its controller is 10.12.34.2: this test isn't it.
    serve(null);
    HttpResponse<byte[]> refused = post("/v2/actions/core.reboot", "");
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(problem(refused))
        .isEqualTo("only the robot controller (10.12.34.2) may run an action");
    assertThat(send(HttpRequest.newBuilder(at("/v2/runs/0123456789abcdef")).DELETE()).statusCode())
        .isEqualTo(403);
    assertThat(post("/v2/packs", "bundle").statusCode()).isEqualTo(403);
    assertThat(fixture.log())
        .contains("Refused a write (run an action) from 127.0.0.1: not the robot controller");
    // Reading is open to anyone.
    assertThat(send(HttpRequest.newBuilder(at(Protocol.DESCRIBE))).statusCode()).isEqualTo(200);
    assertThat(send(HttpRequest.newBuilder(at("/v2/logs/team.log"))).statusCode()).isEqualTo(200);
  }

  @Test
  void aBoardOnNoRobotNetworkTakesNoWrites() throws Exception {
    fixture.addresses.clear();
    serve(null);
    assertThat(problem(post("/v2/actions/core.reboot", "")))
        .startsWith("writes are taken from nobody: this computer has no 10.TE.AM.x address");
  }

  @Test
  void settingsThatCantBeReadRefuseEveryWriteAndReadingWorks() throws Exception {
    fixture.config("{\"acceptPushes\": fals}");
    serve("127.0.0.1");
    HttpResponse<byte[]> refused = post("/v2/actions/core.reboot", "");
    assertThat(refused.statusCode()).isEqualTo(503);
    assertThat(problem(refused))
        .startsWith("every write is refused while /etc/frc-spotter/agent.json can't be read: ");
    assertThat(send(HttpRequest.newBuilder(at("/v2/runs/0123456789abcdef")).DELETE()).statusCode())
        .isEqualTo(503);
    assertThat(post("/v2/packs", "bundle").statusCode()).isEqualTo(503);
    Spotter.Description description =
        ProtoMessage.mergeFrom(
            Spotter.Description.newInstance(),
            send(HttpRequest.newBuilder(at(Protocol.DESCRIBE))).body());
    assertThat(description.getProblems().get(0))
        .startsWith("/etc/frc-spotter/agent.json: can't be read, so every write is refused: ");
    assertThat(description.getRefusesPushes()).isTrue();
  }

  @Test
  void aPagedLogAndItsProblems() throws Exception {
    serve("127.0.0.1");
    Spotter.LogPage page =
        ProtoMessage.mergeFrom(
            Spotter.LogPage.newInstance(),
            send(HttpRequest.newBuilder(at("/v2/logs/team.log?limit=5"))).body());
    assertThat(page.getEntries().get(0).getMessage()).isEqualTo("hello");
    assertThat(send(HttpRequest.newBuilder(at("/v2/logs/team.log?from=after"))).statusCode())
        .isEqualTo(400);
    assertThat(send(HttpRequest.newBuilder(at("/v2/logs/team.none"))).statusCode()).isEqualTo(404);
  }

  /** A stream, open: its response, and its events as they come. */
  private record Opened(HttpResponse<InputStream> response, ProtoSource events) {
    Spotter.Event next() throws Exception {
      return Spotter.Event.newInstance().mergeDelimitedFrom(events);
    }
  }

  private Opened stream(String query) throws Exception {
    HttpResponse<InputStream> response =
        http.send(
            HttpRequest.newBuilder(at(Protocol.STREAM + query)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    return new Opened(response, ProtoSource.newInstance(response.body()));
  }

  @Test
  void theStreamOnTheWire() throws Exception {
    serve("127.0.0.1");
    Opened opened = stream("?heartbeat=50ms");
    assertThat(opened.response().statusCode()).isEqualTo(200);
    assertThat(opened.response().headers().firstValue(Protocol.HEADER)).hasValue("2.0");
    assertThat(opened.response().headers().firstValue(Protocol.CHALLENGE)).isPresent();
    assertThat(opened.next().hasDescribed()).isTrue();
    assertThat(opened.next().getValues().getComplete()).isTrue();
    assertThat(opened.next().hasRuns()).isTrue();
    Spotter.Event event = opened.next();
    while (event.hasValues()) {
      event = opened.next();
    }
    assertThat(event.hasHeartbeat()).isTrue();
    opened.response().body().close();

    assertThat(send(HttpRequest.newBuilder(at(Protocol.STREAM + "?heartbeat=often"))).statusCode())
        .isEqualTo(400);
  }

  @Test
  void theStreamAsJsonLinesForPeople() throws Exception {
    serve("127.0.0.1");
    HttpResponse<java.util.stream.Stream<String>> lines =
        http.send(
            HttpRequest.newBuilder(at(Protocol.STREAM))
                .header("Accept", "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofLines());
    assertThat(lines.headers().firstValue("Content-Type")).hasValue(AgentServer.JSON_LINES);
    try (java.util.stream.Stream<String> each = lines.body()) {
      assertThat(each.limit(3))
          .satisfiesExactly(
              first -> assertThat(first).startsWith("{\"described\":"),
              second -> assertThat(second).startsWith("{\"values\":"),
              third -> assertThat(third).startsWith("{\"runs\":"));
    }
  }

  @Test
  void aStreamAskingWithOtherPacksIsAnsweredWithTheBoards() throws Exception {
    fixture.pushed("vision", "pack: vision\n");
    String ours = PackHash.of(fixture.path(Packs.PUSHED));
    serve("127.0.0.1");
    HttpResponse<byte[]> differ = send(HttpRequest.newBuilder(at(Protocol.STREAM + "?packs=abc")));
    assertThat(differ.statusCode()).isEqualTo(409);
    Spotter.Problem problem = ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), differ.body());
    assertThat(problem.getPushedPacks()).isEqualTo(ours);
    Opened same = stream("?packs=" + ours);
    assertThat(same.response().statusCode()).isEqualTo(200);
    same.response().body().close();
  }

  @Test
  void aBoardThatRefusesPushesNeverAnswers409AndRefusesThem() throws Exception {
    fixture.config("{\"acceptPushes\": false}");
    serve("127.0.0.1");
    Opened opened = stream("?packs=abc");
    assertThat(opened.response().statusCode()).isEqualTo(200);
    opened.response().body().close();
    HttpResponse<byte[]> refused = post("/v2/packs", "bundle");
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(problem(refused))
        .isEqualTo("this board refuses pushes: its agent.json says acceptPushes: false");
  }

  @Test
  void aPushPutsThePacksInPlaceAndEndsTheAgent() throws Exception {
    serve("127.0.0.1");
    Spotter.PackBundle bundle = Spotter.PackBundle.newInstance();
    bundle.addFiles(
        Spotter.PackFile.newInstance()
            .setPath("vision/pack.yaml")
            .setContent("pack: vision\n".getBytes(StandardCharsets.UTF_8)));
    HttpResponse<byte[]> pushed =
        send(
            HttpRequest.newBuilder(at(Protocol.PACKS))
                .header("Content-Type", Protocol.PROTOBUF)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bundle.toByteArray())));
    assertThat(pushed.statusCode()).isEqualTo(202);
    for (int i = 0; i < 50 && fixture.exits.get() == 0; i++) {
      Thread.sleep(50);
    }
    assertThat(fixture.exits.get()).isEqualTo(1);
    assertThat(Files.readString(fixture.path(Packs.PUSHED + "/vision/pack.yaml")))
        .isEqualTo("pack: vision\n");
    HttpResponse<byte[]> notABundle = post("/v2/packs", "not a bundle");
    assertThat(notABundle.statusCode()).isEqualTo(400);
    assertThat(
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(fixture.path(Packs.PUSHED + "/vision/pack.yaml"))))
        .isEqualTo("rw-r--r--");
  }

  @Test
  void aBoardRequiringSignaturesTakesSignedWritesOnly() throws Exception {
    SignaturesTest.Signer team = new SignaturesTest.Signer();
    fixture.config("{\"trustedKeys\": [\"" + team.publicKey + "\"]}");
    serve("127.0.0.1");
    Opened opened = stream("");
    String challenge = opened.response().headers().firstValue(Protocol.CHALLENGE).orElseThrow();
    HttpResponse<byte[]> unsigned = post("/v2/actions/team.echo", "hi");
    assertThat(unsigned.statusCode()).isEqualTo(401);
    assertThat(problem(unsigned))
        .isEqualTo("this board requires signed writes, and this one isn't signed");
    byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
    String signature = team.sign(challenge, 1, "POST", "/v2/actions/team.echo", body);
    HttpResponse<byte[]> signed =
        post("/v2/actions/team.echo", "hi", Protocol.SIGNATURE, signature);
    assertThat(signed.statusCode()).isEqualTo(202);
    String run = ProtoMessage.mergeFrom(Spotter.Started.newInstance(), signed.body()).getRun();
    finished(run);
    // Replayed: refused.
    assertThat(post("/v2/actions/team.echo", "hi", Protocol.SIGNATURE, signature).statusCode())
        .isEqualTo(401);
    String cancel = team.sign(challenge, 2, "DELETE", "/v2/runs/" + run, new byte[0]);
    assertThat(
            send(HttpRequest.newBuilder(at("/v2/runs/" + run))
                    .DELETE()
                    .header(Protocol.SIGNATURE, cancel))
                .statusCode())
        .isEqualTo(409);
    opened.response().body().close();
  }
}
