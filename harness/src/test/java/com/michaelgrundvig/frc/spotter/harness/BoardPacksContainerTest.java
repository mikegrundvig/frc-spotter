package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.Manager;
import com.michaelgrundvig.frc.spotter.manager.Recorder;
import com.michaelgrundvig.frc.spotter.manager.Settings;
import com.michaelgrundvig.frc.spotter.manager.Value;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * One manager, two boards, each given its own packs from one folder, as robot code assigns them:
 * the pack every board has, and the one named for each. The per-board packs' scripts come without
 * their execute bit, as the robot's deploy may leave them, and run all the same: their {@code #!}
 * carries it.
 */
@ContainerTest
class BoardPacksContainerTest {
  @TempDir Path dir;

  /** A pack of one number, {@code <name>.answer.n}, its script without its execute bit. */
  private static void pack(Path packs, String name, int answer) throws Exception {
    Path pack = packs.resolve(name);
    Files.createDirectories(pack);
    Files.writeString(
        pack.resolve("pack.yaml"),
        "pack: "
            + name
            + "\ncollectors:\n  - id: answer\n    run: [./answer]\n    every: 1s\n"
            + "    fields:\n      n: {type: number}\n");
    Files.writeString(pack.resolve("answer"), "#!/bin/sh\necho " + answer + "\n");
    Files.setPosixFilePermissions(
        pack.resolve("answer"), PosixFilePermissions.fromString("rw-r--r--"));
  }

  private static boolean has(Board board, String id) {
    Value value = board.value(id);
    return value != null && value.available();
  }

  @Test
  void twoBoardsGetTheirOwnPacksFromOneFolder() throws Exception {
    Path packs = dir.resolve("spotter-packs");
    Path team = packs.resolve("team");
    Files.createDirectories(team);
    Path source = TestImages.folder("pushed/team");
    for (String file : new String[] {"pack.yaml", "greet"}) {
      Files.copy(source.resolve(file), team.resolve(file));
    }
    Files.setPosixFilePermissions(
        team.resolve("greet"), PosixFilePermissions.fromString("rwxr-xr-x"));
    pack(packs, "vision", 1);
    pack(packs, "detector", 2);
    try (Network network = TestNetwork.create();
        Coprocessor front = new Coprocessor(TestImages.agent(), network, 53, "vision-front");
        Coprocessor back = new Coprocessor(TestImages.agent(), network, 54, "detector-back")) {
      front.start();
      back.start();
      front.restartAgent("--controller=" + front.robotAddress());
      back.restartAgent("--controller=" + back.robotAddress());
      String vision = front.agentHost() + ":" + front.agentPort();
      String detector = back.agentHost() + ":" + back.agentPort();
      try (Manager manager =
          new Manager(
              ManagerContainerTest.robot(),
              List.of(vision, detector),
              Settings.DEFAULTS
                  .withPacks(packs)
                  .withBoardPacks(vision, "vision")
                  .withBoardPacks(detector, "detector")
                  .withKey(dir.resolve("none.key")),
              Recorder.NONE)) {
        manager.start();
        Board one = manager.boards().get(0);
        Board two = manager.boards().get(1);
        ManagerContainerTest.await(
            manager,
            "each board running its own packs",
            () ->
                has(one, "vision.answer.n")
                    && has(one, "team.greet.greeting")
                    && has(two, "detector.answer.n")
                    && has(two, "team.greet.greeting"));
        assertThat(pushed(one)).containsExactly("team", "vision");
        assertThat(pushed(two)).containsExactly("detector", "team");
        assertThat(java.util.Objects.requireNonNull(one.value("vision.answer.n")).number())
            .isEqualTo(1);
        assertThat(java.util.Objects.requireNonNull(two.value("detector.answer.n")).number())
            .isEqualTo(2);
        assertThat(one.value("detector.answer.n")).isNull();
        assertThat(two.value("vision.answer.n")).isNull();
        // Written as executable, as its #! said.
        assertThat(
                front.run("stat", "-c", "%a", "/var/lib/frc-spotter/packs/vision/answer").strip())
            .isEqualTo("755");
      }
    }
  }

  /** The names of a board's pushed packs. */
  private static List<String> pushed(Board board) {
    List<String> names = new java.util.ArrayList<>();
    for (Spotter.Pack pack : board.description().getPacks()) {
      if (pack.getPushed()) {
        names.add(pack.getName());
      }
    }
    return names;
  }
}
