package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actions and logs, against a real agent: a run's log as it goes, its response judged when it
 * finishes, a cancel, a file; refusals by the manager (the match) and by the board; a result that
 * came while the board was away; and a page of a log.
 */
class ActionsTest {
  static final String PACK =
      """
      pack: team
      logs:
        - id: log
          label: Its log
          run: [./pager]
      actions:
        - id: hello
          label: Say hello
          run: [./hello]
          input: text
          response:
            exit: {type: number, fail: {notEquals: 0}}
            greeting: {label: Greeting, type: text}
            speed: {label: Speed, type: number, unit: m/s, warn: {above: 3}}
        - id: wait
          run: [./wait]
          timeout: 10m
        - id: steady
          run: [sh, -c, "echo steady >&2"]
          whileEnabled: true
        - id: export
          run: [printf, "PK zip bytes"]
          response:
            output: {type: file, name: settings.zip}
      """;

  static final String HELLO =
      """
      read -r name
      echo "greeting $name" >&2
      echo "almost done" >&2
      printf '{"greeting": "hello, %s", "speed": 4.5}\\n' "$name"
      """;

  static final String WAIT =
      "trap 'echo stopping >&2; exit 143' TERM; echo waiting >&2; while true; do sleep 0.1; done";

  static final String PAGER =
      """
      from=${SPOTTER_FROM}; cursor=${SPOTTER_CURSOR:-0}; limit=${SPOTTER_LIMIT}; total=20
      case $from in
        latest) first=$((total - limit + 1)); last=$total ;;
        before) first=$((cursor - limit)); last=$((cursor - 1)) ;;
        after) first=$((cursor + 1)); last=$((cursor + limit)) ;;
      esac
      [ "$first" -lt 1 ] && first=1
      [ "$last" -gt "$total" ] && last=$total
      n=$first
      while [ "$n" -le "$last" ]; do
        level=info; [ $((n % 5)) -eq 0 ] && level=error
        printf '{"time": "2026-10-02T12:00:%02dZ", "level": "%s", "source": "%s/%s", "message": "entry %d", "cursor": "%d"}\\n' \\
          "$n" "$level" "$from" "$SPOTTER_LEVEL" "$n" "$n"
        n=$((n + 1))
      done
      """;

  @TempDir Path dir;
  LocalAgent agent;
  final AtomicBoolean enabled = new AtomicBoolean();
  final AtomicBoolean field = new AtomicBoolean();
  @Nullable Manager manager;

