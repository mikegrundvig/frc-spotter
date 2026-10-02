package com.michaelgrundvig.frc.spotter.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recipe hash: what goes onto a board, committed, with the agent's libraries, and nothing else.
 */
class RecipeHashTest {
  static final List<String> LIBRARIES =
      List.of("org.xerial:sqlite-jdbc:3.53.4.0", "org.jspecify:jspecify:1.0.1");

  @TempDir Path root;

  private void write(String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /** Runs git in the repository; false when there's no git to run. */
  private boolean git(String... args) throws IOException, InterruptedException {
    List<String> command =
        new ArrayList<>(
            List.of(
                "git",
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "core.autocrlf=false"));
    command.addAll(List.of(args));
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .directory(root.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException e) {
      return false;
    }
    return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0;
  }

  /** The object ID git gives a file's content. */
  private static String blob(String content) throws NoSuchAlgorithmException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(sha1.digest(bytes));
  }

  private static String sha256(String text) throws NoSuchAlgorithmException {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  /** A repository with a recipe, and what's around it; committed. */
  private void aRepository() throws Exception {
    write("coprocessor/image/provision.sh", "#!/bin/sh\n");
    write("coprocessor/agent/coprocessor-agent.service", "[Service]\n");
    write("coprocessor/photonvision.lock", "{}\n");
    write("gradle/quality.gradle", "// checks\n");
    // Not the recipe:
    write("coprocessor/README.md", "docs\n");
    write("coprocessor/image/README.md", "docs\n");
    write("coprocessor/image/test/provision_test.sh", "test\n");
    write("coprocessor/agent/src/test/java/ATest.java", "class ATest {}\n");
    write("coprocessor/common/src/testFixtures/resources/x.sql", "x\n");
    write("coprocessor/common/coverage-floor.properties", "line=50\n");
    write("build.gradle", "plugins { id 'org.wpilib.GradleRIO' version '1' }\n");
    write("settings.gradle", "include 'x'\n");
    write("gradle/libs.versions.toml", "[versions]\n");
    write("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=x\n");
    write("src/main/java/Robot.java", "class Robot {}\n");
    assumeTrue(git("init", "-q"), "needs git");
    assumeTrue(git("add", "."));
    assumeTrue(git("commit", "-q", "-m", "a repository"));
  }

  private void commit(String path, String text) throws Exception {
    write(path, text);
    assumeTrue(git("add", "."));
    assumeTrue(git("commit", "-q", "-m", "changed " + path));
  }

  @Test
  void theHashIsOfTheCommittedRecipeAndTheAgentsLibraries() throws Exception {
    aRepository();
    String expected =
        sha256(
            "100644 blob "
                + blob("[Service]\n")
                + "\tcoprocessor/agent/coprocessor-agent.service\n"
                + "100644 blob "
                + blob("#!/bin/sh\n")
                + "\tcoprocessor/image/provision.sh\n"
                + "100644 blob "
                + blob("{}\n")
                + "\tcoprocessor/photonvision.lock\n"
                + "100644 blob "
                + blob("// checks\n")
                + "\tgradle/quality.gradle\n"
                + "runtime org.jspecify:jspecify:1.0.1\n"
                + "runtime org.xerial:sqlite-jdbc:3.53.4.0\n");
    RecipeHash.Result result = RecipeHash.of(root, LIBRARIES);
    assertThat(result.why()).isEmpty();
    assertThat(result.hash()).isEqualTo(expected);
  }

  @Test
  void onlyWhatGoesOntoABoardChangesIt() throws Exception {
    aRepository();
    String hash = RecipeHash.of(root, LIBRARIES).hash();

    // The robot's build, the wrapper, documentation, tests: the same image.
    commit("build.gradle", "plugins { id 'org.wpilib.GradleRIO' version '2' }\n");
    commit("settings.gradle", "include 'x', 'y'\n");
    commit("gradle/libs.versions.toml", "[versions]\nwpilib = \"2\"\n");
    commit("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=y\n");
    commit("coprocessor/image/README.md", "more docs\n");
    commit("coprocessor/agent/src/test/java/ATest.java", "class ATest { int x; }\n");
    assertThat(RecipeHash.of(root, LIBRARIES).hash()).isEqualTo(hash);
    // Uncommitted changes don't count: an image is built from a commit.
    write("coprocessor/image/provision.sh", "#!/bin/sh\necho uncommitted\n");
    assertThat(RecipeHash.of(root, LIBRARIES).hash()).isEqualTo(hash);

    // A new library, or a change to what's built into the image: a new image.
    assertThat(
            RecipeHash.of(
                    root, List.of("org.xerial:sqlite-jdbc:3.54.0.0", "org.jspecify:jspecify:1.0.1"))
                .hash())
        .isNotEqualTo(hash);
    commit("coprocessor/agent/coprocessor-agent.service", "[Service]\nNice=10\n");
    String unit = RecipeHash.of(root, LIBRARIES).hash();
    assertThat(unit).isNotEqualTo(hash);
    commit("gradle/quality.gradle", "// stricter checks\n");
    assertThat(RecipeHash.of(root, LIBRARIES).hash()).isNotEqualTo(unit);
  }

  @Test
  void withoutGitThereIsNoHashAndItSaysWhy() throws IOException {
    RecipeHash.Result none = RecipeHash.of(root, LIBRARIES);
    assertThat(none.hash()).isEmpty();
    assertThat(none.why()).isNotEmpty();
  }

  @Test
  void docsAndTestsAreLeftOutHoweverGitQuotesTheirPaths() {
    assertThat(RecipeHash.inRecipe("coprocessor/image/provision.sh")).isTrue();
    assertThat(RecipeHash.inRecipe("coprocessor/image/bench-procedures.md")).isFalse();
    assertThat(RecipeHash.inRecipe("\"coprocessor/image/caf\\303\\251.md\"")).isFalse();
    assertThat(RecipeHash.inRecipe("\"coprocessor/image/test/caf\\303\\251.sh\"")).isFalse();
    assertThat(RecipeHash.inRecipe("\"coprocessor/image/caf\\303\\251.sh\"")).isTrue();
    assertThat(RecipeHash.inRecipe("coprocessor/common/src/testFixtures/x.sql")).isFalse();
    assertThat(RecipeHash.inRecipe("coprocessor/agent/coverage-floor.properties")).isFalse();
  }

  @Test
  void aListingHashesItsRecipeLinesAndLibrariesOnly() throws NoSuchAlgorithmException {
    assertThat(RecipeHash.of("100644 blob abc\tcoprocessor/image/README.md\n", List.of()))
        .isEqualTo(sha256(""));
    assertThat(RecipeHash.of("", List.of("b:b:1", "a:a:1")))
        .isEqualTo(sha256("runtime a:a:1\nruntime b:b:1\n"));
  }
}
