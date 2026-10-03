package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    Manager started =
        new Manager(
            new Robot(enabled::get, field::get, System::nanoTime),
            List.of(agent.address()),
            settings.withPacks(packs).withKey(dir.resolve("no.key")),
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
    return answer != null && answer.number() == 42;
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
  void aManagerWithNoPacksPushesNothingAndCantForceAPush() throws Exception {
    agent.start();
    Manager started = new Manager(Boards.robot(), List.of(agent.address()));
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(started, "connected", () -> board.connection() == Connection.CONNECTED);
    assertThatThrownBy(() -> started.push(board).get(10, TimeUnit.SECONDS))
        .hasMessageContaining("the manager was given no packs to push");
    Board stranger = new Manager(Boards.robot(), List.of("10.12.34.11")).boards().get(0);
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
            List.of(agent.address()),
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
  void eachBoardGetsThePacksEveryBoardHasAndThoseNamedForIt() throws Exception {
    Path all = dir.resolve("deploy/all-packs");
    pack(all, "common", 1);
    pack(all, "vision", 2);
    pack(all, "detector", 3);
    try (LocalAgent other = new LocalAgent(dir.resolve("other"), "detector-board")) {
      agent.start();
      other.start();
      Manager both =
          new Manager(
              new Robot(enabled::get, field::get, System::nanoTime),
              List.of(agent.address(), other.address()),
              Settings.DEFAULTS
                  .withPacks(all)
                  .withBoardPacks(agent.address(), "vision")
                  .withBoardPacks(other.address(), "detector")
                  .withKey(dir.resolve("no.key")),
              Recorder.NONE);
      manager = both;
      both.start();
      Board vision = both.boards().get(0);
      Board detector = both.boards().get(1);
      await(
          both,
          "each board's packs",
          () ->
              vision.value("vision.health.answer") != null
                  && detector.value("detector.health.answer") != null);
      assertThat(vision.description().getPacks())
          .extracting(pack -> pack.getName())
          .containsExactly("common", "vision");
      assertThat(detector.description().getPacks())
          .extracting(pack -> pack.getName())
          .containsExactly("common", "detector");
      assertThat(vision.description().getPushedPacks())
          .isNotEqualTo(detector.description().getPushedPacks());
      assertThat(both.alerts()).isEmpty();
    }
  }

  @Test
  void packsNamedForABoardThatIsntOneOrAPackThatIsntThereAreSaid() throws Exception {
    agent.start();
    Manager manager =
        manage(
            Settings.DEFAULTS
                .withBoardPacks(agent.address(), "team", "nothing")
                .withBoardPacks("10.26.11.13", "team"));
    await(manager, "described", () -> manager.boards().get(0).description().getRevision() != 0);
    assertThat(manager.alerts())
        .extracting(Alert::text)
        .contains(
            "Spotter's packs for 10.26.11.13 name a board the manager wasn't given: none of its"
                + " addresses is 10.26.11.13",
            "Spotter's packs for "
                + agent.address()
                + " name nothing, which isn't among the team's packs ("
                + packs
                + ")");
  }
}
