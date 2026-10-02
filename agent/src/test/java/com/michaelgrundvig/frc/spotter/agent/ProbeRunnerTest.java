package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.probes.Check;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each kind of probe, run once against a fixture computer: what it reports when it finds what the
 * image expects, when it doesn't, and when it can't run at all.
 */
class ProbeRunnerTest {
  @TempDir Path dir;
  Fixture fixture;
  final Map<String, Double> metrics = new HashMap<>();
  ProbeRunner runner;

  @BeforeEach
  void aComputer() throws IOException {
    fixture = new Fixture(dir);
    runner = new ProbeRunner(fixture.host, () -> metrics);
  }

  private ProbeResult run(Check check) {
    return runner.run(new Probe("p", "test", check, 0, 2, List.of()));
  }

  private static String said(ProbeResult result) {
    return result.status() + " [" + result.value() + "] " + result.detail();
  }

  @Test
  void aCommandPassesOnItsExitAndPatternAndReportsWhatMatched() {
    fixture.commands.answer(List.of("helper", "version"), List.of("v2027.0.0-alpha-2"));
    assertThat(said(run(new Check.Command(List.of("helper", "version"), 0, "^(v\\S+)$"))))
        .isEqualTo("pass [v2027.0.0-alpha-2] ");
    // Without a pattern, the value is the first line.
    assertThat(said(run(new Check.Command(List.of("helper", "version"), 0, ""))))
        .isEqualTo("pass [v2027.0.0-alpha-2] ");
    assertThat(said(run(new Check.Command(List.of("helper", "version"), 0, "^(\\d+)$"))))
        .isEqualTo("fail [v2027.0.0-alpha-2] exit 0: its output didn't match ^(\\d+)$");

    fixture.commands.answer(
        List.of("helper", "camera"),
        new Commands.Output(1, List.of("missing /dev/v4l/by-path/x"), false, false));
    assertThat(said(run(new Check.Command(List.of("helper", "camera"), 0, ""))))
        .isEqualTo("fail [missing /dev/v4l/by-path/x] exit 1, not 0: missing /dev/v4l/by-path/x");
    assertThat(
            said(
                run(
                    new Check.Command(
                        List.of("helper", "camera"), Check.Command.ANY_EXIT, "^missing (.*)$"))))
        .isEqualTo("pass [/dev/v4l/by-path/x] ");

    // A failure the command explained on its standard error says why.
    fixture.commands.answer(
        List.of("helper", "fingerprint"),
        new Commands.Output(1, List.of(), false, false, "no AprilTag layout of its own"));
    assertThat(said(run(new Check.Command(List.of("helper", "fingerprint"), 0, "^([0-9a-f]+)$"))))
        .isEqualTo("fail [] exit 1: no AprilTag layout of its own");

    fixture.commands.answer(
        List.of("helper", "slow"), new Commands.Output(-1, List.of(), false, true));
    assertThat(said(run(new Check.Command(List.of("helper", "slow"), 0, ""))))
        .isEqualTo("error [] timed out after 2000 ms");
    // A program that can't start is an error, saying why.
    assertThat(said(run(new Check.Command(List.of("absent"), 0, ""))))
        .isEqualTo("error [] no such command: absent");
  }

  @Test
  void anHttpProbeAsksThisComputerAndReadsAField() throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/api/status",
        exchange -> {
          byte[] body = "not dead yet".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/api/version",
        exchange -> {
          byte[] body = "{\"general\": {\"version\": \"v1\"}}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      String at = "http://127.0.0.1:" + server.getAddress().getPort();
      assertThat(said(run(new Check.Http(at + "/api/status", 200, "", ""))))
          .isEqualTo("pass [200] ");
      assertThat(said(run(new Check.Http(at + "/api/version", 200, "general.version", "v1"))))
          .isEqualTo("pass [v1] ");
      assertThat(said(run(new Check.Http(at + "/api/version", 200, "general.version", "v2"))))
          .isEqualTo("fail [v1] general.version is v1, not v2");
      assertThat(said(run(new Check.Http(at + "/api/version", 200, "general.build", ""))))
          .isEqualTo("fail [] general.build isn't there");
      assertThat(said(run(new Check.Http(at + "/api/status", 200, "general", ""))))
          .startsWith("fail [] its answer isn't JSON");
      assertThat(said(run(new Check.Http(at + "/missing", 200, "", ""))))
          .isEqualTo("fail [404] answered 404, not 200");
    } finally {
      server.stop(0);
    }
    // Nothing listening there now.
    String gone = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/status";
    assertThat(run(new Check.Http(gone, 200, "", "")).detail())
        .isEqualTo("nothing answers at " + gone);
  }