  @BeforeEach
  void aBoard() throws Exception {
    agent = new LocalAgent(dir, "vision-front");
    agent
        .pack("team", PACK)
        .script("team", "hello", HELLO)
        .script("team", "wait", WAIT)
        .script("team", "pager", PAGER)
        .start();
  }

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    agent.close();
  }

  private Board connected(Settings settings) throws InterruptedException {
    Manager started =
        new Manager(
            new Robot(enabled::get, field::get, System::nanoTime),
            List.of(agent.address()),
            settings.withKey(dir.resolve("no.key")),
            Recorder.NONE);
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(started, "described", () -> board.description().getActions().length() > 0);
    return board;
  }

  private Board connected() throws InterruptedException {
    return connected(Settings.DEFAULTS);
  }

  static Run done(Run run) throws Exception {
    return run.whenDone().get(10, TimeUnit.SECONDS);
  }

  static Value field(Run run, String name) {
    return Objects.requireNonNull(run.response(name), () -> run + " has no " + name);
  }

  @Test
  void aRunLogsAsItGoesAndItsResponseIsJudged() throws Exception {
    Board board = connected();
    Run run = board.run("team.hello", "Ada");
    assertThat(run.action()).isEqualTo("team.hello");
    done(run);
    assertThat(run.state()).isEqualTo(Run.State.FINISHED);
    assertThat(run.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    assertThat(run.id()).hasSize(16);
    assertThat(run.log())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("greeting Ada", "almost done");
    assertThat(run.response())
        .extracting(Value::id)
        .containsExactly("outcome", "outcomeMessage", "exit", "output", "greeting", "speed");
    assertThat(field(run, "exit").number()).isZero();
    assertThat(field(run, "exit").level()).isEqualTo(Level.OK);
    assertThat(field(run, "greeting").text()).isEqualTo("hello, Ada");
    Value speed = field(run, "speed");
    assertThat(speed.number()).isEqualTo(4.5);
    assertThat(speed.level()).isEqualTo(Level.WARNING);
    assertThat(speed.reason()).isEqualTo("above 3 m/s");
    assertThat(run.response("nothing")).isNull();
    assertThat(run.changes()).isPositive();
    assertThat(run.toString()).startsWith("team.hello (" + run.id() + "): FINISHED");
  }

  @Test
  void robotCodesLimitsJudgeAResponseByActionAndField() throws Exception {
    Limits slower =
        new Limits(Spotter.Limit.newInstance(), Spotter.Limit.newInstance().setAbove(4));
    Board board = connected(Settings.DEFAULTS.withLimits("team.hello.speed", slower));
    Run run = done(board.run("team.hello", "Ada"));
    assertThat(field(run, "speed").level()).isEqualTo(Level.FAILING);
    assertThat(field(run, "speed").overridden()).isTrue();
  }

  @Test
  void aRunIsCancelledPolitely() throws Exception {
    Board board = connected();
    Run run = board.run("team.wait");
    for (int i = 0; i < 100 && run.log().isEmpty(); i++) {
      Thread.sleep(50);
    }
    assertThat(run.state()).isEqualTo(Run.State.RUNNING);
    run.cancel().get(10, TimeUnit.SECONDS);
    done(run);
    assertThat(run.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
    assertThat(run.log())
        .extracting(Spotter.LogEntry::getMessage)
        .containsSubsequence("waiting", "stopping");
    // Cancelling what's done does nothing.
    run.cancel().get(1, TimeUnit.SECONDS);
  }

  @Test
  void aCancelAskedForBeforeTheRunStartedIsSentOnceItHas() throws Exception {
    Board board = connected();
    Run run = board.run("team.wait");
    run.cancel().get(10, TimeUnit.SECONDS);
    done(run);
    assertThat(run.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
  }

  @Test
  void aFileFieldIsFetchedFromTheBoard() throws Exception {
    Board board = connected();
    Run run = done(board.run("team.export"));
    Value output = field(run, "output");
    assertThat(output.type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_FILE);
    assertThat(output.text()).isEqualTo("/v2/runs/" + run.id() + "/files/output");
    assertThat(new String(run.file("output").get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8))
        .isEqualTo("PK zip bytes");
    assertThatThrownBy(() -> run.file("nothing").get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("it answered 404");
  }

  @Test
  void whileTheRobotIsEnabledOrOnTheFieldOnlyActionsThatSaySoRun() throws Exception {
    Board board = connected();
    enabled.set(true);
    Run refused = board.run("team.hello", "Ada");
    assertThat(refused.state()).isEqualTo(Run.State.REFUSED);
    assertThat(refused.why())
        .isEqualTo("the robot is enabled, and team.hello doesn't say whileEnabled");
    assertThat(refused.whenDone()).isDone();
    assertThat(done(board.run("team.steady")).outcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);

    enabled.set(false);
    field.set(true);
    assertThat(board.run("team.hello", "Ada").why())
        .isEqualTo("the field is attached, and team.hello doesn't say whileEnabled");
    field.set(false);
    assertThat(done(board.run("team.hello", "Ada")).outcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
  }

  @Test
  void robotCodeCanLetActionsRunWhileEnabled() throws Exception {
    Board board = connected(Settings.DEFAULTS.withRefuseWhileEnabled(false));
    enabled.set(true);
    field.set(true);
    assertThat(done(board.run("team.hello", "Ada")).outcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
  }

  @Test
  void anActionTheBoardDoesntHaveOrABoardAwayIsRefusedAtOnce() throws Exception {
    Board board = connected();
    Run unknown = board.run("team.nothing");
    assertThat(unknown.state()).isEqualTo(Run.State.REFUSED);
    assertThat(unknown.why()).isEqualTo("vision-front has no action team.nothing");
    assertThat(unknown.response()).isEmpty();
    assertThat(unknown.cancel()).isDone();
    assertThatThrownBy(() -> unknown.file("output").get())
        .hasMessageContaining("hasn't started, so it has no files");

    agent.stop();
    Objects.requireNonNull(manager).close();
    manager = null;
    Board away = new Manager(Boards.robot(), List.of(agent.address())).boards().get(0);
    Run never = away.run("team.hello");
    assertThat(never.state()).isEqualTo(Run.State.REFUSED);
    assertThat(never.why()).startsWith(agent.address() + " isn't connected");
  }

  @Test
  void aRefusalByTheBoardSaysWhy() throws Exception {
    Board board = connected();
    Run first = board.run("team.wait");
    for (int i = 0; i < 100 && first.state() != Run.State.RUNNING; i++) {
      Thread.sleep(50);
    }
    Run second = done(board.run("team.wait"));
    assertThat(second.state()).isEqualTo(Run.State.REFUSED);
    assertThat(second.why()).startsWith("it answered 409");
    first.cancel().get(10, TimeUnit.SECONDS);
  }

  @Test
  void aRunThatWasGoingWhenTheAgentRestartedComesBackLost() throws Exception {
    Board board = connected();
    Run run = board.run("team.wait");
    for (int i = 0; i < 100 && run.log().isEmpty(); i++) {
      Thread.sleep(50);
    }
    agent.stop();
    agent.start();
    done(run);
    assertThat(run.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_LOST);
    assertThat(run.why()).isEqualTo("the agent restarted while it ran");
    // Its log, as the board kept it, fetched again.
    for (int i = 0; i < 100 && run.log().isEmpty(); i++) {
      Thread.sleep(50);
    }
    assertThat(run.log()).extracting(Spotter.LogEntry::getMessage).contains("waiting");
  }

  @Test
  void aPageOfALogIsFetched() throws Exception {
    Board board = connected();
    Spotter.LogPage latest = board.log("team.log", LogQuery.latest(5)).get(10, TimeUnit.SECONDS);
    assertThat(latest.getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("entry 16", "entry 17", "entry 18", "entry 19", "entry 20");
    assertThat(latest.getBefore()).isEqualTo("16");
    Spotter.LogPage before =
        board
            .log("team.log", LogQuery.before(latest.getBefore(), 5).atLeast("warning"))
            .get(10, TimeUnit.SECONDS);
    assertThat(before.getEntries())
        .extracting(Spotter.LogEntry::getSource)
        .allMatch(source -> source.equals("before/warning"));
    assertThatThrownBy(() -> board.log("team.none", LogQuery.latest(5)).get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("it answered 404");
  }

  @Test
  void theRecorderGetsEachRunsEvents() throws Exception {
    List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    Manager started =
        new Manager(
            Boards.robot(),
            List.of(agent.address()),
            Settings.DEFAULTS.withKey(dir.resolve("no.key")),
            new Recorder() {
              @Override
              public void run(Board board, Spotter.RunEvent event) {
                events.add(
                    event.getAction()
                        + (event.hasStarted()
                            ? " started"
                            : event.hasLog() ? " log" : " finished"));
              }
            });
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(started, "described", () -> board.description().getActions().length() > 0);
    done(board.run("team.hello", "Ada"));
    for (int i = 0; i < 100 && !events.contains("team.hello finished"); i++) {
      Thread.sleep(20);
    }
    assertThat(events)
        .containsSubsequence("team.hello started", "team.hello log", "team.hello finished");
  }
}
