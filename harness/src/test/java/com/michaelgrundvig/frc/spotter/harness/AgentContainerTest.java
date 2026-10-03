package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
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
 * root read-only, nothing configured and its packs put in place: its description and values on the
 * wire, each kind of collector against real units, a web server, files and a script, and the packs
 * it mustn't trust.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentContainerTest {
  Network network;
  Coprocessor coprocessor;
  TestClient client;

  @BeforeAll
  void aCoprocessor() {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(TestImages.agent(), network, 11, "vision-front");
    coprocessor.start();
    client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
  }

  @AfterAll
  void stop() {
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

  /** The values, once every collector has run (or failed) at least once. */
  private Map<String, Spotter.FieldValue> collected() throws Exception {
    Spotter.Description description = client.describe();
    for (int i = 0; i < 100; i++) {
      Spotter.Values values = client.values();
      boolean all = true;
      Map<String, Spotter.FieldValue> byId = new HashMap<>();
      for (Spotter.FieldValue value : values.getValues()) {
        byId.put(description.getValues().get(value.getIndex()).getId(), value);
        all &= !value.getUnavailable().equals("not collected yet");
      }
      if (all && values.getRevision() == description.getRevision()) {
        return byId;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("not every collector ran within 10 s: " + client.values());
  }

  private static Spotter.FieldValue value(Map<String, Spotter.FieldValue> values, String id) {
    return Objects.requireNonNull(values.get(id), id);
  }

  @Test
  void itsInstalledFromItsPackageWithItsOwnJavaAndNoneOnTheSystem() {
    assertThat(coprocessor.runAsAgent("sh", "-c", "command -v java").getExitCode()).isNotZero();
    assertThat(coprocessor.run("systemctl", "is-enabled", "frc-spotter.service").strip())
        .isEqualTo("enabled");
    String java =
        coprocessor.run(
            "sh",
            "-c",
            "tr '\\0' ' ' < /proc/$(systemctl show -p MainPID --value frc-spotter)/cmdline");
    assertThat(java).startsWith("/usr/lib/frc-spotter/runtime/bin/java -Xmx64m");
    assertThat(coprocessor.run("dpkg-query", "-W", "-f=${Status}", "frc-spotter"))
        .isEqualTo("install ok installed");
    // Its runtime has the modules it needs, and its journal no warning that one is missing.
    assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
        .contains("serving protocol 2 on port 5808")
        .doesNotContain("NoClassDefFoundError", "Exception in");
  }

  @Test
  void itDescribesItselfOverProtocol2() throws Exception {
    Spotter.Description description = client.describe();
    Spotter.Identity identity = description.getIdentity();
    assertThat(identity.getHostname()).isEqualTo("vision-front");
    assertThat(identity.getAddresses().get(0)).isEqualTo(Images.address(11));
    assertThat(identity.getBootId()).matches("[0-9a-f-]{36}");
    Map<String, String> osRelease = new HashMap<>();
    identity.getOsRelease().forEach(e -> osRelease.put(e.getKey(), e.getValue()));
    assertThat(osRelease)
        .containsEntry("ID", "debian")
        .containsEntry("VERSION_ID", "13")
        .containsAllEntriesOf(TestImages.LABELS);
    assertThat(description.getPacks())
        .extracting(p -> p.getName() + " " + p.getVersion() + " " + p.getPushed())
        // program's pack loads; only its collector, whose program isn't root's, doesn't run.
        .containsExactly("program  false", "standin 1.0.0 false");
    assertThat(description.getValues())
        .extracting(Spotter.FieldDeclaration::getId)
        .containsExactly(
            "standin.service.running",
            "standin.broken.state",
            "standin.broken.exit",
            "standin.web.outcome",
            "standin.web.status",
            "standin.web.state",
            "standin.uptime.seconds",
            "standin.answer.answer",
            "standin.answer.mood",
            "standin.user.name",
            "standin.slow.text",
            "standin.gone.product",
            "standin.ticker.second");
    assertThat(description.getLogs())
        .extracting(Spotter.LogDeclaration::getId)
        .containsExactly("standin.log");
    assertThat(description.getActions())
        .extracting(Spotter.ActionDeclaration::getId)
        .containsExactly(
            "core.power-off",
            "core.reboot",
            "standin.hello",
            "standin.fail",
            "standin.wait",
            "standin.hurry",
            "standin.big",
            "standin.page");
    assertThat(description.getPushedPacks()).isEmpty();
    System.out.printf("A description: %d bytes%n", description.getSerializedSize());
  }

  @Test
  void eachKindOfCollectorFillsItsValuesFromTheRealThing() throws Exception {
    Map<String, Spotter.FieldValue> values = collected();
    StringBuilder said = new StringBuilder();
    values.forEach((id, value) -> said.append(id).append(": ").append(value).append('\n'));
    System.out.print(said);
    assertThat(value(values, "standin.service.running").getText())
        .as(said.toString())
        .isEqualTo("active");
    // systemctl is-active exits 3 for a failed unit, saying so: its words fill the value, and
    // its exit code the named part, for the pack's limit to judge.
    assertThat(value(values, "standin.broken.state").getText()).isEqualTo("failed");
    assertThat(value(values, "standin.broken.exit").getNumber()).isEqualTo(3);
    assertThat(value(values, "standin.web.outcome").getText()).isEqualTo("completed");
    assertThat(value(values, "standin.web.status").getNumber()).isEqualTo(200);
    assertThat(value(values, "standin.web.state").getText()).isEqualTo("up");
    assertThat(value(values, "standin.uptime.seconds").getNumber()).isPositive();
    assertThat(value(values, "standin.answer.answer").getNumber()).isEqualTo(42);
    assertThat(value(values, "standin.answer.mood").getStatus().getLevel())
        .isEqualTo(Spotter.Level.LEVEL_OK);
    assertThat(value(values, "standin.answer.mood").getStatus().getMessage()).isEqualTo("fine");
    // Commands run as the agent's own user, never root.
    assertThat(value(values, "standin.user.name").getText()).isEqualTo("frc-spotter");
    assertThat(value(values, "standin.slow.text").getUnavailable()).isEqualTo("timed out after 1s");
    assertThat(value(values, "standin.gone.product").getUnavailable())
        .isEqualTo("no such file: /sys/bus/usb/devices/7-1/product");
    // The slow one's sleep was killed, not left running.
    assertThat(coprocessor.run("sh", "-c", "pgrep -u frc-spotter -x sleep | wc -l").strip())
        .isIn("0", "1");
  }

  @Test
  void whatItCantTrustIsIgnoredAndSaysWhy() throws Exception {
    assertThat(client.describe().getProblems())
        .containsExactlyInAnyOrder(
            "/etc/frc-spotter/packs/mine/pack.yaml: isn't root's (its owner is nobody),"
                + " ignored",
            "/etc/frc-spotter/packs/shared/pack.yaml: may be written by its group or others (mode"
                + " 664), ignored",
            "/etc/frc-spotter/packs/program/check: isn't root's (its owner is nobody), so"
                + " program's collector check isn't run");
    assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
        .contains("/etc/frc-spotter/packs/mine/pack.yaml: isn't root's");
  }

  @Test
  void itAnswersInJsonForPeopleAndRefusesWhatItDoesntServe() throws Exception {
    Container.ExecResult json =
        coprocessor.execInContainer(
            "curl", "-s", "-H", "Accept: application/json", "http://127.0.0.1:5808/v2/describe");
    assertThat(json.getStdout())
        .contains("\"hostname\": \"vision-front\"", "\"id\": \"core.reboot\"");
    HttpResponse<byte[]> gone =
        client.send(
            HttpRequest.newBuilder(
                URI.create(
                    "http://"
                        + coprocessor.agentHost()
                        + ":"
                        + coprocessor.agentPort()
                        + "/v1/health")));
    assertThat(gone.statusCode()).isEqualTo(404);
    assertThat(gone.headers().firstValue(Protocol.HEADER)).hasValue(Protocol.VERSION);
    assertThat(TestClient.problem(gone).getMessage()).isEqualTo("no such resource: /v1/health");
  }

  @Test
  void itsRootIsReadOnlyAndDataWritable() throws Exception {
    Container.ExecResult root = coprocessor.execInContainer("touch", "/etc/written");
    assertThat(root.getExitCode()).isNotZero();
    coprocessor.write("/data/frc-spotter/written", "yes\n");
  }

  @Test
  void aStopIsCleanNotAFailure() {
    // Java exits 143 on SIGTERM, which the unit counts as success: a stop leaves it inactive, never
    // failed.
    coprocessor.run("systemctl", "stop", "frc-spotter.service");
    try {
      assertThat(
              coprocessor
                  .run("systemctl", "show", "frc-spotter.service", "--property=ActiveState,Result")
                  .strip())
          .contains("ActiveState=inactive", "Result=success");
    } finally {
      coprocessor.run("systemctl", "start", "frc-spotter.service");
      coprocessor.awaitAgent();
    }
  }
}