  @Test
  void aFileProbeSaysWhetherItsThereItsSizeHashFieldOrLine() throws IOException {
    fixture.write("/opt/x/version.json", "{\"version\": \"v3\", \"n\": 2}\n");
    String path = "/opt/x/version.json";
    assertThat(said(run(file(path, Check.FileTest.EXISTS, -1, -1, "", "", "", ""))))
        .isEqualTo("pass [26] ");
    assertThat(said(run(file(path, Check.FileTest.SIZE, 10, 20, "", "", "", ""))))
        .isEqualTo("fail [26] 26 bytes, outside 10 to 20");
    assertThat(said(run(file(path, Check.FileTest.SIZE, 10, -1, "", "", "", ""))))
        .isEqualTo("pass [26] ");
    String hash = run(file(path, Check.FileTest.SHA256, -1, -1, "", "", "", "")).value();
    assertThat(hash).matches("[0-9a-f]{64}");
    assertThat(run(file(path, Check.FileTest.SHA256, -1, -1, hash, "", "", "")).status())
        .isEqualTo("pass");
    assertThat(said(run(file(path, Check.FileTest.SHA256, -1, -1, "0".repeat(64), "", "", ""))))
        .isEqualTo("fail [" + hash + "] its SHA-256 isn't " + "0".repeat(64));
    assertThat(said(run(file(path, Check.FileTest.JSON, -1, -1, "", "version", "v3", ""))))
        .isEqualTo("pass [v3] ");
    assertThat(said(run(file(path, Check.FileTest.JSON, -1, -1, "", "n", "", ""))))
        .isEqualTo("pass [2] ");
    assertThat(
            said(
                run(
                    file(
                        path, Check.FileTest.TEXT, -1, -1, "", "", "", "\"version\": \"(v\\d)\""))))
        .isEqualTo("pass [v3] ");
    assertThat(said(run(file(path, Check.FileTest.TEXT, -1, -1, "", "", "", "build"))))
        .isEqualTo("fail [] /opt/x/version.json has nothing matching build");
    assertThat(said(run(file("/opt/none", Check.FileTest.EXISTS, -1, -1, "", "", "", ""))))
        .isEqualTo("fail [] /opt/none isn't there");
    fixture.write("/opt/x/text", "not json");
    assertThat(said(run(file("/opt/x/text", Check.FileTest.JSON, -1, -1, "", "a", "", ""))))
        .startsWith("fail [] /opt/x/text isn't JSON");
  }

  private static Check.File file(
      String path,
      Check.FileTest test,
      long min,
      long max,
      String sha,
      String field,
      String equals,
      String match) {
    return new Check.File(path, test, min, max, sha, field, equals, match);
  }

  @Test
  void aUnitProbeReadsItsStateAndRestarts() {
    fixture.commands.answer(
        List.of("systemctl", "show", "vision.service"),
        List.of(
            "LoadState=loaded",
            "ActiveState=active",
            "SubState=running",
            "Result=success",
            "NRestarts=2"));
    assertThat(said(run(new Check.Unit("vision.service", "active"))))
        .isEqualTo("pass [active/running, restarts 2] ");
    fixture.commands.answer(
        List.of("systemctl", "show", "vision.service"),
        List.of(
            "LoadState=loaded",
            "ActiveState=failed",
            "SubState=failed",
            "Result=exit-code",
            "NRestarts=5"));
    assertThat(said(run(new Check.Unit("vision.service", "active"))))
        .isEqualTo(
            "fail [failed/failed, restarts 5] vision.service is failed, not active (exit-code)");
    fixture.commands.answer(
        List.of("systemctl", "show", "lidar.service"),
        List.of("LoadState=not-found", "ActiveState=inactive"));
    assertThat(said(run(new Check.Unit("lidar.service", "active"))))
        .isEqualTo("fail [not-found] lidar.service isn't installed");
    fixture.commands.answer(
        List.of("systemctl", "show", "slow.service"),
        new Commands.Output(-1, List.of(), false, true));
    assertThat(said(run(new Check.Unit("slow.service", "active"))))
        .isEqualTo("error [] systemctl show slow.service timed out");
  }

