package com.michaelgrundvig.frc.spotter.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import com.michaelgrundvig.frc.spotter.settings.SettingsRow;
import com.michaelgrundvig.frc.spotter.table.Board;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build's checks and the compiled table, on a repository made for each test. */
class CoprocessorBuildTest {
  static final String VERSION = "dev-v2027.0.0-alpha-2-69-g71416112";
  static final String SHA = "edf2bda3032579d759de46aab0e8094cfd3de3586ba764b663470d2b80351cb7";

  @TempDir Path root;

  static String lock(String version, String jarSha256) {
    StringBuilder images = new StringBuilder();
    for (Board board : Board.values()) {
      images
          .append(images.isEmpty() ? "" : ",")
          .append("\"")
          .append(board.id())
          .append("\":{\"url\":\"https://example.org/")
          .append(board.id())
          .append(".img.xz\",\"sha256\":\"")
          .append(SHA)
          .append("\"}");
    }
    return "{\"version\":\""
        + version
        + "\",\"jar\":{\"url\":\"https://example.org/photonvision.jar\",\"sha256\":\""
        + jarSha256
        + "\"},\"images\":{"
        + images
        + "}}";
  }

  private void write(String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  @BeforeEach
  void aRepository() throws IOException {
    write("vendordeps/photonlib.json", "{\"name\":\"photonlib\",\"version\":\"" + VERSION + "\"}");
    write("coprocessor/photonvision.lock", lock(VERSION, "PLACEHOLDER: not archived yet"));
    write(
        "coprocessors/coprocessors.yaml",
        """
        team: 1234
        computers:
          - name: vision-front
            address: 11
            board: orangepi-5
            cameras: [front-left]
          - name: vision-back
            address: 12
            board: orangepi-5-plus
        """);
  }

  @Test
  void aLockMatchingPhotonLibPassesAndNamesItsPlaceholders() throws IOException {
    assertThat(CoprocessorBuild.checkLock(root))
        .isEqualTo(
            "coprocessor/photonvision.lock: not yet known, so images can't be built yet:"
                + " jar.sha256\n");
    write("coprocessor/photonvision.lock", lock(VERSION, SHA));
    assertThat(CoprocessorBuild.checkLock(root)).isEmpty();
  }

  @Test
  void aLockForAnotherVersionFailsTheBuild() throws IOException {
    write("coprocessor/photonvision.lock", lock("v2027.1.0", SHA));
    assertThatThrownBy(() -> CoprocessorBuild.checkLock(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith(
            "coprocessor/photonvision.lock locks PhotonVision v2027.1.0, but"
                + " vendordeps/photonlib.json is "
                + VERSION
                + ". They must match");

    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Path marker = root.resolve("build/checked");
    Files.createDirectories(marker.getParent());
    int status =
        CoprocessorBuild.run(
            new String[] {"check-lock", root.toString(), marker.toString()},
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    assertThat(status).isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("They must match");
    assertThat(marker).doesNotExist();
  }

  @Test
  void aBrokenLockSaysWhatsWrong() {
    assertThatThrownBy(() -> PhotonVisionLock.parse("{"))
        .hasMessageStartingWith("coprocessor/photonvision.lock: line 1");
    assertThatThrownBy(
            () ->
                PhotonVisionLock.parse(
                    "{\"jar\":{\"url\":\"ftp://x\",\"sha256\":\"abc\"},\"images\":{"
                        + "\"orangepi-9\":{},\"orangepi-5\":{\"url\":\"http://x\",\"sha256\":\"x\"}}}"))
        .hasMessageContaining("version is missing")
        .hasMessageContaining("jar.url must be an https:// address or start with PLACEHOLDER")
        .hasMessageContaining("jar.sha256 must be a SHA-256")
        .hasMessageContaining("images: unknown board orangepi-9")
        .hasMessageContaining("images.orangepi-5.url must be an https:// address")
        .hasMessageContaining("images.orangepi-5.sha256 must be a SHA-256")
        .hasMessageContaining("images has no orangepi-5b");
    assertThatThrownBy(() -> PhotonVisionLock.vendordepVersion("{}"))
        .hasMessage("vendordeps/photonlib.json has no version");
    assertThatThrownBy(() -> PhotonVisionLock.vendordepVersion("["))
        .hasMessageStartingWith("vendordeps/photonlib.json: line 1");
  }

  @Test
  void theLockNamesEveryPlaceholder() {
    PhotonVisionLock lock =
        PhotonVisionLock.parse(
            lock(VERSION, SHA)
                .replace("https://example.org/photonvision.jar", "PLACEHOLDER: archive it")
                .replaceFirst(SHA, "PLACEHOLDER: later"));
    assertThat(lock.placeholders()).containsExactly("jar.url", "jar.sha256");
    assertThat(Objects.requireNonNull(lock.images().get(Board.ORANGEPI_5_MAX)).url())
        .isEqualTo("https://example.org/orangepi-5-max.img.xz");
  }

  @Test
  void theTableIsCompiledWithEachComputersCommittedSettingsHash() throws IOException {
    Settings settings =
        new Settings(
            2,
            List.of(SettingsRow.fromText("global", "hardwareSettings", Map.of("contents", "{}"))));
    SettingsFiles.write(settings, root.resolve(SettingsFiles.folder("vision-front")));
    write("build/libraries.txt", "org.xerial:sqlite-jdbc:3.53.4.0\n\n");

    Path out = root.resolve("build/generated/coprocessor/coprocessor/table.json");
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    int status =
        CoprocessorBuild.run(
            new String[] {
              "table",
              root.toString(),
              root.resolve("build/libraries.txt").toString(),
              out.toString()
            },
            new PrintStream(printed, true, StandardCharsets.UTF_8),
            System.err);
    assertThat(printed.toString(StandardCharsets.UTF_8)).startsWith("No recipe hash");
    assertThat(status).isZero();
    CompiledTable compiled = CompiledTable.parse(Files.readString(out, StandardCharsets.UTF_8));
    assertThat(compiled.table().team()).isEqualTo(1234);
    assertThat(compiled.photonvisionVersion()).isEqualTo(VERSION);
    // Not a Git checkout, so no recipe hash.
    assertThat(compiled.recipeHash()).isEmpty();
    assertThat(compiled.settingsHashes())
        .containsOnly(Map.entry("vision-front", settings.hash()), Map.entry("vision-back", ""));
  }

  @Test
  void aBrokenTableOrSettingsFailsTheBuildSayingWhere() throws IOException {
    write("coprocessors/coprocessors.yaml", "team: 0\ncomputers:\n  - name: Bad\n");
    assertThatThrownBy(() -> CoprocessorBuild.compile(root, List.of(), System.out))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coprocessors.yaml:3: name \"Bad\" isn't a hostname");

    write(
        "coprocessors/coprocessors.yaml",
        "team: 0\ncomputers:\n  - name: a\n    address: 11\n    board: orangepi-5\n");
    write("coprocessors/a/settings/database.json", "{\"userVersion\": 2}");
    write("coprocessors/a/settings/global/x.json", "{");
    assertThatThrownBy(() -> CoprocessorBuild.compile(root, List.of(), System.out))
        .hasMessageStartingWith("coprocessors/a/settings: global/x.json: line 1");

    Files.delete(root.resolve("vendordeps/photonlib.json"));
    assertThatThrownBy(() -> CoprocessorBuild.compile(root, List.of(), System.out))
        .hasMessage("vendordeps/photonlib.json is missing");
  }

  @Test
  void theRecipeHashIsPrintedAndWritten() throws Exception {
    Path libraries = root.resolve("libraries.txt");
    Files.writeString(libraries, "org.xerial:sqlite-jdbc:3.53.4.0\n");
    Process init =
        new ProcessBuilder(
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "-c",
                "commit.gpgsign=false",
                "init",
                "-q")
            .directory(root.toFile())
            .start();
    assumeTrue(init.waitFor() == 0, "needs git");
    Process commit =
        new ProcessBuilder(
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "empty")
            .directory(root.toFile())
            .start();
    assumeTrue(commit.waitFor() == 0);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Path written = root.resolve("build/recipe-hash.txt");
    assertThat(
            CoprocessorBuild.run(
                new String[] {
                  "recipe-hash", root.toString(), libraries.toString(), written.toString()
                },
                new PrintStream(out, true, StandardCharsets.UTF_8),
                System.err))
        .isZero();
    String hash = RecipeHash.of(root, List.of("org.xerial:sqlite-jdbc:3.53.4.0")).hash();
    assertThat(out.toString(StandardCharsets.UTF_8).strip()).isEqualTo(hash).hasSize(64);
    assertThat(Files.readString(written)).isEqualTo(hash + "\n");
  }

  @Test
  void aRecipeHashThatCantBeComputedFailsAndAWrongCommandIsShownHowToRun() throws IOException {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream printOut =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    PrintStream printErr = new PrintStream(err, true, StandardCharsets.UTF_8);
    Path libraries = root.resolve("libraries.txt");
    Files.writeString(libraries, "org.xerial:sqlite-jdbc:3.53.4.0\n");
    assertThat(
            CoprocessorBuild.run(
                new String[] {"recipe-hash", root.toString(), libraries.toString(), "out"},
                printOut,
                printErr))
        .isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("No recipe hash: ");
    assertThat(CoprocessorBuild.run(new String[] {"nope"}, printOut, printErr)).isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage:");
  }
}
