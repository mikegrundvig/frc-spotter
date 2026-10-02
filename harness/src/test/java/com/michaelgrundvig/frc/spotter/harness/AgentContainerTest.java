package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.AgentHttp;
import com.michaelgrundvig.frc.spotter.client.DeployCheck;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import com.michaelgrundvig.frc.spotter.table.Computer;
import com.michaelgrundvig.frc.spotter.table.Pack;
import com.michaelgrundvig.frc.spotter.table.Packs;
import com.michaelgrundvig.frc.spotter.table.Table;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.Network;

/**
 * The agent as installed from its .deb on a plain Debian 13 with no Java, under systemd with its
 * root read-only, and the robot's client calling it: every endpoint on the wire, the deploy check
 * against it, and each kind of probe against real files, units, a web server, and its own
 * measurements; a USB probe against a fixture tree of devices.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentContainerTest {
  static final AgentConfig STANDIN =
      new AgentConfig("vision-front", "", 5808, List.of("standin", "kinds"), List.of(), List.of());

  Network network;
  Coprocessor coprocessor;
  AgentClient client;

  @BeforeAll
  void aCoprocessor() {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(TestImages.agent(), network, 11);
    coprocessor.start();
    coprocessor.configure(STANDIN);
    client = coprocessor.client("vision-front");
  }

  @AfterAll
  void stop() {
    if (client != null) {
      client.close();
    }
    if (coprocessor != null) {
      long[] memory = coprocessor.memoryMb();
      System.out.printf(
          "Agent container: %d MiB now, %d MiB at most; the agent's unit %d MiB%n",
          memory[0], memory[1], coprocessor.agentMemoryMb());
      coprocessor.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  private Health health() throws InterruptedException {
    for (int i = 0; i < 100 && client.latest().answer() == null; i++) {
      Thread.sleep(100);
    }
    return Objects.requireNonNull(
        client.latest().answer(), "no answer: " + client.latest().error());
  }

  @Test
  void itsInstalledFromItsPackageWithItsOwnJavaAndNoneOnTheSystem() {
    assertThat(coprocessor.runAsAgent("sh", "-c", "command -v java").getExitCode()).isNotZero();
    assertThat(coprocessor.run("systemctl", "is-enabled", "frc-spotter.service").strip())
        .isEqualTo("enabled");
    assertThat(coprocessor.run("id", "frc-spotter")).contains("(systemd-journal)");
    String java =
        coprocessor.run(
            "sh",
            "-c",
            "tr '\\0' ' ' < /proc/$(systemctl show -p MainPID --value frc-spotter)/cmdline");
    assertThat(java).startsWith("/usr/lib/frc-spotter/runtime/bin/java -Xmx64m");
    assertThat(coprocessor.run("dpkg-query", "-W", "-f=${Status}", "frc-spotter"))
        .isEqualTo("install ok installed");
  }

  @Test
  void itsRootIsReadOnlyAndDataWritable() throws Exception {
    Container.ExecResult root = coprocessor.execInContainer("touch", "/etc/written");
    assertThat(root.getExitCode()).isNotZero();
    coprocessor.write("/data/frc-spotter/written", "yes\n");
    Health health = health();
    assertThat(health.boot().rootReadOnly()).isTrue();
    assertThat(health.disks()).extracting(d -> d.mount()).contains("/", "/data");
  }

  @Test
  void theStampAndHealthAreWhatTheRobotReads() throws Exception {
    Health health = health();
    Stamp stamp = health.stamp();
    assertThat(stamp.name()).isEqualTo("vision-front");
    assertThat(stamp.team()).isEqualTo(Images.TEAM);
    assertThat(stamp.address()).isEqualTo(Images.address(11));
    assertThat(stamp.bootId()).matches("[0-9a-f-]{36}");
    assertThat(health.memory().totalMb()).isPositive();
    assertThat(health.boot().uptimeSeconds()).isPositive();
    assertThat(health.problems()).noneMatch(problem -> problem.startsWith("configuration"));
    assertThat(health.probes())
        .extracting(ProbeResult::id)
        .contains("vision.unit", "kinds.command");
    // The stamp carries the hash of the probes it runs: the ones the robot's build compiles.
    ProbeSet compiled = Packs.compile(STANDIN, packs());
    assertThat(stamp.probesHash()).isEqualTo(compiled.hash());
    String json = Json.compact(health.toJson());
    System.out.printf("A health answer: %d bytes%n", json.getBytes(StandardCharsets.UTF_8).length);
  }

  /** The packs the agent runs, as the robot's build reads them. */
  static List<Pack> packs() {
    return List.of(
        Pack.parseYaml(TestImages.resource("builtin.yaml"), "builtin"),
        Pack.parseYaml(TestImages.resource("standin.yaml"), "standin"),
        Pack.parseYaml(TestImages.resource("kinds.yaml"), "kinds"));
  }

  @Test
  void itsJournalIsPagedWithItsPacksUnits() throws Exception {
    JournalPage agent = client.journal("unit=" + AgentApi.AGENT_UNIT + "&limit=50");
    assertThat(agent.entries()).anyMatch(entry -> entry.message().contains("serving on port 5808"));
    JournalPage vision = client.journal("unit=vision.service");
    assertThat(vision.entries()).isNotNull();
    AgentHttp http = new AgentHttp(coprocessor.agentHost(), coprocessor.agentPort(), 1, 5, 1 << 20);
    assertThat(statusOf(() -> http.get(AgentApi.JOURNAL + "?unit=sshd.service"))).isEqualTo(400);
  }

  @Test
  void aPacksDownloadIsServed() throws Exception {
    AgentHttp http = new AgentHttp(coprocessor.agentHost(), coprocessor.agentPort(), 1, 5, 1 << 20);
    AgentHttp.Streamed streamed = client.download("settings.zip");
    try (InputStream in = streamed.body()) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("PK stand-in backup");
    }
    assertThat(statusOf(() -> http.get("/v1/settings.zip"))).isEqualTo(404);
  }

  @FunctionalInterface
  interface Call {
    Object call() throws IOException;
  }

  private static int statusOf(Call call) throws IOException {
    try {
      call.call();
      return 200;
    } catch (AgentHttp.Answered answered) {
      return answered.status;
    }
  }

  @Test
  void eachKindOfProbeChecksTheRealThing() throws Exception {
    coprocessor.write("/data/frc-spotter/written", "yes\n");
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry("kinds.command", "pass"),
            Map.entry("kinds.command-slow", "error"),
            Map.entry("kinds.command-exit", "fail"),
            Map.entry("kinds.http", "pass"),
            Map.entry("kinds.http-missing", "fail"),
            Map.entry("kinds.file-json", "pass"),
            Map.entry("kinds.file-sha256", "pass"),
            Map.entry("kinds.file-text", "pass"),
            Map.entry("kinds.file-missing", "fail"),
            Map.entry("kinds.file-data", "pass"),
            Map.entry("kinds.unit", "pass"),
            Map.entry("kinds.unit-failed", "fail"),
            Map.entry("kinds.unit-missing", "fail"),
            Map.entry("kinds.memory", "pass"),
            Map.entry("kinds.data", "pass"),
            Map.entry("kinds.root", "pass"));
    StringBuilder said = new StringBuilder();
    for (Map.Entry<String, String> probe : expected.entrySet()) {
      ProbeResult result = client.runProbe(probe.getKey());
      said.append(probe.getKey())
          .append(": ")
          .append(result.status())
          .append(" [")
          .append(result.value())
          .append("] ")
          .append(result.detail())
          .append('\n');
      assertThat(result.status()).as(said.toString()).isEqualTo(probe.getValue());
    }
    System.out.print(said);
    assertThat(client.runProbe("kinds.command").value()).matches("13\\.\\d+");
    assertThat(client.runProbe("kinds.command-slow").detail()).isEqualTo("timed out after 1000 ms");
    assertThat(client.runProbe("kinds.http").value()).isEqualTo("v-standin");
    assertThat(client.runProbe("kinds.file-text").value()).isEqualTo("trixie");
    assertThat(client.runProbe("kinds.unit").value()).startsWith("active/running");
    assertThat(client.runProbe("kinds.unit-failed").detail()).contains("broken.service is failed");
    assertThat(client.runProbe("kinds.unit-missing").detail()).contains("isn't installed");
    // A run asked for again at once answers the last one.
    ProbeResult first = client.runProbe("kinds.file-json");
    assertThat(client.runProbe("kinds.file-json").ranMicros()).isEqualTo(first.ranMicros());
  }

  @Test
  void aUsbProbeFollowsADeviceToItsPortAndSpeed() throws Exception {
    // A second agent, as the agent's own user, reading a fixture tree of devices with real links.
    coprocessor.run(
        "systemd-run",
        "--unit=fixture-agent",
        "--uid=frc-spotter",
        "/usr/lib/frc-spotter/bin/frc-spotter",
        "--root=/srv/fixture",
        "--port=5809",
        "--bind=127.0.0.1");
    String left = "";
    for (int i = 0; i < 100 && left.isEmpty(); i++) {
      Container.ExecResult got =
          coprocessor.execInContainer(
              "curl", "-sf", "http://127.0.0.1:5809/v1/probes/camera.front-left");
      left = got.getExitCode() == 0 ? got.getStdout() : "";
      if (left.isEmpty()) {
        Thread.sleep(100);
      }
    }
    ProbeResult present = ProbeResult.fromJson(Json.parse(left));
    assertThat(present.status()).isEqualTo(ProbeResult.PASS);
    assertThat(present.value()).isEqualTo("7-1 5000 Mb/s Arducam OV9281 USB Camera");
    ProbeResult absent =
        ProbeResult.fromJson(
            Json.parse(
                coprocessor.run(
                    "curl", "-sf", "http://127.0.0.1:5809/v1/probes/camera.front-right")));
    assertThat(absent.status()).isEqualTo(ProbeResult.FAIL);
    assertThat(absent.detail()).startsWith("nothing at /dev/v4l/by-path/platform-fc880000");
    coprocessor.run("systemctl", "stop", "fixture-agent");
  }

  @Test
  void theDeployCheckComparesItsStampAndSeesWhatAnswers() throws Exception {
    Computer computer = new Computer("vision-front", 11, List.of(), 5808);
    Table table = new Table(Images.TEAM, 5808, List.of(computer));
    ProbeSet probes = Packs.compile(STANDIN, packs());
    CompiledTable same =
        new CompiledTable(
            table, "", Map.of("standinVersion", "v-standin"), Map.of("vision-front", probes));
    DeployCheck.Asked asked = DeployCheck.ask(coprocessor.agentHost(), coprocessor.agentPort());
    assertThat(asked).isInstanceOf(DeployCheck.Asked.Stamped.class);
    assertThat(DeployCheck.judge(same, computer, asked).verdict())
        .isEqualTo(DeployCheck.Verdict.WARN);
    assertThat(DeployCheck.judge(same, computer, asked).text())
        .contains("its recipe can't be checked");
    CompiledTable other =
        new CompiledTable(
            table, "", Map.of("standinVersion", "v2027.1.0"), Map.of("vision-front", probes));
    DeployCheck.Finding wrong = DeployCheck.judge(other, computer, asked);
    assertThat(wrong.verdict()).isEqualTo(DeployCheck.Verdict.FAIL);
    assertThat(wrong.text())
        .contains("\"v-standin\" on the coprocessor and \"v2027.1.0\" in this build");

    // The agent stopped, its software answering: an image builder's asker checks the software's
    // port when the agent doesn't answer, and says so.
    coprocessor.run("systemctl", "stop", "frc-spotter.service");
    try {
      DeployCheck.Asker builders =
          (address, port) -> {
            DeployCheck.Asked agent = DeployCheck.ask(address, port);
            if (!(agent instanceof DeployCheck.Asked.Unreachable)) {
              return agent;
            }
            try (java.net.Socket software = new java.net.Socket()) {
              software.connect(
                  new java.net.InetSocketAddress(address, coprocessor.softwarePort()), 1000);
              return new DeployCheck.Asked.AgentMissing(
                  "The stand-in's page", ((DeployCheck.Asked.Unreachable) agent).why());
            } catch (IOException nothing) {
              return agent;
            }
          };
      DeployCheck.Asked agentless = builders.ask(coprocessor.agentHost(), coprocessor.agentPort());
      assertThat(agentless).isInstanceOf(DeployCheck.Asked.AgentMissing.class);
      assertThat(DeployCheck.judge(same, computer, agentless).verdict())
          .isEqualTo(DeployCheck.Verdict.FAIL);
    } finally {
      coprocessor.run("systemctl", "start", "frc-spotter.service");
      coprocessor.awaitAgent();
    }
  }
}