  @Test
  void aUsbProbeChecksTheLinkSpeed() {
    assertThat(said(run(new Check.Usb(Fixture.FRONT_RIGHT, 5000))))
        .isEqualTo("fail [3-1 480 Mb/s Arducam OV9281 USB Camera] linked at 480 Mb/s, below 5000");
    assertThat(run(new Check.Usb(Fixture.FRONT_LEFT, 5000)).status()).isEqualTo("pass");
  }

  @Test
  void aThresholdHoldsAMeasurementToItsLimits() {
    metrics.put("memory.available.percent", 8.25);
    assertThat(said(run(new Check.Threshold("memory.available.percent", 10, Double.NaN))))
        .isEqualTo("fail [8.3] memory.available.percent is 8.3, below 10");
    assertThat(said(run(new Check.Threshold("memory.available.percent", Double.NaN, 5))))
        .isEqualTo("fail [8.3] memory.available.percent is 8.3, above 5");
    assertThat(said(run(new Check.Threshold("memory.available.percent", 5, 10))))
        .isEqualTo("pass [8.3] ");
    assertThat(said(run(new Check.Threshold("drive.celsius", 0, 70))))
        .isEqualTo("error [] drive.celsius isn't measured on this computer");
  }

  @Test
  void probesRunOnScheduleAndThoseThatWatchFilesOnlyWhenTheyChange() throws Exception {
    fixture.write("/opt/x/db", "one");
    fixture.commands.answer(List.of("helper", "hash"), List.of("aaaa"));
    Probe watching =
        new Probe(
            "hash",
            "test",
            new Check.Command(List.of("helper", "hash"), 0, ""),
            0.1,
            2,
            List.of("/opt/x/db"));
    Probe ticking =
        new Probe(
            "tick", "test", new Check.Command(List.of("helper", "hash"), 0, ""), 0.1, 2, List.of());
    ProbeSet set = new ProbeSet(List.of(), List.of(watching, ticking), List.of());
    Probes probes = new Probes(fixture.host, set, runner);
    probes.scheduled(watching);
    probes.scheduled(watching);
    probes.scheduled(watching);
    assertThat(fixture.commands.ran()).hasSize(1);
    // A change reruns it.
    fixture.write("/opt/x/db", "two, longer");
    probes.scheduled(watching);
    assertThat(fixture.commands.ran()).hasSize(2);
    // Five minutes on, it runs anyway.
    fixture.micros.addAndGet(Math.round(Probe.WATCHED_EVERY_SECONDS * 1e6));
    probes.scheduled(watching);
    assertThat(fixture.commands.ran()).hasSize(3);

    // On its own schedule, on the probes' threads.
    probes.start();
    for (int i = 0; i < 100 && probes.result("tick").orElseThrow().ranMicros() == 0; i++) {
      Thread.sleep(20);
    }
    probes.close();
    assertThat(probes.executor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    assertThat(probes.result("tick").orElseThrow().status()).isEqualTo(ProbeResult.PASS);
    assertThat(probes.result("none")).isEmpty();
  }

  @Test
  void aProbeThatChangesItsStatusIsLogged() throws Exception {
    fixture.commands.answer(List.of("helper"), List.of("ok"));
    Probe probe =
        new Probe("p", "test", new Check.Command(List.of("helper"), 0, "^ok$"), 0, 2, List.of());
    Probes probes =
        new Probes(fixture.host, new ProbeSet(List.of(), List.of(probe), List.of()), runner);
    probes.runNow("p");
    fixture.commands.answer(List.of("helper"), List.of("not ok"));
    fixture.micros.addAndGet(5_000_000);
    probes.runNow("p");
    assertThat(fixture.log())
        .containsExactly("Probe p is now fail: exit 0: its output didn't match ^ok$");
    probes.close();
  }
}
