package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where a board's packs come from, which win, and which it trusts. */
class PacksTest {
  @TempDir Path dir;
  Fixture fixture;

  private static final String VISION =
      """
      pack: %s
      version: %s
      collectors:
        - id: web
          run: [./web]
          every: 2s
          fields:
            up: {type: boolean}
      logs:
        - id: log
          run: [/opt/vision/log]
      actions:
        - id: restart
          run: [systemctl, restart, vision]
      """;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
  }

  @Test
  void aBoardWithNoPacksHasNoneAndNoProblems() {
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).isEmpty();
    assertThat(loaded.problems()).isEmpty();
    assertThat(loaded.pushedHash()).isEmpty();
  }

  @Test
  void installedAndPushedPacksLoadInTheOrderOfTheirNames() throws Exception {
    fixture.pack("vision", VISION.formatted("vision", "1.0.0"));
    fixture.pushed("detector", "pack: detector\nversion: 0.2.0\n");
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.problems()).isEmpty();
    assertThat(loaded.packs()).extracting(Pack::name).containsExactly("detector", "vision");
    assertThat(loaded.packs().get(0).pushed()).isTrue();
    assertThat(loaded.packs().get(0).folder()).isEqualTo(Packs.PUSHED + "/detector");
    assertThat(loaded.packs().get(1).pushed()).isFalse();
    assertThat(loaded.pushedHash()).isEqualTo(PackHash.of(fixture.path(Packs.PUSHED))).isNotEmpty();
  }

  @Test
  void aPushedPackWinsOverAnInstalledOneOfItsNameAsTheRuleSaysWithNoProblem() {
    fixture.pack("vision", VISION.formatted("vision", "1.0.0"));
    fixture.pushed("vision", VISION.formatted("vision", "2.0.0"));
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).extracting(Pack::version).containsExactly("2.0.0");
    assertThat(loaded.packs().get(0).pushed()).isTrue();
    assertThat(loaded.problems()).isEmpty();
  }

  @Test
  void aBoardThatRefusesPushesIgnoresPushedPacks() {
    fixture.pack("vision", VISION.formatted("vision", "1.0.0"));
    fixture.pushed("vision", VISION.formatted("vision", "2.0.0"));
    Packs.Loaded loaded = Packs.load(fixture.host, false);
    assertThat(loaded.packs()).extracting(Pack::version).containsExactly("1.0.0");
    assertThat(loaded.pushedHash()).isEmpty();
    assertThat(loaded.problems())
        .containsExactly(Packs.PUSHED + ": ignored, as agent.json refuses pushes");
  }

  @Test
  void anInstalledPackWhoseFileAnyoneButRootCouldChangeIsIgnored() {
    fixture.pack("mine", "pack: mine\n");
    fixture.owners.put(Packs.INSTALLED + "/mine/pack.yaml", new Host.Owner("pi", 0644));
    fixture.pack("shared", "pack: shared\n");
    fixture.owners.put(Packs.INSTALLED + "/shared/pack.yaml", Host.Owner.root(0664));
    // A pushed pack is the agent's own, trusted because the controller alone may push.
    fixture.pushed("pushed", "pack: pushed\n");
    fixture.owners.put(Packs.PUSHED + "/pushed/pack.yaml", new Host.Owner("frc-spotter", 0644));
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).extracting(Pack::name).containsExactly("pushed");
    assertThat(loaded.problems())
        .containsExactly(
            Packs.INSTALLED + "/mine/pack.yaml: isn't root's (its owner is pi), ignored",
            Packs.INSTALLED
                + "/shared/pack.yaml: may be written by its group or others (mode 664), ignored");
  }

  @Test
  void aProgramAnyoneButRootCouldChangeIsntRunAndTheRestOfItsPackIs() {
    String folder = fixture.pack("vision", VISION.formatted("vision", "1.0.0"));
    fixture.script(folder + "/web", "echo true");
    fixture.owners.put(folder + "/web", new Host.Owner("pi", 0755));
    fixture.script("/opt/vision/log", "true");
    fixture.owners.put("/opt/vision/log", Host.Owner.root(0775));
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    Pack vision = loaded.packs().get(0);
    assertThat(vision.collectors()).isEmpty();
    assertThat(vision.logs()).isEmpty();
    // systemctl is found on the path, not named by one: nothing to check.
    assertThat(vision.actions()).extracting(Pack.Action::id).containsExactly("restart");
    assertThat(loaded.problems())
        .containsExactly(
            folder
                + "/web: isn't root's (its owner is pi), so vision's collector web isn't run",
            "/opt/vision/log: may be written by its group or others (mode 775), so vision's log log"
                + " isn't run");
  }

  @Test
  void aPacksOwnFileGivenToAnInterpreterIsCheckedToo() {
    String folder =
        fixture.pack(
            "team",
            """
            pack: team
            collectors:
              - id: check
                run: [python3, ./check.py, /opt/team/data.db]
                every: 1s
                fields:
                  ok: {type: boolean}
              - id: hash
                run: [sha256sum, /opt/team/data.db]
                every: 1s
                fields:
                  hash: {type: text}
            """);
    fixture.write(folder + "/check.py", "print('true')\n");
    fixture.owners.put(folder + "/check.py", new Host.Owner("pi", 0644));
    // An absolute path past the program is data, such as a file to hash: not checked.
    fixture.write("/opt/team/data.db", "data");
    fixture.owners.put("/opt/team/data.db", new Host.Owner("pi", 0664));
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs().get(0).collectors())
        .extracting(Pack.Collector::id)
        .containsExactly("hash");
    assertThat(loaded.problems())
        .containsExactly(
            folder
                + "/check.py: isn't root's (its owner is pi), so team's collector check"
                + " isn't run");
  }

  @Test
  void aProgramThatIsntThereIsLeftToFailWhenRun() {
    fixture.pack("vision", VISION.formatted("vision", "1.0.0"));
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.problems()).isEmpty();
    assertThat(loaded.packs().get(0).collectors()).hasSize(1);
  }

  @Test
  void anythingButAPacksFolderIsIgnoredAndSaysWhy() {
    fixture.write(Packs.INSTALLED + "/health.yaml", "pack: health\n");
    fixture.write(Packs.INSTALLED + "/empty/README", "nothing here\n");
    fixture.pack("broken", "pack: broken\ncollectors: [\n");
    fixture.pack("wrong", "pack: other\n");
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).isEmpty();
    assertThat(loaded.problems())
        .hasSize(4)
        .anyMatch(
            p ->
                p.startsWith(Packs.INSTALLED + "/broken/pack.yaml:3: ")
                    && p.endsWith(" (the pack is ignored)"))
        .contains(
            Packs.INSTALLED + "/empty: has no pack.yaml, ignored",
            Packs.INSTALLED
                + "/health.yaml: not a pack (a pack is a folder, <name>/pack.yaml), ignored",
            Packs.INSTALLED
                + "/wrong/pack.yaml:1: pack other is in a folder named wrong: a pack's folder is"
                + " its name (the pack is ignored)");
  }

  @Test
  void aDuplicateNameIgnoresTheWholePackAndProblemsSayWhy() {
    fixture.pack(
        "vision",
        "pack: vision\ncollectors:\n"
            + "  - {id: web, run: [a], every: 1s, fields: {up: {type: boolean}}}\n"
            + "  - {id: web, run: [b], every: 1s, fields: {up: {type: boolean}}}\n");
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).isEmpty();
    assertThat(loaded.problems())
        .containsExactly(
            Packs.INSTALLED
                + "/vision/pack.yaml:4: collector web is declared twice (the pack is ignored)");
  }

  @Test
  void aPushedPackThatFailsToLoadStillCountsInTheHash() throws Exception {
    fixture.pushed("broken", "pack: broken\nnope: 1\n");
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).isEmpty();
    assertThat(loaded.problems()).hasSize(1);
    assertThat(loaded.pushedHash()).isEqualTo(PackHash.of(fixture.path(Packs.PUSHED)));
  }

  /** A pack of {@code collectors} collectors, {@code fields} values each, and some actions. */
  private void pack(String name, int collectors, int fields, int actions) {
    StringBuilder yaml = new StringBuilder("pack: " + name + "\ncollectors:\n");
    for (int c = 0; c < collectors; c++) {
      yaml.append("  - id: c").append(c).append("\n    file: /proc/uptime\n    every: 1s\n");
      yaml.append("    fields:\n");
      for (int f = 0; f < fields; f++) {
        yaml.append("      f").append(f).append(": {type: number}\n");
      }
    }
    if (actions > 0) {
      yaml.append("actions:\n");
      for (int a = 0; a < actions; a++) {
        yaml.append("  - {id: a").append(a).append(", run: [true]}\n");
      }
    }
    fixture.pack(name, yaml.toString());
  }

  @Test
  void aBoardsPacksAreBoundedAndOneThatWouldPassABoundIsIgnoredWhole() {
    // 33 collectors of 64 values: 2,112, past the 2,048 a board may have.
    pack("big", 33, 64, 0);
    pack("small", 1, 1, 0);
    Packs.Loaded loaded = Packs.load(fixture.host, true);
    assertThat(loaded.packs()).extracting(Pack::name).containsExactly("small");
    assertThat(loaded.problems())
        .containsExactly(
            Packs.INSTALLED
                + "/big: ignored, as its 2112 values would pass the 2048 a board may have (0 so"
                + " far)");
  }

  @Test
  void aBoardsActionsAndPacksAreBoundedToo() {
    for (int i = 0; i < 4; i++) {
      pack("a" + i, 0, 0, 32);
    }
    Packs.Loaded actions = Packs.load(fixture.host, true);
    assertThat(actions.packs()).extracting(Pack::name).containsExactly("a0", "a1", "a2");
    assertThat(actions.problems())
        .containsExactly(
            Packs.INSTALLED
                + "/a3: ignored, as its 32 actions would pass the 128 a board may have (98 so"
                + " far)");
  }

  @Test
  void aBoardHasAtMostItsMostPacks(@org.junit.jupiter.api.io.TempDir Path other)
      throws Exception {
    Fixture board = new Fixture(other);
    for (int i = 0; i <= Packs.MAX_PACKS; i++) {
      board.pack(String.format("p%02d", i), String.format("pack: p%02d\n", i));
    }
    Packs.Loaded loaded = Packs.load(board.host, true);
    assertThat(loaded.packs()).hasSize(Packs.MAX_PACKS);
    assertThat(loaded.problems())
        .containsExactly(Packs.INSTALLED + "/p32: ignored, as a board has at most 32 packs");
  }

  @Test
  void whateverReadingOnePackMeetsTheOthersLoad() {
    fixture.pack("vision", VISION.formatted("vision", "1.0.0"));
    fixture.pack("odd", "pack: odd\n");
    Host failing =
        new Host(
            fixture.root,
            path -> {
              if (path.contains("/odd/")) {
                throw new IllegalStateException("a filesystem in a strange state");
              }
              return Host.Owner.root(0644);
            },
            List::of,
            fixture.nanos::get,
            fixture.log::add);
    Packs.Loaded loaded = Packs.load(failing, true);
    assertThat(loaded.packs()).extracting(Pack::name).containsExactly("vision");
    assertThat(loaded.problems())
        .containsExactly(
            Packs.INSTALLED
                + "/odd/pack.yaml: couldn't be read (java.lang.IllegalStateException: a filesystem"
                + " in a strange state), ignored");
  }
}
