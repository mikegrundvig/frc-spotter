package com.michaelgrundvig.frc.spotter.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Asked;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Expected;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Finding;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Verdict;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The deploy's coprocessor check, its decisions without a network: what each answer means for the
 * deploy, against what the robot program expects of each coprocessor.
 */
class DeployCheckTest {
  private static final Expected FRONT =
      new Expected(
          "vision-front",
          "10.246.80.11",
          5808,
          Map.of("IMAGE_ID", "vision-orangepi", "PADDOCK_PHOTONVISION_VERSION", "v2027.1.0"));
  private static final Expected BACK = new Expected("vision-back", "10.246.80.12");

  /** The identity a coprocessor answers with: its hostname, its address, and its labels. */
  private static Stamp stampOf(String hostname, String address, Map<String, String> labels) {
    return new Stamp(hostname, List.of(address), "02:00:00:00:00:0b", "boot", 12.5, labels);
  }

  private static Stamp front(String version) {
    return stampOf(
        "vision-front",
        "10.246.80.11",
        Map.of(
            "ID", "debian",
            "IMAGE_ID", "vision-orangepi",
            "PADDOCK_PHOTONVISION_VERSION", version));
  }

  @Test
  void aCoprocessorAsExpectedPasses() {
    Finding finding = DeployCheck.judge(FRONT, new Asked.Stamped(front("v2027.1.0")));

    assertThat(finding.verdict()).isEqualTo(Verdict.OK);
    assertThat(finding.text())
        .isEqualTo(
            "vision-front at 10.246.80.11, with IMAGE_ID, PADDOCK_PHOTONVISION_VERSION as"
                + " expected");
    assertThat(
            DeployCheck.judge(
                    BACK, new Asked.Stamped(stampOf("vision-back", "10.246.80.12", Map.of())))
                .text())
        .isEqualTo("vision-back at 10.246.80.12");
  }

