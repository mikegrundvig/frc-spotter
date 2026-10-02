package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.AgentHttp;
import com.michaelgrundvig.frc.spotter.client.DeployCheck;
import com.michaelgrundvig.frc.spotter.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * root read-only, nothing configured and its packs copied in, and the robot's client calling it:
 * every endpoint on the wire, the deploy check against its identity, the packs it mustn't trust,
 * and each kind of probe against real files, units, a web server, and its own measurements; a USB
 * probe against a fixture tree of devices.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentContainerTest {
  Network network;
  Coprocessor coprocessor;
  AgentClient client;

  @BeforeAll
  void aCoprocessor() {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(TestImages.agent(), network, 11, "vision-front");
    coprocessor.start();
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
  void itsIdentityAndHealthAreWhatTheRobotReads() throws Exception {
    Health health = health();
    Stamp stamp = health.stamp();
    assertThat(stamp.hostname()).isEqualTo("vision-front");
    assertThat(stamp.addresses()).first().isEqualTo(Images.address(11));
    assertThat(stamp.bootId()).matches("[0-9a-f-]{36}");
    // A container's interface is virtual (no device behind it), so it has no wired MAC to report.
    assertThat(stamp.mac()).isEmpty();
    assertThat(stamp.uptimeSeconds()).isPositive();
    assertThat(stamp.osRelease())
        .containsEntry("ID", "debian")
        .containsEntry("VERSION_ID", "13")
        .containsAllEntriesOf(TestImages.LABELS);
    assertThat(health.memory().totalMb()).isPositive();
    assertThat(health.boot().uptimeSeconds()).isPositive();
    assertThat(health.problems()).noneMatch(problem -> problem.startsWith("configuration"));
    assertThat(health.probes())
        .extracting(ProbeResult::id)
        .contains("vision.unit", "kinds.command");
    String json = Json.compact(health.toJson());
    System.out.printf("A health answer: %d bytes%n", json.getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void itReportsItsLinkItsUsbAndItsClock() throws Exception {
    Health health = health();
    System.out.println("Its links: " + health.network());
    System.out.println("Its USB devices: " + health.usb());
    System.out.println("Its clock: " + health.clock());
    // A container's interface is virtual: up, with no device behind it.
    assertThat(health.network()).anyMatch(link -> link.up() && !link.physical());
    // A container sees the host's USB devices, or none: either way, read without a problem.
    assertThat(health.problems())
        .noneMatch(p -> p.startsWith("network:") || p.startsWith("usb:") || p.startsWith("clock:"));
    // Synchronized is the kernel's (the host's, in a container); no NTP daemon runs here.
    assertThat(health.clock().synced()).isNotNull();
    assertThat(health.clock().source()).isEmpty();
  }

  @Test
  void theDriveTimerReadsTheDriveAsRootForTheAgent() throws Exception {
    assertThat(coprocessor.run("systemctl", "is-enabled", "frc-spotter-drive.timer").strip())
        .isEqualTo("enabled");
    assertThat(coprocessor.run("systemctl", "is-active", "frc-spotter-drive.timer").strip())
        .isEqualTo("active");
    // No drive: nothing written, and the agent reports none.
    coprocessor.run("systemctl", "start", "frc-spotter-drive.service");
    assertThat(coprocessor.run("stat", "-c", "%U %a", "/run/frc-spotter").strip())
        .isEqualTo("root 755");
    assertThat(health().drive()).isNull();
    // A drive (nvme-cli stood in for): its reports written whole, root's, and read by the agent.
    coprocessor.run("touch", "/dev/nvme0");
    try {
      coprocessor.run("systemctl", "start", "frc-spotter-drive.service");
      assertThat(coprocessor.run("stat", "-c", "%U %a", "/run/frc-spotter/nvme-smart-log.json"))
          .isEqualTo("root 644\n");
      Drive drive = null;
      for (int i = 0; i < 50 && drive == null; i++) {
        Thread.sleep(100);
        Health latest = client.latest().answer();
        drive = latest == null ? null : latest.drive();
      }
      assertThat(drive).isNotNull();
      assertThat(Objects.requireNonNull(drive).device())
          .isEqualTo("/dev/nvme0 (Spotter Test NVMe)");
      assertThat(drive.celsius()).isEqualTo(40.9);
      assertThat(drive.unsafeShutdowns()).isEqualTo(23);
    } finally {
      coprocessor.run("rm", "-f", "/dev/nvme0");
    }
    // The drive gone: its reports go too.
    coprocessor.run("systemctl", "start", "frc-spotter-drive.service");
    assertThat(coprocessor.run("sh", "-c", "ls /run/frc-spotter | wc -l").strip()).isEqualTo("0");
    System.out.println(
        "The drive timer's journal: "
            + coprocessor.run(
                "journalctl", "-u", "frc-spotter-drive.service", "--no-pager", "-o", "cat"));
  }

  @Test
  void aPackFileItCantTrustIsIgnoredAndSaysWhy() throws Exception {
    assertThat(health().problems())
        .contains(
            "pack ignored: /etc/frc-spotter/packs/mine.yaml isn't root's (its owner is user 65534)",
            "pack ignored: /etc/frc-spotter/packs/shared.yaml may be written by its group or"
                + " others (mode 664): chmod go-w it");
    assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
        .contains("pack ignored: /etc/frc-spotter/packs/mine.yaml isn't root's");
  }

  @Test
  void itTakesAShutdownFromTheControllerOnItsOwnNetworkWithNothingConfigured() throws Exception {
    // Its address is 10.99.71.11, so the robot controller is 10.99.71.2: this test isn't it.
    AgentHttp http = new AgentHttp(coprocessor.agentHost(), coprocessor.agentPort(), 1, 5, 1 << 20);
    assertThat(http.post(AgentApi.SHUTDOWN)).isEqualTo(403);
    assertThat(coprocessor.run("systemctl", "is-active", "vision.service").strip())
        .isEqualTo("active");
    assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
        .contains(": not the robot controller");
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
            Map.entry("kinds.command-shell", "pass"),
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
    assertThat(client.runProbe("kinds.command-shell").value()).isEqualTo("42");
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
    assertThat(present.status()).as("%s", present).isEqualTo(ProbeResult.PASS);
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
  void theDeployCheckComparesItsIdentityAndSeesWhatAnswers() throws Exception {
    DeployCheck.Expected same =
        new DeployCheck.Expected(
            "vision-front",
            Images.address(11),
            5808,
            Map.of("IMAGE_ID", "spotter-test", "TEST_STANDIN_VERSION", "v-standin"));
    DeployCheck.Asked asked = DeployCheck.ask(coprocessor.agentHost(), coprocessor.agentPort());
    assertThat(asked).isInstanceOf(DeployCheck.Asked.Stamped.class);
    DeployCheck.Finding ok = DeployCheck.judge(same, asked);
    assertThat(ok.verdict()).as("%s", ok).isEqualTo(DeployCheck.Verdict.OK);
    DeployCheck.Expected other =
        new DeployCheck.Expected(
            "vision-front", Images.address(11), 5808, Map.of("TEST_STANDIN_VERSION", "v2027.1.0"));
    DeployCheck.Finding wrong = DeployCheck.judge(other, asked);
    assertThat(wrong.verdict()).isEqualTo(DeployCheck.Verdict.FAIL);
    assertThat(wrong.text())
        .contains("\"v-standin\" on the coprocessor and \"v2027.1.0\" in this build");
    DeployCheck.Finding elsewhere =
        DeployCheck.judge(new DeployCheck.Expected("vision-back", Images.address(12)), asked);
    assertThat(elsewhere.verdict()).isEqualTo(DeployCheck.Verdict.FAIL);
    assertThat(elsewhere.text())
        .contains("its hostname is \"vision-front\", not \"vision-back\"")
        .contains("without " + Images.address(12));

    // The agent stopped, its software answering: a caller's asker checks the software's port
    // when the agent doesn't answer, and says so.
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
      assertThat(DeployCheck.judge(same, agentless).verdict()).isEqualTo(DeployCheck.Verdict.FAIL);
    } finally {
      coprocessor.run("systemctl", "start", "frc-spotter.service");
      coprocessor.awaitAgent();
    }
  }
}
