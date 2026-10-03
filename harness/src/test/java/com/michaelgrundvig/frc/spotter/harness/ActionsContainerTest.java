package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Network;

/**
 * Actions on a board, from its controller (this test, named with {@code --controller}, as it
 * reaches the agent through port forwarding): their runs' lifecycle, what's kept of them across a
 * restart, and the stand-in pack's log paged by SPOTTER_*.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActionsContainerTest {
  Network network;
  Coprocessor coprocessor;
  TestClient client;

  @BeforeAll
  void aCoprocessor() throws Exception {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(TestImages.agent(), network, 42, "vision-actions");
    coprocessor.start();
    coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
    client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
  }

  @AfterAll
  void stop() {
    if (coprocessor != null) {
      System.out.println(
          "The agent's journal:\n"
              + coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager", "-n", "40"));
      coprocessor.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  private static Map<String, Spotter.FieldValue> response(Spotter.RunState state) {
    Map<String, Spotter.FieldValue> byName = new LinkedHashMap<>();
    for (Spotter.FieldValue value : state.getResult().getResponse()) {
      byName.put(value.getName(), value);
    }
    return byName;
  }

  /** A finished run's response field, by its name. */
  private static Spotter.FieldValue part(Spotter.RunState state, String name) {
    return java.util.Objects.requireNonNull(response(state).get(name), name);
  }

  private static byte[] text(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aRunSucceedsWithItsResponseAndItsLogOnTheStream() throws Exception {
    try (TestClient.Stream stream = client.stream("?heartbeat=1s")) {
      for (int i = 0; i < 3; i++) {
        stream.next(Duration.ofSeconds(5));
      }
      String run = client.start("standin.hello", text("robot\n"));
      List<String> seen = new ArrayList<>();
      Spotter.RunResult finished = null;
      while (finished == null) {
        Spotter.Event event = stream.nextNotHeartbeat(Duration.ofSeconds(10));
        if (!event.hasRun() || !event.getRun().getRun().equals(run)) {
          continue;
        }
        Spotter.RunEvent change = event.getRun();
        assertThat(change.getAction()).isEqualTo("standin.hello");
        if (change.hasStarted()) {
          seen.add("started");
        } else if (change.hasLog()) {
          seen.add(change.getLog().getLevel() + " " + change.getLog().getMessage());
        } else {
          finished = change.getFinished();
        }
      }
      assertThat(seen)
          .containsExactly(
              "started", "LOG_LEVEL_INFO greeting robot", "LOG_LEVEL_WARNING almost done");
      assertThat(finished.getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
      Spotter.RunState state = client.finished(run);
      assertThat(part(state, "outcome").getText()).isEqualTo("completed");
      assertThat(part(state, "exit").getNumber()).isZero();
      assertThat(part(state, "greeting").getText()).isEqualTo("hello, robot");
      Spotter.LogPage log = client.get(Protocol.RUNS + run + "/log", Spotter.LogPage.newInstance());
      assertThat(log.getEntries())
          .extracting(Spotter.LogEntry::getMessage)
          .containsExactly("greeting robot", "almost done");
    }
  }

  @Test
  void aFailingExitCompletesWithItsCodeForThePacksLimit() throws Exception {
    Spotter.RunState state = client.finished(client.start("standin.fail", new byte[0]));
    assertThat(state.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    assertThat(part(state, "exit").getNumber()).isEqualTo(3);
    assertThat(
            client
                .get(Protocol.RUNS + state.getRun() + "/log", Spotter.LogPage.newInstance())
                .getEntries()
                .get(0)
                .getMessage())
        .isEqualTo("failing");
  }

  @Test
  void aRunIsCancelledPolitelyAndOneOfEachActionRunsAtOnceAndTwoInAll() throws Exception {
    String waiting = client.start("standin.wait", new byte[0]);
    for (int i = 0;
        i < 100
            && client
                    .get(Protocol.RUNS + waiting + "/log", Spotter.LogPage.newInstance())
                    .getEntries()
                    .length()
                == 0;
        i++) {
      Thread.sleep(100);
    }
    assertThat(client.post(Protocol.ACTIONS + "standin.wait", new byte[0], null).statusCode())
        .isEqualTo(409);
    String hurrying = client.start("standin.hurry", new byte[0]);
    var third = client.post(Protocol.ACTIONS + "standin.fail", new byte[0], null);
    assertThat(third.statusCode()).isEqualTo(503);
    assertThat(TestClient.problem(third).getMessage()).startsWith("2 actions are running");

    assertThat(client.delete(Protocol.RUNS + waiting, null).statusCode()).isEqualTo(204);
    Spotter.RunState cancelled = client.finished(waiting);
    assertThat(cancelled.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
    assertThat(
            client
                .get(Protocol.RUNS + waiting + "/log", Spotter.LogPage.newInstance())
                .getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsSubsequence("waiting", "stopping");
    assertThat(client.delete(Protocol.RUNS + waiting, null).statusCode()).isEqualTo(409);

    Spotter.RunState timedOut = client.finished(hurrying);
    assertThat(timedOut.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_TIMED_OUT);
    assertThat(timedOut.getResult().getOutcomeMessage()).isEqualTo("timed out after 2s");
  }

  @Test
  void outputPastTheMostKeptIsSaidAndWhatsKeptIsBounded() throws Exception {
    Spotter.RunState big = client.finished(client.start("standin.big", new byte[0]));
    assertThat(part(big, "output").getUnavailable())
        .isEqualTo("it printed more than the 1024 KiB kept");
    // The call completed: its exit code stands.
    assertThat(part(big, "exit").getNumber()).isZero();
    assertThat(
            coprocessor
                .run("stat", "-c", "%s", "/run/frc-spotter/runs/" + big.getRun() + "/output")
                .strip())
        .isEqualTo("1048576");
  }

  @Test
  void anHttpActionAsksTheBoardsOwnPage() throws Exception {
    Spotter.RunState page = client.finished(client.start("standin.page", new byte[0]));
    assertThat(part(page, "status").getNumber()).isEqualTo(200);
    assertThat(part(page, "state").getText()).isEqualTo("up");
  }

  @Test
  void runsSurviveARestartAndOnesThatWereGoingAreLost() throws Exception {
    String done = client.start("standin.fail", new byte[0]);
    client.finished(done);
    String going = client.start("standin.wait", new byte[0]);
    coprocessor.run("systemctl", "restart", "frc-spotter.service");
    coprocessor.awaitAgent();
    Spotter.RunState lost = client.get(Protocol.RUNS + going, Spotter.RunState.newInstance());
    assertThat(lost.getRunning()).isFalse();
    assertThat(lost.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_LOST);
    assertThat(lost.getResult().getOutcomeMessage()).isEqualTo("the agent restarted while it ran");
    // A finished run stays fetchable across the restart, and the stream lists both.
    assertThat(
            client
                .get(Protocol.RUNS + done, Spotter.RunState.newInstance())
                .getResult()
                .getOutcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    try (TestClient.Stream stream = client.stream("")) {
      stream.next(Duration.ofSeconds(5));
      stream.next(Duration.ofSeconds(5));
      assertThat(stream.next(Duration.ofSeconds(5)).event().getRuns().getRuns())
          .extracting(Spotter.RunState::getRun)
          .contains(done, going);
    }
    // Its process went with the agent.
    assertThat(coprocessor.run("sh", "-c", "pgrep -f 'standin/[w]ait' | wc -l").strip())
        .isEqualTo("0");
  }

  @Test
  void aPacksLogIsPagedBySpotterVariables() throws Exception {
    Spotter.LogPage latest =
        client.get(Protocol.LOGS + "standin.log?limit=5", Spotter.LogPage.newInstance());
    assertThat(latest.getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("entry 16", "entry 17", "entry 18", "entry 19", "entry 20");
    assertThat(latest.getEntries().get(0).getSource()).isEqualTo("latest/debug");
    assertThat(latest.getEntries().get(4).getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_ERROR);
    assertThat(latest.getBefore()).isEqualTo("16");
    Spotter.LogPage before =
        client.get(
            Protocol.LOGS + "standin.log?from=before&cursor=16&limit=5&level=warning",
            Spotter.LogPage.newInstance());
    assertThat(before.getEntries())
        .extracting(Spotter.LogEntry::getCursor)
        .containsExactly("11", "12", "13", "14", "15");
    assertThat(before.getEntries().get(0).getSource()).isEqualTo("before/warning");
    Spotter.LogPage after =
        client.get(
            Protocol.LOGS + "standin.log?from=after&cursor=20", Spotter.LogPage.newInstance());
    assertThat(after.getEntries()).isEmpty();
    assertThat(after.getAfter()).isEqualTo("20");
  }
}