  @Test
  void anotherLabelValueFailsNamingBoth() {
    Finding finding = DeployCheck.judge(FRONT, new Asked.Stamped(front("v2026.3.4")));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text())
        .contains(
            "PADDOCK_PHOTONVISION_VERSION is \"v2026.3.4\" on the coprocessor and \"v2027.1.0\" in"
                + " this build",
            "10.246.80.11");
  }

  @Test
  void aMissingLabelFails() {
    Stamp unlabeled = stampOf("vision-front", "10.246.80.11", Map.of("ID", "debian"));
    assertThat(DeployCheck.differences(FRONT, unlabeled))
        .containsExactly(
            "IMAGE_ID isn't in its os-release, and \"vision-orangepi\" is expected",
            "PADDOCK_PHOTONVISION_VERSION isn't in its os-release, and \"v2027.1.0\" is expected");
  }

  @Test
  void anotherComputerFailsNamingItsHostnameAndAddresses() {
    Finding finding =
        DeployCheck.judge(
            FRONT, new Asked.Stamped(stampOf("vision-back", "10.246.80.12", Map.of())));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text())
        .contains(
            "its hostname is \"vision-back\", not \"vision-front\"",
            "its addresses are 10.246.80.12, without 10.246.80.11");
    assertThat(
            DeployCheck.differences(BACK, new Stamp("vision-back", List.of(), "", "", 1, Map.of())))
        .containsExactly("its addresses are none, without 10.246.80.12");
  }

  /** Its software up and its agent not (as a caller's asker finds): the agent isn't running. */
  @Test
  void itsSoftwareWithoutItsAgentFailsSayingTheAgentIsntRunning() {
    Finding finding =
        DeployCheck.judge(FRONT, new Asked.AgentMissing("Its vision page", "Connection refused"));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text())
        .isEqualTo(
            "Its vision page answers at 10.246.80.11, but its agent doesn't (Connection refused):"
                + " the agent isn't running. Read its journal");
  }

  /** Something on the agent's port that isn't Spotter's agent, or an older one. */
  @Test
  void anotherAnswerOnTheAgentsPortFails() {
    Finding finding = DeployCheck.judge(FRONT, new Asked.Unreadable("answered 404 to /v1/stamp"));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text())
        .isEqualTo(
            "something else answers at 10.246.80.11: its agent's port answered, but not with an"
                + " identity (answered 404 to /v1/stamp). Is Spotter's agent 0.3.0 or newer"
                + " installed there?");
  }

  /** A coprocessor restarting is asked once more, two seconds on, before it counts. */
  @Test
  void aCoprocessorNotAnsweringIsAskedOnceMore() {
    List<Long> slept = new ArrayList<>();
    List<String> asked = new ArrayList<>();
    Deque<Asked> answers =
        new ArrayDeque<>(
            List.of(
                new Asked.Unreachable("connect timed out"), new Asked.Stamped(front("v2027.1.0"))));

    List<Finding> findings =
        DeployCheck.check(
            List.of(FRONT),
            (address, port) -> {
              asked.add(address);
              return answers.removeFirst();
            },
            slept::add);

    assertThat(findings).singleElement().extracting(Finding::verdict).isEqualTo(Verdict.OK);
    assertThat(asked).containsExactly("10.246.80.11", "10.246.80.11");
    assertThat(slept).containsExactly(2000L);
  }

  /** One that answers the first time is asked once. */
  @Test
  void aCoprocessorThatAnswersIsAskedOnce() {
    List<Long> slept = new ArrayList<>();

    DeployCheck.check(
        List.of(FRONT), (address, port) -> new Asked.Stamped(front("v2027.1.0")), slept::add);

    assertThat(slept).isEmpty();
  }

  @Test
  void theRealSleeperWaitsAndKeepsAnInterrupt() {
    long start = System.nanoTime();
    DeployCheck.Sleeper.REAL.sleep(20);
    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(20_000_000L);
    Thread.currentThread().interrupt();
    DeployCheck.Sleeper.REAL.sleep(10_000);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void aCoprocessorThatDoesntAnswerOnlyWarns() {
    Finding finding = DeployCheck.judge(FRONT, new Asked.Unreachable("connect timed out"));

    assertThat(finding.verdict()).isEqualTo(Verdict.WARN);
    assertThat(finding.text()).contains("deploying anyway");
  }

  @Test
  void everyCoprocessorIsAskedAtItsAddressAndPort() {
    Expected side = new Expected("vision-side", "10.246.80.13", 5809, Map.of());
    List<Finding> findings =
        DeployCheck.check(
            List.of(FRONT, BACK, side),
            (address, port) -> {
              assertThat(port).isEqualTo(address.endsWith(".13") ? 5809 : 5808);
              return address.equals("10.246.80.11")
                  ? new Asked.Stamped(front("v2027.1.0"))
                  : new Asked.Unreachable("connect timed out");
            },
            millis -> {});

    assertThat(findings)
        .extracting(Finding::computer, Finding::verdict)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("vision-front", Verdict.OK),
            org.assertj.core.groups.Tuple.tuple("vision-back", Verdict.WARN),
            org.assertj.core.groups.Tuple.tuple("vision-side", Verdict.WARN));
  }

  @Test
  void theReportSaysWhetherTheDeployGoesOn() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);

    boolean ok =
        DeployCheck.report(
            List.of(
                new Finding("vision-front", Verdict.OK, "fine"),
                new Finding("vision-back", Verdict.WARN, "nothing answers")),
            print);
    boolean both =
        DeployCheck.report(
            List.of(
                new Finding("vision-front", Verdict.OK, "fine"),
                new Finding("vision-back", Verdict.OK, "fine")),
            print);
    boolean failed =
        DeployCheck.report(List.of(new Finding("vision-front", Verdict.FAIL, "wrong")), print);
    boolean empty = DeployCheck.report(List.of(), print);
    boolean silent =
        DeployCheck.report(List.of(new Finding("vision-front", Verdict.WARN, "nothing")), print);

    assertThat(ok).isTrue();
    assertThat(both).isTrue();
    assertThat(failed).isFalse();
    assertThat(empty).isTrue();
    assertThat(silent).isTrue();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(
            "Coprocessor check: OK vision-front: fine",
            "Coprocessor check: WARN vision-back: nothing answers",
            "Coprocessor check: 1 answered as this build expects.",
            "Coprocessor check: 2 answered, each as this build expects.",
            "Coprocessor check: FAILED. Nothing was deployed.",
            "no coprocessors are expected",
            "none answered, so none was compared with this build");
  }

  @Test
  void askingNobodyIsUnreachable() throws Exception {
    // A closed port on this computer: refused at once.
    int free;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      free = socket.getLocalPort();
    }

    Asked asked = DeployCheck.ask("127.0.0.1", free);

    assertThat(asked).isInstanceOf(Asked.Unreachable.class);
  }

  @Test
  void askingReadsTheIdentityOrSaysWhyNot() throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4);
    Deque<String> bodies =
        new ArrayDeque<>(
            List.of(
                "{\"hostname\":\"vision-front\",\"addresses\":[\"10.246.80.11\"]}",
                "{\"name\":\"vision-front\",\"team\":2468}",
                "not json"));
    server.createContext(
        "/v1/stamp",
        exchange -> {
          byte[] body = bodies.removeFirst().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      int port = server.getAddress().getPort();
      Asked stamped = DeployCheck.ask("127.0.0.1", port);
      assertThat(stamped).isInstanceOf(Asked.Stamped.class);
      assertThat(((Asked.Stamped) stamped).stamp().hostname()).isEqualTo("vision-front");
      // An agent from before 0.3.0 answers a stamp with no hostname.
      assertThat(DeployCheck.ask("127.0.0.1", port))
          .isEqualTo(new Asked.Unreadable("its answer has no hostname"));
      assertThat(DeployCheck.ask("127.0.0.1", port)).isInstanceOf(Asked.Unreadable.class);
    } finally {
      server.stop(0);
    }
  }

  /** An agent busy twice is left unchecked, with a warning, not a failure. */
  @Test
  void aBusyAgentIsLeftUncheckedWithAWarning() {
    List<Long> slept = new ArrayList<>();

    List<Finding> findings =
        DeployCheck.check(
            List.of(FRONT),
            (address, port) -> new Asked.Busy("answered 503 to /v1/stamp"),
            slept::add);

    assertThat(findings)
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.verdict()).isEqualTo(Verdict.WARN);
              assertThat(finding.text()).contains("is busy", "not checked");
            });
    assertThat(slept).containsExactly(2000L);
  }
}
