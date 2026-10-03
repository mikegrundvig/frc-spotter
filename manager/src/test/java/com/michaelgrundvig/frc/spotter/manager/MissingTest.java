package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static com.michaelgrundvig.frc.spotter.manager.Boards.number;
import static com.michaelgrundvig.frc.spotter.manager.Boards.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A board that goes quiet counts as missing once it's been silent for the missing threshold, on the
 * robot's clock (here the test's, which moves only when the test moves it); the manager keeps
 * trying, and a board that comes back is connected again, its description and values with it.
 */
class MissingTest {
  static final long SECOND = 1_000_000_000L;

  @TempDir Path dir;
  final AtomicLong clock = new AtomicLong(1_000 * SECOND);
  @Nullable LocalAgent agent;
  @Nullable Manager manager;

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    if (agent != null) {
      agent.close();
    }
  }

  private Manager manage(String address) {
    Manager started =
        new Manager(
            Boards.robot(clock),
            List.of(address),
            Settings.DEFAULTS.withBackoff(Duration.ofMillis(500)),
            Recorder.NONE);
    manager = started;
    started.start();
    return started;
  }

  @Test
  void aBoardNeverReachedIsConnectingThenMissing() throws Exception {
    int port;
    try (ServerSocket free = new ServerSocket(0)) {
      port = free.getLocalPort();
    }
    Manager manager = manage("127.0.0.1:" + port);
    Board board = manager.boards().get(0);
    manager.update();
    assertThat(board.connection()).isEqualTo(Connection.CONNECTING);
    assertThat(manager.alerts()).isEmpty();
    await(manager, "a refused attempt", () -> !board.why().isEmpty());
    assertThat(board.why()).contains("refused");

    clock.addAndGet(SECOND + 1);
    manager.update();
    assertThat(board.connection()).isEqualTo(Connection.MISSING);
    assertThat(manager.alerts())
        .containsExactly(
            new Alert(
                Level.FAILING, board.address(), board.address() + " is missing: " + board.why()));
  }

  @Test
  void aBoardThatGoesQuietIsMissingAndComesBackConnected() throws Exception {
    LocalAgent running = new LocalAgent(dir, "vision-front");
    agent = running;
    running
        .pack("vision", ManagerTest.PACK)
        .script("vision", "health", ManagerTest.HEALTHY)
        .start();
    Manager manager = manage(running.address());
    Board board = manager.boards().get(0);
    await(
        manager,
        "connected",
        () ->
            board.connection() == Connection.CONNECTED
                && !board.values().isEmpty()
                && value(board, "vision.health.fps").available());
    Spotter.Description described = board.description();

    // Silent, but not yet for the threshold: still connected.
    running.stop();
    await(manager, "the stream's end", () -> !board.why().isEmpty());
    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);
    clock.addAndGet(SECOND);
    manager.update();
    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);

    clock.addAndGet(1);
    manager.update();
    assertThat(board.connection()).isEqualTo(Connection.MISSING);
    assertThat(manager.alerts()).hasSize(1);
    assertThat(manager.alerts().get(0).text()).startsWith("vision-front is missing: ");
    // What it last said stays, for what it's worth; its connection says it's missing.
    assertThat(value(board, "vision.health.fps").number()).isEqualTo(60);

    // Back, its collector saying something new.
    running.script("vision", "health", ManagerTest.HEALTHY.replace("60", "55")).start();
    await(
        manager,
        "connected again",
        () ->
            board.connection() == Connection.CONNECTED
                && value(board, "vision.health.fps").number() == 55);
    assertThat(manager.alerts()).isEmpty();
    assertThat(board.why()).isEmpty();
    // The same description again: kept as it was.
    assertThat(board.description()).isSameAs(described);

    running.set("vision.health.fps", number(12));
    await(manager, "fps 12", () -> value(board, "vision.health.fps").number() == 12);
    assertThat(manager.alerts()).hasSize(1);
  }

  @Test
  void anOverrideThatMatchesNoValueWarnsOnceEveryBoardHasDescribedItself() throws Exception {
    LocalAgent running = new LocalAgent(dir, "vision-front");
    agent = running;
    running
        .pack("vision", ManagerTest.PACK)
        .script("vision", "health", ManagerTest.HEALTHY)
        .start();
    int port;
    try (ServerSocket free = new ServerSocket(0)) {
      port = free.getLocalPort();
    }
    Settings settings =
        Settings.DEFAULTS
            .withLimits("vision.health.fps", Limits.NONE)
            .withLimits("vision.health.fsp", Limits.NONE);
    Manager manager =
        new Manager(
            Boards.robot(clock),
            List.of(running.address(), "127.0.0.1:" + port),
            settings,
            Recorder.NONE);
    this.manager = manager;
    manager.start();
    Board vision = manager.boards().get(0);
    Board away = manager.boards().get(1);
    // One board has described itself; the other hasn't been reached yet, and may still: nothing.
    // Its values collected: until then, fps is unavailable, which its missing rule makes failing.
    await(
        manager,
        "vision's values",
        () ->
            vision.value("vision.health.fps") != null
                && value(vision, "vision.health.fps").available());
    await(manager, "the other refused", () -> !away.why().isEmpty());
    assertThat(manager.alerts()).isEmpty();

    // The other is missing: it no longer holds the check back.
    clock.addAndGet(SECOND + 1);
    manager.update();
    assertThat(manager.alerts())
        .contains(
            new Alert(
                Level.WARNING,
                "",
                "Spotter's limits for vision.health.fsp match no value or response field on any board"))
        .noneMatch(alert -> alert.text().contains("vision.health.fps "));
  }

  @Test
  void aStreamSilentForTheThresholdIsDroppedAndMadeAgain() throws Exception {
    // A board whose agent stops answering without closing anything: a socket that accepts and says
    // nothing. The connection's own timeout drops it, and the board is missing on the robot's
    // clock.
    try (ServerSocket silent = new ServerSocket(0)) {
      Manager manager = manage("127.0.0.1:" + silent.getLocalPort());
      Board board = manager.boards().get(0);
      await(manager, "a timed-out attempt", () -> board.why().contains("timed out"));
      clock.addAndGet(2 * SECOND);
      manager.update();
      assertThat(board.connection()).isEqualTo(Connection.MISSING);
    }
  }
}
