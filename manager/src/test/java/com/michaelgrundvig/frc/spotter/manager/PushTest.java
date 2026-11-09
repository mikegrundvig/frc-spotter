package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.manager.stream.Link;
import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The team's packs, pushed to a real agent: automatically while the robot is disabled and off the
 * field, never on it; forced by robot code at any time; refused by a board that refuses pushes; and
 * a push that fails is said, and never tried again in a loop.
 */
class PushTest {
  static final String TEAM =
      """
      pack: team
      version: 1.0.0
      collectors:
        - id: health
          run: [./health]
          every: 1h
          fields:
            answer: {type: number}
      """;

  @TempDir Path dir;
  LocalAgent agent;
  Path packs;
  String hash = "";
  final AtomicBoolean enabled = new AtomicBoolean();
  final AtomicBoolean field = new AtomicBoolean();
  final List<String> pushed = new CopyOnWriteArrayList<>();
  @Nullable Manager manager;

  @BeforeEach
  void aBoardAndTheRobotsPacks() throws Exception {
    agent = new LocalAgent(dir.resolve("board"), "vision-front");
    packs = dir.resolve("deploy/spotter-packs");
    Path team = packs.resolve("team");
    Files.createDirectories(team);
    Files.writeString(team.resolve("pack.yaml"), TEAM);
    Files.writeString(team.resolve("health"), "#!/bin/sh\necho '{\"answer\": 42}'\n");
    Files.setPosixFilePermissions(
        team.resolve("health"), PosixFilePermissions.fromString("rwxr-xr-x"));
    hash = PackHash.of(packs);
  }

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    agent.close();
  }

  private Manager manage(Settings settings) {
    return manage(settings, "team");
  }

  /** A manager of the board, which is to have these of the team's packs. */
  private Manager manage(Settings settings, String... packs) {
    return manage(settings, System::nanoTime, packs);
  }

  /** A manager of the board, on this clock, which is to have these of the team's packs. */
  private Manager manage(Settings settings, LongSupplier clock, String... packs) {
    Manager started =
        new Manager(
            new RobotSignals(enabled::get, field::get, clock),
            List.of(new BoardDefinition(agent.address(), packs)),
            settings.withPacks(this.packs).withKey(dir.resolve("no.key")),
            new Recorder() {
              @Override
              public void pushed(Board board, String packs, boolean forced) {
                pushed.add(board.name() + " " + packs + (forced ? " forced" : ""));
              }
            });
    manager = started;
    started.start();
    return started;
  }

  /** Whether the board runs the pushed pack: its value has come. */
  private static boolean runsTheTeamsPack(Board board) {
    Value answer = board.value("team.health.answer");
    return answer.number() == 42;
  }

  @Test
  void aBoardWhosePacksDifferIsPushedThemRestartsAndMatches() throws Exception {
    agent.start();
    Manager manager = manage(Settings.DEFAULTS);
    Board board = manager.boards().get(0);
    await(manager, "the pushed pack's value", () -> runsTheTeamsPack(board));
    assertThat(board.description().getPushedPacks()).isEqualTo(hash);
    assertThat(board.description().getPacks().get(0).getPushed()).isTrue();
    assertThat(agent.exits()).isEqualTo(1);
    assertThat(pushed).containsExactly("vision-front " + hash);
    assertThat(manager.alerts()).isEmpty();
    // Its pushed packs keep the hash: the agent hashed what it unpacked.
    assertThat(PackHash.of(agent.pushedPacks())).isEqualTo(hash);

    // Another manager, as after the robot program restarts: the packs match, nothing is pushed.
    manager.close();
    Manager again = manage(Settings.DEFAULTS);
    Board same = again.boards().get(0);
    await(again, "the pack's value", () -> runsTheTeamsPack(same));
    Thread.sleep(500);
    assertThat(agent.exits()).isEqualTo(1);
    assertThat(pushed).hasSize(1);
  }

  @Test
  void onTheFieldTheyArentPushedUntilItsOff() throws Exception {
    agent.start();
    field.set(true);
    Manager manager = manage(Settings.DEFAULTS);
    Board board = manager.boards().get(0);
    await(manager, "a warning", () -> !manager.alerts().isEmpty());
    assertThat(manager.alerts())
        .containsExactly(
            new Alert(
                board.address() + "/packs",
                Level.WARNING,
                "vision-front",
                "vision-front's packs differ from the robot's: they'll be pushed off the field"));
    assertThat(agent.exits()).isZero();

    // Enabled off the field: still not.
    field.set(false);
    enabled.set(true);
    Thread.sleep(500);
    assertThat(agent.exits()).isZero();

    enabled.set(false);
    await(manager, "the pushed pack's value", () -> runsTheTeamsPack(board));
    assertThat(manager.alerts()).isEmpty();
    assertThat(agent.exits()).isEqualTo(1);
  }

  @Test
  void aBoardThatRefusesPushesIsWarnedAboutAndRefusesAForcedOne() throws Exception {
    agent.config("{\"acceptPushes\": false}").start();
    Manager manager = manage(Settings.DEFAULTS);
    Board board = manager.boards().get(0);
    await(manager, "a warning", () -> !manager.alerts().isEmpty());
    assertThat(manager.alerts().get(0).text())
        .isEqualTo("vision-front's packs differ from the robot's, and it refuses pushes");
    assertThatThrownBy(() -> manager.push(board).get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasMessageContaining("it answered 403: this board refuses pushes");
    assertThat(agent.exits()).isZero();
  }

  @Test
  void withAutomaticPushesOffOnlyAForcedPushPushes() throws Exception {
    agent.start();
    Manager manager = manage(Settings.DEFAULTS.withPushAutomatically(false));
    Board board = manager.boards().get(0);
    await(manager, "a warning", () -> !manager.alerts().isEmpty());
    assertThat(manager.alerts().get(0).text())
        .isEqualTo("vision-front's packs differ from the robot's, and automatic pushes are off");
    assertThat(agent.exits()).isZero();

    // Forced, on the field and enabled: it's robot code's call.
    field.set(true);
    enabled.set(true);
    manager.push(board).get(10, TimeUnit.SECONDS);
    await(manager, "the pushed pack's value", () -> runsTheTeamsPack(board));
    assertThat(manager.alerts()).isEmpty();
    assertThat(pushed).containsExactly("vision-front " + hash + " forced");
  }

  @Test
  void aPushThatFailsIsSaidAndNotTriedAgainInALoop() throws Exception {
    // Nowhere to put pushed packs: a read-only root, as a board's can be.
    Path lib = agent.path("/var/lib/frc-spotter");
    Files.createDirectories(lib);
    Files.setPosixFilePermissions(lib, PosixFilePermissions.fromString("r-xr-xr-x"));
    assumeFalse(Files.isWritable(lib));
    agent.start();
    Manager manager = manage(Settings.DEFAULTS);
    Board board = manager.boards().get(0);
    await(manager, "a warning", () -> !manager.alerts().isEmpty());
    assertThat(manager.alerts().get(0).text())
        .startsWith(
            "vision-front's packs differ from the robot's, and pushing them failed: it answered 500");
    Link link = manager.link(0);
    long tried = link.pushes();
    Thread.sleep(1500);
    manager.update();
    assertThat(link.pushes()).isEqualTo(tried).isEqualTo(1);
    assertThat(agent.exits()).isZero();
    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);
  }

  @Test
  void aBoardThatTookAPushAndDidntRestartHasFailedItAndIsTriedOnceMore() throws Exception {
    agent.wontEndItself().start();
    AtomicLong clock = new AtomicLong(1_000_000_000L);
    Manager manager = manage(Settings.DEFAULTS, clock::get, "team");
    Link link = manager.link(0);
    String failed =
        "vision-front's packs differ from the robot's, and pushing them failed: it took them, and"
            + " didn't restart to read them within 5000 ms";
    for (int pushes = 1; pushes <= 2; pushes++) {
      int taken = pushes;
      await(manager, "push " + taken + " taken", () -> agent.exits() == taken);
      assertThat(manager.alerts()).isEmpty();
      // Past its time to restart, on the robot's clock: the push has failed, saying so.
      clock.addAndGet(6_000_000_000L);
      await(manager, "push " + taken + " failed", () -> said(manager, failed));
    }
    // Tried once more, and no more: its stream has had heartbeats enough to push a third time.
    long events = link.events();
    await(manager, "two more seconds of heartbeats", () -> link.events() >= events + 8);
    clock.addAndGet(6_000_000_000L);
    long later = link.events();
    await(manager, "two more seconds of heartbeats", () -> link.events() >= later + 8);
    assertThat(link.pushes()).isEqualTo(2);
    assertThat(agent.exits()).isEqualTo(2);
    assertThat(said(manager, failed)).isTrue();
  }

  private static boolean said(Manager manager, String text) {
    return manager.alerts().stream().anyMatch(alert -> alert.text().equals(text));
  }

  @Test
  void aManagerWithNoPacksPushesNothingAndCantForceAPush() throws Exception {
    agent.start();
    Manager started = new Manager(Boards.robot(), List.of(new BoardDefinition(agent.address())));
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(started, "connected", () -> board.connection() == Connection.CONNECTED);
    assertThatThrownBy(() -> started.push(board).get(10, TimeUnit.SECONDS))
        .hasMessageContaining("the manager was given no packs to push");
    Board stranger =
        new Manager(Boards.robot(), List.of(new BoardDefinition("10.12.34.11"))).boards().get(0);
    assertThatThrownBy(() -> started.push(stranger).get())
        .hasMessageContaining("isn't one of this manager's boards");
  }

  @Test
  void packsThatCantBeReadAreSaid() throws Exception {
    agent.start();
    Path unreadable = dir.resolve("deploy/locked");
    Files.createDirectories(unreadable.resolve("team"));
    Files.writeString(unreadable.resolve("team/pack.yaml"), TEAM);
    Files.setPosixFilePermissions(
        unreadable.resolve("team/pack.yaml"), PosixFilePermissions.fromString("---------"));
    // Root reads anything: nothing to see there.
    assumeFalse(Files.isReadable(unreadable.resolve("team/pack.yaml")));
    Manager started =
        new Manager(
            Boards.robot(),
            List.of(new BoardDefinition(agent.address())),
            Settings.DEFAULTS.withPacks(unreadable).withKey(dir.resolve("no.key")),
            Recorder.NONE);
    manager = started;
    assertThat(started.alerts()).hasSize(1);
    assertThat(started.alerts().get(0).text())
        .startsWith("the team's Spotter packs can't be read (")
        .endsWith("): nothing is pushed");
  }

  /** A pack of one value, its script printing it: {@code <name>.health.answer}. */
  private static void pack(Path folder, String name, int answer) throws Exception {
    Path pack = folder.resolve(name);
    Files.createDirectories(pack);
    Files.writeString(pack.resolve("pack.yaml"), TEAM.replace("pack: team", "pack: " + name));
    // No execute bit, as a deploy may leave it: its #! carries it.
    Files.writeString(pack.resolve("health"), "#!/bin/sh\necho '{\"answer\": " + answer + "}'\n");
  }

  @Test
  void eachBoardGetsExactlyThePacksItNamesAndNoneOfTheFoldersOtherFiles() throws Exception {
    Path all = dir.resolve("deploy/all-packs");
    pack(all, "common", 1);
    pack(all, "vision", 2);
    pack(all, "detector", 3);
    // Not packs: a file at the folder's top, and a folder without a pack.yaml.
    Files.writeString(all.resolve("README.md"), "The robot's packs.\n");
    Files.createDirectories(all.resolve("notes"));
    Files.writeString(all.resolve("notes/todo.txt"), "nothing\n");
    try (LocalAgent other = new LocalAgent(dir.resolve("other"), "detector-board")) {
      agent.start();
      other.start();
      Manager both =
          new Manager(
              new RobotSignals(enabled::get, field::get, System::nanoTime),
              List.of(
                  new BoardDefinition(agent.address(), "common", "vision"),
                  new BoardDefinition(other.address(), "common", "detector")),
              Settings.DEFAULTS.withPacks(all).withKey(dir.resolve("no.key")),
              Recorder.NONE);
      manager = both;
      both.start();
      Board vision = both.board(agent.address());
      Board detector = both.board(other.address());
      await(
          both,
          "each board's packs",
          () ->
              vision.value("vision.health.answer").available()
                  && detector.value("detector.health.answer").available());
      assertThat(vision.description().getPacks())
          .extracting(pack -> pack.getName())
          .containsExactly("common", "vision");
      assertThat(detector.description().getPacks())
          .extracting(pack -> pack.getName())
          .containsExactly("common", "detector");
      assertThat(vision.problems()).isEmpty();
      assertThat(detector.problems()).isEmpty();
      assertThat(Files.exists(agent.pushedPacks().resolve("README.md"))).isFalse();
      assertThat(Files.exists(agent.pushedPacks().resolve("notes"))).isFalse();
      assertThat(both.alerts()).isEmpty();
      assertThat(both.boards()).containsExactly(vision, detector);
    }
  }

  @Test
  void aBoardThatNamesNoPacksHasThosePushedBeforeTakenAway() throws Exception {
    agent.start();
    Manager first = manage(Settings.DEFAULTS);
    Board board = first.boards().get(0);
    await(first, "the pushed pack's value", () -> runsTheTeamsPack(board));
    first.close();

    // The robot program, changed to give the board none of the team's packs.
    Manager none = manage(Settings.DEFAULTS, new String[0]);
    Board bare = none.boards().get(0);
    await(
        none,
        "the pushed packs taken away",
        () ->
            bare.description().getRevision() != 0 && bare.description().getPushedPacks().isEmpty());
    assertThat(bare.description().getPacks()).isEmpty();
    assertThat(bare.value("team.health.answer").available()).isFalse();
    assertThat(agent.exits()).isEqualTo(2);
    assertThat(pushed).containsExactly("vision-front " + hash, "vision-front ");
    assertThat(none.alerts()).isEmpty();
  }

  @Test
  void aPackThatIsntThereOrPacksWithNoFolderAreSaid() throws Exception {
    agent.start();
    Manager manager = manage(Settings.DEFAULTS, "team", "nothing");
    await(manager, "described", () -> manager.boards().get(0).description().getRevision() != 0);
    assertThat(manager.alerts())
        .extracting(Alert::text)
        .contains(
            agent.address()
                + " is to have the pack nothing, which isn't among the team's packs ("
                + packs
                + "): a pack is a folder there with a pack.yaml");
    manager.close();

    Manager noFolder =
        new Manager(
            Boards.robot(),
            List.of(new BoardDefinition(agent.address(), "team")),
            Settings.DEFAULTS.withKey(dir.resolve("no.key")),
            Recorder.NONE);
    this.manager = noFolder;
    assertThat(noFolder.alerts())
        .extracting(Alert::text)
        .containsExactly(
            agent.address()
                + " is to have the packs team, but the manager has no packs folder: nothing is"
                + " pushed");
  }

  @Test
  void aPacksFolderThatIsntThereIsSaidAndNothingIsPushed() throws Exception {
    Manager started =
        new Manager(
            Boards.robot(),
            List.of(new BoardDefinition(agent.address(), "team")),
            Settings.DEFAULTS
                .withPacks(dir.resolve("deploy/nowhere"))
                .withKey(dir.resolve("no.key")),
            Recorder.NONE);
    manager = started;
    assertThat(started.alerts())
        .extracting(Alert::text)
        .containsExactly(
            "the team's Spotter packs can't be read ("
                + dir.resolve("deploy/nowhere")
                + " isn't a folder): nothing is pushed");
  }
}
