package com.michaelgrundvig.frc.spotter.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.agent.PackCheck;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tools as a build or a person runs them: check over the catalog's packs and over a pack full
 * of mistakes, and status against a real agent, and one that isn't there.
 */
class SpotterToolsTest {
  @TempDir Path dir;

  /** What a run printed, and its exit code. */
  record Ran(int code, String out, String err) {}

  static Ran run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        SpotterTools.run(
            List.of(args),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Ran(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  static final Path CATALOG = Path.of(System.getProperty("spotter.catalog", "../packs"));

  @Test
  void theCatalogsPacksPassTheCheck() {
    Ran ran =
        run(
            "check",
            CATALOG.resolve("debian").toString(),
            CATALOG.resolve("photonvision").toString(),
            CATALOG.resolve("raspberry-pi").toString());
    assertThat(ran.code()).as(ran.out()).isEqualTo(SpotterTools.OK);
    assertThat(ran.out()).endsWith("3 packs checked: no problems\n");
    // photonvision's web collector declares its request's own status: a note, no problem.
    assertThat(ran.out())
        .contains("collector web's field status is its command's own exit code or HTTP status")
        .contains("note: " + PackCheck.TRUST_SKIPPED);
  }

  @Test
  void aPacksMistakesAreEachAtTheirFileAndLineAndFailTheCheck() throws Exception {
    Path packs = dir.resolve("spotter-packs");
    Path team = packs.resolve("team");
    Files.createDirectories(team);
    Files.writeString(
        team.resolve("pack.yaml"),
        String.join(
            "\n",
            "pack: team",
            "collectors:",
            "  - id: health.main",
            "    run: [./health]",
            "    every: 1s",
            "    fields:",
            "      mood: {type: status, warn: {equals: sad}}",
            "  - id: gone",
            "    run: [./missing]",
            "    evry: 1s",
            ""));
    // No execute bit, and no #!: it won't run once pushed.
    Files.writeString(team.resolve("health"), "echo '{}'\n");
    Files.writeString(packs.resolve("README.md"), "the team's packs\n");
    Ran ran = run("check", packs.toString());
    assertThat(ran.code()).isEqualTo(SpotterTools.FOUND);
    String file = team.toString().replace('\\', '/') + "/pack.yaml";
    assertThat(ran.out())
        .contains(file + ":3: collector id \"health.main\" has a dot")
        .contains(file + ":4: ./health has neither an execute bit nor a #! first line")
        .contains(file + ":7: equals on a status is never applied")
        .contains(file + ":9: ./missing isn't in the pack's folder")
        .contains(file + ":10: unknown key \"evry\"")
        .contains("/spotter-packs/README.md: not a pack")
        .contains("0 packs checked: ");
  }

  @Test
  void aCommandItDoesntKnowOrNothingToCheckIsUsage() {
    assertThat(run().code()).isEqualTo(SpotterTools.USAGE);
    assertThat(run("checks").err()).startsWith("spotter-tools: no command checks");
    assertThat(run("check").code()).isEqualTo(SpotterTools.USAGE);
    assertThat(run("status").code()).isEqualTo(SpotterTools.USAGE);
    assertThat(run("status", "a", "b").code()).isEqualTo(SpotterTools.USAGE);
    assertThat(run("status", "a", "--wait=soon").code()).isEqualTo(SpotterTools.USAGE);
    assertThat(run("help").out()).contains("check <folder>...");
    assertThat(run("check", dir.resolve("nothing").toString()).out()).contains(": no such folder");
  }

  @Test
  void statusPrintsABoardsProblemsValuesIdentityAndPacks() throws Exception {
    try (LocalAgent agent = new LocalAgent(dir.resolve("board"), "vision-front")) {
      agent
          .pack(
              "vision",
              String.join(
                  "\n",
                  "pack: vision",
                  "version: 1.2.0",
                  "collectors:",
                  "  - id: health",
                  "    run: [./health]",
                  "    every: 1h",
                  "    fields:",
                  "      fps: {label: Frame rate, type: number, unit: fps, warn: {below: 30}}",
                  "      mode: {type: text}",
                  "      armed: {type: boolean}",
                  "      camera: {type: text}",
                  ""))
          .script(
              "vision", "health", "echo '{\"fps\": 12.5, \"mode\": \"tracking\", \"armed\": true}'")
          .pack("broken", "pack: broken\nversoin: 1\n")
          .start();
      Ran ran = run("status", agent.address());
      assertThat(ran.code()).as(ran.out()).isEqualTo(SpotterTools.OK);
      String[] lines = ran.out().split("\n");
      assertThat(lines[0])
          .isEqualTo("vision-front (" + agent.address() + "): agent 0.4.0-test, protocol 2.0");
      assertThat(lines[1]).isEqualTo("Problems: 1");
      assertThat(lines[2]).contains("/broken/pack.yaml:2: unknown key \"versoin\"");
      assertThat(ran.out())
          .contains("  vision.health.fps     12.5 fps  (Frame rate)  warning: below 30 fps\n")
          .contains("  vision.health.mode    tracking\n")
          .contains("  vision.health.armed   true\n")
          .contains("  vision.health.camera  unavailable: its output has no \"camera\"\n")
          .contains("  hostname   vision-front\n")
          .contains("  vision 1.2.0  installed  /etc/frc-spotter/packs/vision\n");
    }
  }

  @Test
  void statusOfABoardNotThereSaysWhyInPlainWords() throws Exception {
    int port;
    try (ServerSocket free = new ServerSocket(0)) {
      port = free.getLocalPort();
    }
    Ran ran = run("status", "127.0.0.1:" + port, "--wait=1s");
    assertThat(ran.code()).isEqualTo(SpotterTools.FOUND);
    assertThat(ran.out())
        .startsWith(
            "127.0.0.1:"
                + port
                + ": not reached: connection refused: is frc-spotter running on it?");
  }

  @Test
  void durationsAreMillisecondsSecondsOrMinutes() {
    assertThat(SpotterTools.duration("500ms")).hasMillis(500);
    assertThat(SpotterTools.duration("5s")).hasSeconds(5);
    assertThat(SpotterTools.duration("2m")).hasMinutes(2);
    assertThat(SpotterTools.duration("soon")).isNull();
  }
}
