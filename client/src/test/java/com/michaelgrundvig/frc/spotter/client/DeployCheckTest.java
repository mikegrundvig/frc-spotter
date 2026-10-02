package com.michaelgrundvig.frc.spotter.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Asked;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Finding;
import com.michaelgrundvig.frc.spotter.client.DeployCheck.Verdict;
import com.michaelgrundvig.frc.spotter.table.Board;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import com.michaelgrundvig.frc.spotter.table.Computer;
import com.michaelgrundvig.frc.spotter.table.Table;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
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
 * deploy.
 */
class DeployCheckTest {
  private static final Computer FRONT =
      new Computer("vision-front", 11, Board.ORANGEPI_5, List.of("front-left"), 5808);
  private static final Computer BACK =
      new Computer("vision-back", 12, Board.ORANGEPI_5, List.of("back-left"), 5808);
  private static final CompiledTable TABLE =
      new CompiledTable(
          new Table(24680, 5808, List.of(FRONT, BACK)),
          "recipe-1",
          Map.of("visionVersion", "v2027.1.0"),
          Map.of());

  /** The stamp this build's image for a computer would carry, with its software's version. */
  private static Stamp stampOf(Computer computer, String version, String recipe) {
    return new Stamp(
        computer.name(),
        24680,
        TABLE.table().ip(computer),
        "r1",
        recipe,
        "",
        Map.of("visionVersion", version),
        "",
        "boot",
        "02:00:00:00:00:0b");
  }

  @Test
  void aCoprocessorRunningThisBuildsImagePasses() {
    Finding finding =
        DeployCheck.judge(TABLE, FRONT, new Asked.Stamped(stampOf(FRONT, "v2027.1.0", "recipe-1")));

    assertThat(finding.verdict()).isEqualTo(Verdict.OK);
    assertThat(finding.text()).isEqualTo("image r1, as this build's");
  }

