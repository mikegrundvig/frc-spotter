package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static com.michaelgrundvig.frc.spotter.manager.Boards.number;
import static com.michaelgrundvig.frc.spotter.manager.Boards.status;
import static com.michaelgrundvig.frc.spotter.manager.Boards.text;
import static com.michaelgrundvig.frc.spotter.manager.Boards.unavailable;
import static com.michaelgrundvig.frc.spotter.manager.Boards.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manager against a real agent, in the test's own process on a local port, with a pack the test
 * writes: it connects, follows the stream, judges each value by its limits (or robot code's), keeps
 * the alerts, and hands each message to the recorder.
 */
class ManagerTest {
  static final String PACK =
      """
      pack: vision
      version: 1.2.0
      collectors:
        - id: health
          run: [./health]
          every: 1h
          fields:
            fps: {label: Frames per second, type: number, unit: fps, warn: {below: 30}, fail: {below: 5, missing: true}}
            mode: {label: Mode, type: text, warn: {equals: calibrating}, fail: {missing: true}}
            detector: {label: Detector, type: status}
            armed: {label: Armed, type: boolean, warn: {notEquals: true}}
      """;

  /** What the collector's script says the first time: everything in order. */
  static final String HEALTHY =
      "echo '{\"fps\": 60, \"mode\": \"tracking\", \"armed\": true,"
          + " \"detector\": {\"level\": \"ok\", \"message\": \"two tags\"}}'";

  @TempDir Path dir;
  LocalAgent agent;
  @Nullable Manager manager;

  @BeforeEach
  void aBoard() throws Exception {
    agent = new LocalAgent(dir, "vision-front");
    agent.pack("vision", PACK).script("vision", "health", HEALTHY).start();
  }

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    agent.close();
  }

  private Manager manage(Settings settings, Recorder recorder) {
    Manager started = new Manager(Boards.robot(), List.of(agent.address()), settings, recorder);
    manager = started;
    started.start();
    return started;
  }

  /** A manager of the agent, once the collector's first values have reached it. */
  private Board connected(Manager manager) throws InterruptedException {
    Board board = manager.boards().get(0);
    await(
        manager,
        "the first values",
        () ->
            board.value("vision.health.fps") != null
                && value(board, "vision.health.fps").available());
    return board;
  }

  @Test
  void itConnectsAndFollowsTheStream() throws Exception {
    Manager manager = manage(Settings.DEFAULTS, Recorder.NONE);
    Board board = manager.boards().get(0);
    assertThat(board.connection()).isEqualTo(Connection.CONNECTING);
    assertThat(board.name()).isEqualTo(agent.address());
    connected(manager);

    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);
    assertThat(board.name()).isEqualTo("vision-front");
    assertThat(board.protocol()).isEqualTo(Protocol.VERSION);
    assertThat(board.why()).isEmpty();
    assertThat(board.description().getAgentVersion()).isEqualTo(LocalAgent.VERSION);
    assertThat(board.description().getPacks().get(0).getVersion()).isEqualTo("1.2.0");
    assertThat(board.values())
        .extracting(Value::id)
        .containsExactly(
            "vision.health.fps",
            "vision.health.mode",
            "vision.health.detector",
            "vision.health.armed");

    Value fps = value(board, "vision.health.fps");
    assertThat(fps.label()).isEqualTo("Frames per second");
    assertThat(fps.unit()).isEqualTo("fps");
    assertThat(fps.type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_NUMBER);
    assertThat(fps.number()).isEqualTo(60);
    assertThat(fps.level()).isEqualTo(Level.OK);
    assertThat(fps.reason()).isEmpty();
    assertThat(value(board, "vision.health.mode").text()).isEqualTo("tracking");
    assertThat(value(board, "vision.health.armed").flag()).isTrue();
    Value detector = value(board, "vision.health.detector");
    assertThat(detector.status()).isEqualTo(Level.OK);
    assertThat(detector.text()).isEqualTo("two tags");
    assertThat(manager.alerts()).isEmpty();
    assertThat(board.value("vision.health.nothing")).isNull();
  }

  @Test
  void eachValueIsJudgedByItsLimitsAndTheAlertsFollow() throws Exception {
    Manager manager = manage(Settings.DEFAULTS, Recorder.NONE);
    Board board = connected(manager);

    agent.set("vision.health.fps", number(12));
    await(
        manager,
        "fps at warning",
        () -> value(board, "vision.health.fps").level() == Level.WARNING);
    assertThat(value(board, "vision.health.fps").reason()).isEqualTo("below 30 fps");
    assertThat(manager.alerts())
        .containsExactly(
            new Alert(
                Level.WARNING, "vision-front", "vision-front: Frames per second below 30 fps"));

    agent.set("vision.health.fps", number(3));
    agent.set("vision.health.mode", text("calibrating"));
    agent.set("vision.health.detector", status(Spotter.Level.LEVEL_FAILING, "no tags in 5 s"));
    agent.set("vision.health.armed", Spotter.FieldValue.newInstance().setFlag(false));
    await(manager, "four alerts", () -> manager.alerts().size() == 4);
    assertThat(manager.alerts())
        .containsExactly(
            new Alert(Level.FAILING, "vision-front", "vision-front: Frames per second below 5 fps"),
            new Alert(Level.WARNING, "vision-front", "vision-front: Mode is calibrating"),
            new Alert(Level.FAILING, "vision-front", "vision-front: Detector: no tags in 5 s"),
            new Alert(Level.WARNING, "vision-front", "vision-front: Armed isn't true"));
    assertThat(board.alerts()).isEqualTo(manager.alerts());

    agent.set("vision.health.fps", unavailable("timed out after 5 s"));
    agent.set("vision.health.mode", text(""));
    await(
        manager,
        "fps and mode missing",
        () -> value(board, "vision.health.mode").reason().equals(Field.EMPTY));
    Value fps = value(board, "vision.health.fps");
    assertThat(fps.available()).isFalse();
    assertThat(fps.unavailable()).isEqualTo("timed out after 5 s");
    assertThat(fps.level()).isEqualTo(Level.FAILING);
    assertThat(manager.alerts())
        .contains(
            new Alert(
                Level.FAILING,
                "vision-front",
                "vision-front: Frames per second unavailable: timed out after 5 s"),
            new Alert(Level.FAILING, "vision-front", "vision-front: Mode is empty"));

    agent.set("vision.health.fps", number(60));
    agent.set("vision.health.mode", text("tracking"));
    agent.set("vision.health.detector", status(Spotter.Level.LEVEL_OK, "two tags"));
    agent.set("vision.health.armed", Spotter.FieldValue.newInstance().setFlag(true));
    await(manager, "no alerts", () -> manager.alerts().isEmpty());
  }

  @Test
  void aBoardsProblemsAreAWarningWithHowManyAndTheFirstUntilTheyreGone() throws Exception {
    // Two packs it ignores: a misspelled key, and a folder named for another pack.
    agent.stop();
    agent.pack("broken", "pack: broken\nversoin: 1.0.0\n");
    agent.pack("misnamed", "pack: other\n");
    agent.start();
    Manager manager = manage(Settings.DEFAULTS, Recorder.NONE);
    Board board = connected(manager);
    assertThat(board.problems())
        .hasSize(2)
        .allMatch(problem -> problem.contains("(the pack is ignored)"));
    assertThat(board.problems().get(0))
        .startsWith("/etc/frc-spotter/packs/broken/pack.yaml:2: unknown key \"versoin\"");
    assertThat(manager.alerts())
        .containsExactly(
            new Alert(
                Level.WARNING,
                "vision-front",
                "vision-front reports 2 problems, the first: " + board.problems().get(0)));

    // Fixed, and the agent restarted with them: no problems, no alert.
    agent.stop();
    agent.pack("broken", "pack: broken\n");
    agent.pack("misnamed", "pack: misnamed\n");
    agent.start();
    await(manager, "no problems", () -> board.problems().isEmpty());
    assertThat(manager.alerts()).isEmpty();
  }

  @Test
  void aProblemsAlertQuotesALongFirstProblemCut() {
    String first = "x".repeat(Link.QUOTED_PROBLEM + 50);
    assertThat(Link.problemsAlert("vision-front", List.of(first)))
        .isEqualTo("vision-front reports a problem: " + "x".repeat(Link.QUOTED_PROBLEM) + "...");
    assertThat(Link.problemsAlert("vision-front", List.of())).isEmpty();
  }

  @Test
  void theAlertsAreTheSameListUntilOneChanges() throws Exception {
    Manager manager = manage(Settings.DEFAULTS, Recorder.NONE);
    Board board = connected(manager);
    agent.set("vision.health.fps", number(12));
    await(manager, "an alert", () -> manager.alerts().size() == 1);
    List<Alert> alerts = manager.alerts();

    // The number changes, its level and reason don't: the same alerts.
    agent.set("vision.health.fps", number(13));
    await(manager, "fps 13", () -> value(board, "vision.health.fps").number() == 13);
    assertThat(manager.alerts()).isSameAs(alerts);
  }

  @Test
  void robotCodesLimitsReplaceAPacksByFieldId() throws Exception {
    Limits stricter =
        new Limits(
            Spotter.Limit.newInstance().setBelow(50),
            Spotter.Limit.newInstance().setBelow(20).setMissing(true));
    Manager manager =
        manage(Settings.DEFAULTS.withLimits("vision.health.fps", stricter), Recorder.NONE);
    Board board = connected(manager);
    Value fps = value(board, "vision.health.fps");
    assertThat(fps.overridden()).isTrue();
    assertThat(fps.limits()).isEqualTo(stricter);
    assertThat(value(board, "vision.health.mode").overridden()).isFalse();

    agent.set("vision.health.fps", number(40));
    await(manager, "fps 40 at warning", () -> value(board, "vision.health.fps").number() == 40);
    assertThat(value(board, "vision.health.fps").level()).isEqualTo(Level.WARNING);
    assertThat(value(board, "vision.health.fps").reason()).isEqualTo("below 50 fps");

    agent.set("vision.health.fps", number(10));
    await(manager, "fps 10", () -> value(board, "vision.health.fps").number() == 10);
    assertThat(value(board, "vision.health.fps").level()).isEqualTo(Level.FAILING);
    assertThat(value(board, "vision.health.fps").reason()).isEqualTo("below 20 fps");
  }

  @Test
  void theChangeNoticeRisesWithEachChangeAndSaysWhichValues() throws Exception {
    Manager manager = manage(Settings.DEFAULTS, Recorder.NONE);
    Board board = connected(manager);
    long seen = board.changes();
    assertThat(seen).isPositive();

    agent.set("vision.health.mode", text("searching"));
    await(manager, "a change", () -> board.changes() != seen);
    assertThat(value(board, "vision.health.mode").changed()).isGreaterThan(seen);
    assertThat(value(board, "vision.health.fps").changed()).isLessThanOrEqualTo(seen);
  }

  @Test
  void eachValueIsTimedOnTheRobotsClock() throws Exception {
    long before = System.nanoTime();
    Manager manager = manage(Settings.DEFAULTS, Recorder.NONE);
    Board board = connected(manager);
    agent.set("vision.health.fps", number(42));
    await(manager, "fps 42", () -> value(board, "vision.health.fps").number() == 42);
    long after = System.nanoTime();
    // The agent's clock is this computer's too: what it sent is mapped onto the robot's, between
    // the test's start and now, and never after it was heard.
    Value fps = value(board, "vision.health.fps");
    assertThat(fps.nanos()).isBetween(before, after);
    assertThat(fps.nanos()).isLessThanOrEqualTo(board.heardNanos());
  }

  @Test
  void theRecorderGetsEachDescriptionAndValuesAsReceived() throws Exception {
    List<String> recorded = new CopyOnWriteArrayList<>();
    List<Spotter.Values> values = new CopyOnWriteArrayList<>();
    Recorder recorder =
        new Recorder() {
          @Override
          public void described(Board board, Spotter.Description description) {
            recorded.add(board.name() + " described " + description.getIdentity().getHostname());
          }

          @Override
          public void values(Board board, Spotter.Values received) {
            recorded.add(board.name() + " values");
            // Reused after the call: a copy.
            values.add(received.clone());
          }
        };
    Manager manager = manage(Settings.DEFAULTS, recorder);
    connected(manager);
    agent.set("vision.health.fps", number(31));
    await(
        manager,
        "fps 31 recorded",
        () ->
            values.stream()
                .anyMatch(
                    v -> v.getValues().length() == 1 && v.getValues().get(0).getNumber() == 31));
    assertThat(recorded.get(0)).isEqualTo("vision-front described vision-front");
    assertThat(recorded).contains("vision-front values");
    assertThat(values.get(0).getComplete()).isTrue();
  }

  @Test
  void aRecorderThatThrowsNeverTakesTheBoardAway() throws Exception {
    Recorder broken =
        new Recorder() {
          @Override
          public void values(Board board, Spotter.Values values) {
            throw new IllegalStateException("the log is full");
          }
        };
    Manager manager = manage(Settings.DEFAULTS, broken);
    Board board = connected(manager);
    agent.set("vision.health.fps", number(44));
    await(manager, "fps 44", () -> value(board, "vision.health.fps").number() == 44);
    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);
  }
}
