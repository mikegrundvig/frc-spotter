package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actions' runs, for real: their responses, logs, limits, and what's kept of them. */
class RunsTest {
  @TempDir Path dir;
  Fixture fixture;
  Commands commands;
  Runs runs;
  final List<Spotter.RunEvent> events = new CopyOnWriteArrayList<>();

  static final String TEAM =
      """
      pack: team
      actions:
        - id: ok
          run: [./ok]
          input: text
          response:
            answer: {type: number}
            report: {type: json}
        - id: fail
          run: [sh, -c, "echo nope >&2; exit 3"]
          response:
            exit: {type: number, fail: {notEquals: 0}}
        - id: slow
          run: [sleep, "30"]
          timeout: 1s
        - id: stubborn
          run: [sh, -c, "trap 'echo stopping >&2; exit 143' TERM; echo waiting >&2; while true; do sleep 0.1; done"]
        - id: big
          run: [head, -c, "2000000", /dev/zero]
        - id: big2
          run: [head, -c, "2000000", /dev/zero]
        - id: export
          run: [printf, "PK zip bytes"]
          response:
            output: {type: file, name: settings.zip}
      """;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
    String folder = fixture.pack("team", TEAM);
    fixture.script(
        folder + "/ok",
        "read given\n"
            + "echo 'step one' >&2\n"
            + "echo '{\"level\": \"warning\", \"message\": \"careful\"}' >&2\n"
            + "echo \"{\\\"answer\\\": 42, \\\"report\\\": {\\\"given\\\": \\\"$given\\\"}}\"");
    commands = new Commands(fixture.host);
    runs = runs(Runs.MAX_KEPT);
  }

  private Runs runs(long maxKept) {
    Events hub = new Events();
    hub.subscribe(
        new Events.Subscriber() {
          @Override
          public void valuesChanged() {}

          @Override
          public void run(Spotter.RunEvent event) {
            events.add(event);
          }
        });
    return new Runs(
        fixture.host,
        commands,
        hub,
        Runs.declared(Packs.load(fixture.host, true).packs()),
        maxKept);
  }

  @AfterEach
  void close() {
    runs.close();
    commands.close();
  }

  private Path input(String text) throws Exception {
    Path input = runs.input();
    Files.writeString(input, text);
    return input;
  }

  private Spotter.RunState finished(String run) throws Exception {
    for (int i = 0; i < 200; i++) {
      Spotter.RunState state = runs.state(run).orElseThrow();
      if (!state.getRunning()) {
        return state;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("run " + run + " didn't finish");
  }

  private static Map<String, Spotter.FieldValue> response(Spotter.RunState state) {
    Map<String, Spotter.FieldValue> byName = new java.util.LinkedHashMap<>();
    for (Spotter.FieldValue value : state.getResult().getResponse()) {
      byName.put(value.getName(), value);
    }
    return byName;
  }

  /** A finished run's response field, by its name. */
  private static Spotter.FieldValue part(Spotter.RunState state, String name) {
    return java.util.Objects.requireNonNull(response(state).get(name), name);
  }

  @Test
  void aRunTakesItsInputAndFillsItsResponseAndLog() throws Exception {
    String run = runs.start("team.ok", input("front camera\n")).getRun();
    assertThat(run).matches(Runs.ID.pattern());
    Spotter.RunState state = finished(run);
    assertThat(state.getAction()).isEqualTo("team.ok");
    assertThat(state.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    assertThat(response(state).keySet())
        .containsExactly("outcome", "outcomeMessage", "exit", "output", "answer", "report");
    assertThat(part(state, "outcome").getText()).isEqualTo("completed");
    assertThat(part(state, "exit").getNumber()).isZero();
    assertThat(part(state, "answer").getNumber()).isEqualTo(42);
    assertThat(part(state, "report").getJson()).isEqualTo("{\"given\":\"front camera\"}");
    Spotter.LogPage log = runs.log(run, Logs.Paging.of(Map.of()));
    assertThat(log.getEntries())
        .extracting(e -> e.getCursor() + " " + e.getLevel() + " " + e.getMessage())
        .containsExactly("1 LOG_LEVEL_INFO step one", "2 LOG_LEVEL_WARNING careful");
    assertThat(log.getEntries().get(0).getTimeMicros()).isPositive();
    // As it happened: started, each line, finished.
    assertThat(events)
        .extracting(
            e -> e.hasStarted() ? "started" : e.hasLog() ? e.getLog().getMessage() : "finished")
        .containsExactly("started", "step one", "careful", "finished");
    assertThat(events).allMatch(e -> e.getRun().equals(run) && e.getAction().equals("team.ok"));
    // Its input is gone with it.
    try (var files = Files.list(fixture.path(Runs.FOLDER + "/" + run))) {
      assertThat(files.map(p -> p.getFileName().toString()).sorted())
          .containsExactly("log", "output", "state");
    }
  }

  @Test
  void aFailingExitCompletesForItsPacksLimitToJudge() throws Exception {
    Spotter.RunState state = finished(runs.start("team.fail", null).getRun());
    assertThat(state.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    assertThat(part(state, "exit").getNumber()).isEqualTo(3);
  }

  @Test
  void aRunPastItsTimeoutIsStoppedAndSaysSo() throws Exception {
    Spotter.RunState state = finished(runs.start("team.slow", null).getRun());
    assertThat(state.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_TIMED_OUT);
    assertThat(state.getResult().getOutcomeMessage()).isEqualTo("timed out after 1s");
    assertThat(part(state, "outcome").getText()).isEqualTo("timedOut");
    assertThat(part(state, "exit").getUnavailable()).isEqualTo("timed out after 1s");
  }

  @Test
  void aCancelledRunIsAskedToStopAndDoes() throws Exception {
    String run = runs.start("team.stubborn", null).getRun();
    for (int i = 0;
        i < 100 && runs.log(run, Logs.Paging.of(Map.of())).getEntries().length() == 0;
        i++) {
      Thread.sleep(50);
    }
    runs.cancel(run);
    Spotter.RunState state = finished(run);
    assertThat(state.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
    assertThat(state.getResult().getOutcomeMessage()).isEqualTo("cancelled");
    // It was asked politely, and said goodbye (its shell may say its sleep was Terminated too).
    assertThat(runs.log(run, Logs.Paging.of(Map.of())).getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsSubsequence("waiting", "stopping");
    assertThat(catchThrowableOfType(Runs.Refused.class, () -> runs.cancel(run)).status)
        .isEqualTo(409);
    assertThat(
            catchThrowableOfType(Runs.Refused.class, () -> runs.cancel("0123456789abcdef")).status)
        .isEqualTo(404);
  }

  @Test
  void oneRunOfAnActionAtATimeAndTwoActionsAtOnce() throws Exception {
    String stubborn = runs.start("team.stubborn", null).getRun();
    Runs.Refused again =
        catchThrowableOfType(Runs.Refused.class, () -> runs.start("team.stubborn", null));
    assertThat(again.status).isEqualTo(409);
    assertThat(again).hasMessage("team.stubborn is running already, as run " + stubborn);
    String slow = runs.start("team.slow", null).getRun();
    assertThat(catchThrowableOfType(Runs.Refused.class, () -> runs.start("team.fail", null)).status)
        .isEqualTo(503);
    runs.cancel(stubborn);
    finished(stubborn);
    finished(runs.start("team.fail", null).getRun());
    runs.cancel(slow);
    assertThat(catchThrowableOfType(Runs.Refused.class, () -> runs.start("team.none", null)).status)
        .isEqualTo(404);
  }

  @Test
  void aFileFieldIsFetchedByItsUrlAsItsName() throws Exception {
    String run = runs.start("team.export", null).getRun();
    Spotter.RunState state = finished(run);
    assertThat(part(state, "output").getFileUrl()).isEqualTo("/v2/runs/" + run + "/files/output");
    Runs.Download download = runs.file(run, "output");
    assertThat(download.name()).isEqualTo("settings.zip");
    assertThat(Files.readString(download.path())).isEqualTo("PK zip bytes");
    assertThat(catchThrowableOfType(Runs.Refused.class, () -> runs.file(run, "exit")).status)
        .isEqualTo(404);
  }

  @Test
  void outputPastTheMostKeptIsSaidAndWhatsKeptIsBounded() throws Exception {
    String first = runs.start("team.big", null).getRun();
    Spotter.RunState big = finished(first);
    assertThat(part(big, "output").getUnavailable())
        .isEqualTo("it printed more than the 1024 KiB kept");
    assertThat(part(big, "exit").getNumber()).isZero();
    assertThat(Files.size(fixture.path(Runs.FOLDER + "/" + first + "/output")))
        .isEqualTo(Runs.MAX_OUTPUT);
    // A run is kept until its action runs again.
    String second = runs.start("team.big", null).getRun();
    finished(second);
    assertThat(runs.state(first)).isEmpty();
    assertThat(Files.exists(fixture.path(Runs.FOLDER + "/" + first))).isFalse();
    runs.close();
    // And the oldest is dropped when the runs kept are full.
    runs = runs(3 * Runs.MAX_OUTPUT / 2);
    runs.restore();
    finished(runs.start("team.big2", null).getRun());
    assertThat(runs.state(second)).isEmpty();
    assertThat(runs.states()).extracting(Spotter.RunState::getAction).containsExactly("team.big2");
  }

  @Test
  void runsSurviveARestartAndOnesThatWereGoingAreLost() throws Exception {
    String done = runs.start("team.fail", null).getRun();
    finished(done);
    // A run that was going as the agent stopped, as it left its state.
    String going = "0000000000100abc";
    Path folder = fixture.path(Runs.FOLDER + "/" + going);
    Files.createDirectories(folder);
    Files.write(
        folder.resolve("state"),
        Spotter.RunState.newInstance()
            .setRun(going)
            .setAction("team.stubborn")
            .setRunning(true)
            .toByteArray());
    Files.writeString(folder.resolve("input"), "x");
    // Its log's last entry cut short as the agent stopped.
    Files.write(
        folder.resolve("log"),
        new byte[] {
          (byte)
              (Spotter.LogEntry.newInstance().setMessage("ok").setCursor("1").getSerializedSize()),
          0x22,
          0x02,
          'o',
          'k',
          0x2a,
          0x01,
          '1',
          0x10
        });
    Files.write(folder.resolve("rubbish"), "x".getBytes(StandardCharsets.UTF_8));
    Files.createDirectories(fixture.path(Runs.FOLDER + "/not-a-run"));
    runs.close();
    runs = runs(Runs.MAX_KEPT);
    runs.restore();
    assertThat(runs.states()).extracting(Spotter.RunState::getRun).containsExactly(going, done);
    Spotter.RunState lost = runs.state(going).orElseThrow();
    assertThat(lost.getRunning()).isFalse();
    assertThat(lost.getResult().getOutcome()).isEqualTo(Spotter.Outcome.OUTCOME_LOST);
    assertThat(lost.getResult().getOutcomeMessage()).isEqualTo(Runs.LOST);
    assertThat(part(lost, "outcome").getText()).isEqualTo("lost");
    assertThat(Files.exists(folder.resolve("input"))).isFalse();
    assertThat(runs.log(going, Logs.Paging.of(Map.of())).getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("ok");
    assertThat(runs.state(done).orElseThrow().getResult().getOutcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
  }

  @Test
  void theBuiltInActionsAreCores() {
    List<String> ids = new ArrayList<>();
    for (Runs.Declared each : Runs.declared(List.of())) {
      ids.add(each.id());
    }
    assertThat(ids).containsExactly("core.power-off", "core.reboot");
    assertThat(runs.action("core.reboot").orElseThrow().action().command())
        .isEqualTo(new Command.Run(List.of("systemctl", "reboot")));
  }
}