  @Test
  void anotherVersionOfItsSoftwareFailsNamingBoth() {
    Finding finding =
        DeployCheck.judge(TABLE, FRONT, new Asked.Stamped(stampOf(FRONT, "v2026.3.4", "recipe-1")));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text()).contains("v2026.3.4", "v2027.1.0", "10.246.80.11");
  }

  /** An image from another recipe is worth flashing when convenient, not a failed deploy. */
  @Test
  void anotherRecipeAloneWarns() {
    Finding finding =
        DeployCheck.judge(TABLE, FRONT, new Asked.Stamped(stampOf(FRONT, "v2027.1.0", "recipe-0")));

    assertThat(finding.verdict()).isEqualTo(Verdict.WARN);
    assertThat(finding.text()).contains("recipe-0", "recipe-1", "when convenient");
  }

  /** A build without a recipe hash can't judge the recipe: it says so, and judges the rest. */
  @Test
  void aBuildWithoutARecipeHashWarnsAndJudgesTheRest() {
    CompiledTable unhashed =
        new CompiledTable(
            new Table(24680, 5808, List.of(FRONT, BACK)),
            "",
            Map.of("visionVersion", "v2027.1.0"),
            Map.of());

    Finding right =
        DeployCheck.judge(unhashed, FRONT, new Asked.Stamped(stampOf(FRONT, "v2027.1.0", "x")));
    Finding wrong =
        DeployCheck.judge(unhashed, FRONT, new Asked.Stamped(stampOf(FRONT, "v2026.3.4", "x")));

    assertThat(right.verdict()).isEqualTo(Verdict.WARN);
    assertThat(right.text()).contains("recipe can't be checked");
    assertThat(wrong.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(wrong.text()).contains("v2026.3.4");
  }

  @Test
  void anotherComputersImageFails() {
    Finding finding =
        DeployCheck.judge(TABLE, FRONT, new Asked.Stamped(stampOf(BACK, "v2027.1.0", "recipe-1")));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text()).contains("vision-back", "vision-front");
  }

  /** Its software up and its agent not (as a builder's asker finds): the agent isn't running. */
  @Test
  void itsSoftwareWithoutItsAgentFailsSayingTheAgentIsntRunning() {
    Finding finding =
        DeployCheck.judge(
            TABLE, FRONT, new Asked.AgentMissing("Its vision page", "Connection refused"));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text())
        .isEqualTo(
            "Its vision page answers at 10.246.80.11, but its agent doesn't (Connection refused):"
                + " the agent isn't running, or this isn't this build's image. Read its journal, or"
                + " flash it with this build's release");
  }

  /** Something on the agent's port that isn't this agent is a different image. */
  @Test
  void anotherAnswerOnTheAgentsPortIsADifferentImage() {
    Finding finding =
        DeployCheck.judge(TABLE, FRONT, new Asked.Unreadable("answered 404 to /v1/stamp"));

    assertThat(finding.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(finding.text())
        .isEqualTo(
            "a different image answers at 10.246.80.11: its agent's port answered, but not with a"
                + " stamp (answered 404 to /v1/stamp). Flash it with this build's release");
  }

  /** A coprocessor restarting is asked once more, two seconds on, before it counts. */
  @Test
  void aCoprocessorNotAnsweringIsAskedOnceMore() {
    List<Long> slept = new ArrayList<>();
    List<String> asked = new ArrayList<>();
    Deque<Asked> answers =
        new ArrayDeque<>(
            List.of(
                new Asked.Unreachable("connect timed out"),
                new Asked.Stamped(stampOf(FRONT, "v2027.1.0", "recipe-1"))));
    CompiledTable one =
        new CompiledTable(new Table(24680, 5808, List.of(FRONT)), "recipe-1", Map.of(), Map.of());

    List<Finding> findings =
        DeployCheck.check(
            one,
            24680,
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
    CompiledTable one =
        new CompiledTable(new Table(24680, 5808, List.of(FRONT)), "recipe-1", Map.of(), Map.of());

    DeployCheck.check(
        one,
        24680,
        (address, port) -> new Asked.Stamped(stampOf(FRONT, "v2027.1.0", "recipe-1")),
        slept::add);

    assertThat(slept).isEmpty();
  }

  /** The template's example table (team 0) is checked against nothing: it says so. */
  @Test
  void theTemplatesExampleTableIsNotChecked() {
    CompiledTable example =
        new CompiledTable(new Table(0, 5808, List.of(FRONT)), "recipe-1", Map.of(), Map.of());

    List<Finding> findings =
        DeployCheck.check(
            example,
            24680,
            (address, port) -> {
              throw new AssertionError("asked " + address);
            },
            millis -> {});

    assertThat(findings)
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.verdict()).isEqualTo(Verdict.WARN);
              assertThat(finding.text()).contains("the template's example (team 0)");
            });
  }

  @Test
  void aCoprocessorThatDoesntAnswerOnlyWarns() {
    Finding finding = DeployCheck.judge(TABLE, FRONT, new Asked.Unreachable("connect timed out"));

    assertThat(finding.verdict()).isEqualTo(Verdict.WARN);
    assertThat(finding.text()).contains("deploying anyway");
  }

  @Test
  void everyComputerIsAskedAtItsAddress() {
    List<Finding> findings =
        DeployCheck.check(
            TABLE,
            24680,
            (address, port) -> {
              assertThat(port).isEqualTo(5808);
              return address.equals("10.246.80.11")
                  ? new Asked.Stamped(stampOf(FRONT, "v2027.1.0", "recipe-1"))
                  : new Asked.Unreachable("connect timed out");
            },
            millis -> {});

    assertThat(findings)
        .extracting(Finding::computer, Finding::verdict)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("vision-front", Verdict.OK),
            org.assertj.core.groups.Tuple.tuple("vision-back", Verdict.WARN));
  }

  @Test
  void aTableForAnotherTeamWarns() {
    List<Finding> findings =
        DeployCheck.check(
            TABLE, 24681, (address, port) -> new Asked.Unreachable("no route"), millis -> {});

    assertThat(findings.get(0).verdict()).isEqualTo(Verdict.WARN);
    assertThat(findings.get(0).text()).contains("team 24680's", "team 24681's");
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
    boolean failed =
        DeployCheck.report(List.of(new Finding("vision-front", Verdict.FAIL, "wrong")), print);
    boolean empty = DeployCheck.report(List.of(), print);
    boolean silent =
        DeployCheck.report(List.of(new Finding("vision-front", Verdict.WARN, "nothing")), print);

    assertThat(ok).isTrue();
    assertThat(failed).isFalse();
    assertThat(empty).isTrue();
    assertThat(silent).isTrue();
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(
            "Coprocessor check: OK vision-front: fine",
            "Coprocessor check: WARN vision-back: nothing answers",
            "Coprocessor check: 1 answered, running this build's image.",
            "Coprocessor check: FAILED. Nothing was deployed.",
            "the table has no coprocessors",
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

  /** An agent busy twice is left unchecked, with a warning, not a failure. */
  @Test
  void aBusyAgentIsLeftUncheckedWithAWarning() {
    List<Long> slept = new ArrayList<>();
    CompiledTable one =
        new CompiledTable(new Table(24680, 5808, List.of(FRONT)), "recipe-1", Map.of(), Map.of());

    List<Finding> findings =
        DeployCheck.check(
            one, 24680, (address, port) -> new Asked.Busy("answered 503 to /v1/stamp"), slept::add);

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
