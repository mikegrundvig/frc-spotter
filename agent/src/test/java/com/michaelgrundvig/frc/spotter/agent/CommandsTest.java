package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Running a pack's commands for real: processes, the board's own web pages, and files. */
class CommandsTest {
  private static final Duration SECOND = Duration.ofSeconds(1);

  @TempDir Path dir;
  Fixture fixture;
  Commands commands;
  String folder;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
    commands = new Commands(fixture.host);
    folder = fixture.pack("vision", "pack: vision\n");
  }

  @AfterEach
  void close() {
    commands.close();
  }

  private Commands.Result run(String... argv) {
    return commands.run(folder, new Command.Run(List.of(argv)), SECOND, 1024);
  }

  @Test
  void aPacksOwnProgramRunsFromItsFolder() {
    fixture.script(folder + "/where", "pwd; echo \"$1\"");
    Commands.Result result = run("./where", "front");
    assertThat(result.completed()).isTrue();
    assertThat(result.code()).isZero();
    assertThat(result.text()).isEqualTo(fixture.path(folder) + "\nfront\n");
  }

  @Test
  void aProgramByAbsolutePathOrNameRunsWithItsArgumentsAndNoShell() {
    fixture.script("/opt/team/health", "echo \"$#:$1\"");
    assertThat(run("/opt/team/health", "a b; echo c").text()).isEqualTo("1:a b; echo c\n");
    assertThat(run("sh", "-c", "echo 42; exit 3").code()).isEqualTo(3);
    assertThat(run("sh", "-c", "echo 42; exit 3").text()).isEqualTo("42\n");
  }

  @Test
  void standardErrorsFirstLineSaysWhy() {
    Commands.Result result =
        run("sh", "-c", "echo '' >&2; echo 'no such unit' >&2; echo more >&2; exit 4");
    assertThat(result.errors()).isEqualTo("no such unit");
    assertThat(result.code()).isEqualTo(4);
  }

  @Test
  void aProgramThatIsntThereCouldNotStart() {
    Commands.Result missing = run("./nothing");
    assertThat(missing.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COULD_NOT_START);
    assertThat(missing.message()).isEqualTo("no such file: " + folder + "/nothing");
    fixture.write(folder + "/plain", "echo not executable\n");
    Commands.Result denied = run("./plain");
    assertThat(denied.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COULD_NOT_START);
    assertThat(denied.message())
        .isEqualTo(
            "not executable ("
                + folder
                + "/plain): chmod +x, or name its interpreter: run: [sh, ./plain]");
  }

  @Test
  void aProgramThatOverrunsIsKilledWithWhatItStarted() {
    long started = System.nanoTime();
    Commands.Result result =
        commands.run(
            folder,
            new Command.Run(List.of("sh", "-c", "echo started; sleep 30 & sleep 30")),
            Duration.ofMillis(300),
            1024);
    assertThat((System.nanoTime() - started) / 1e9).isLessThan(5);
    assertThat(result.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_TIMED_OUT);
    assertThat(result.message()).isEqualTo("timed out after 300ms");
    assertThat(result.text()).isEqualTo("started\n");
  }

  @Test
  void outputPastTheMostKeptIsDrainedAndSaid() {
    Commands.Result result =
        commands.run(
            folder,
            new Command.Run(List.of("sh", "-c", "head -c 100000 /dev/zero; echo done >&2")),
            SECOND,
            1000);
    assertThat(result.completed()).isTrue();
    assertThat(result.output()).hasSize(1000);
    assertThat(result.truncated()).isTrue();
    assertThat(result.errors()).isEqualTo("done");
  }

  @Test
  void theBoardsOwnPagesAreAsked() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/status",
        exchange -> {
          byte[] body =
              (exchange.getRequestMethod() + " {\"up\": true}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/missing",
        exchange -> {
          exchange.sendResponseHeaders(404, 3);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write("no!".getBytes(StandardCharsets.UTF_8));
          }
        });
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(3000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();
    try {
      String at = "http://localhost:" + server.getAddress().getPort();
      Commands.Result got =
          commands.run(folder, new Command.Http("GET", at + "/api/status", ""), SECOND, 1024);
      assertThat(got.code()).isEqualTo(200);
      assertThat(got.text()).isEqualTo("GET {\"up\": true}");
      Commands.Result posted =
          commands.run(folder, new Command.Http("POST", at + "/api/status", ""), SECOND, 1024);
      assertThat(posted.text()).startsWith("POST");
      Commands.Result missing =
          commands.run(folder, new Command.Http("GET", at + "/missing", ""), SECOND, 1024);
      assertThat(missing.completed()).isTrue();
      assertThat(missing.code()).isEqualTo(404);
      assertThat(missing.text()).isEqualTo("no!");
      Commands.Result slow =
          commands.run(
              folder, new Command.Http("GET", at + "/slow", ""), Duration.ofMillis(200), 1024);
      assertThat(slow.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_TIMED_OUT);
      assertThat(slow.message()).startsWith("no answer within 200ms (localhost:");
      Commands.Result cut =
          commands.run(folder, new Command.Http("GET", at + "/api/status", ""), SECOND, 4);
      assertThat(cut.truncated()).isTrue();
      assertThat(cut.text()).isEqualTo("GET ");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void anActionsInputGoesAsAFormsFileFieldNamedAsThePackSays() throws Exception {
    List<String> got = new java.util.concurrent.CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/upload",
        exchange -> {
          got.add(exchange.getRequestHeaders().getFirst("Content-Type"));
          got.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      String at = "http://localhost:" + server.getAddress().getPort() + "/upload";
      Path input = dir.resolve("input");
      java.nio.file.Files.writeString(input, "{\"tags\": []}");
      Commands.Options options =
          new Commands.Options(input, java.util.Map.of(), 1024, 0, line -> {}, Duration.ZERO);
      for (Command.Http http :
          List.of(
              new Command.Http("POST", at, "data", "layout.json"),
              new Command.Http("POST", at, "data"))) {
        got.clear();
        Commands.Result result =
            commands.run(folder, http, SECOND, options, new Commands.Cancellation());
        assertThat(result.code()).isEqualTo(200);
        assertThat(got.get(0)).startsWith("multipart/form-data; boundary=spotter-");
        assertThat(got.get(1))
            .contains(
                "Content-Disposition: form-data; name=\"data\"; filename=\"" + http.file() + "\"")
            .contains("\r\n\r\n{\"tags\": []}\r\n--spotter-");
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aPageNobodyServesIsUnreachable() throws Exception {
    int port;
    try (ServerSocket free = new ServerSocket(0)) {
      port = free.getLocalPort();
    }
    Commands.Result result =
        commands.run(
            folder, new Command.Http("GET", "http://localhost:" + port + "/", ""), SECOND, 1024);
    assertThat(result.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_UNREACHABLE);
    assertThat(result.message()).isEqualTo("connection refused (localhost:" + port + ")");
  }

  @Test
  void aFileIsReadUnderTheRoot() {
    fixture.write("/proc/uptime", "4123.55 15991.04\n");
    Commands.Result read = commands.run(folder, new Command.Read("/proc/uptime"), SECOND, 1024);
    assertThat(read.text()).isEqualTo("4123.55 15991.04\n");
    assertThat(read.truncated()).isFalse();
    Commands.Result cut = commands.run(folder, new Command.Read("/proc/uptime"), SECOND, 4);
    assertThat(cut.text()).isEqualTo("4123");
    assertThat(cut.truncated()).isTrue();
    Commands.Result gone =
        commands.run(folder, new Command.Read("/sys/bus/usb/devices/7-1/product"), SECOND, 1024);
    assertThat(gone.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COULD_NOT_START);
    assertThat(gone.message()).isEqualTo("no such file: /sys/bus/usb/devices/7-1/product");
    Commands.Result folderRead = commands.run(folder, new Command.Read("/proc"), SECOND, 1024);
    assertThat(folderRead.completed()).isFalse();
    assertThat(folderRead.message()).startsWith("/proc: ");
  }
}
