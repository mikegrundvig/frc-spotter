package com.michaelgrundvig.frc.spotter.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The hash of the recipe a coprocessor image is built from, which the robot checks each
 * coprocessor's stamp against. It changes when what goes onto a board changes, and only then: the
 * image's scripts and files, the agent's and the shared code with their own build files, the
 * PhotonVision lock, and the libraries the agent runs with. A robot-side change (a GradleRIO or
 * WPILib update, the wrapper, the robot program) leaves it alone, so it never asks for a new image
 * that would be the same.
 *
 * <p>It's the SHA-256, in lowercase hex, of:
 *
 * <ol>
 *   <li>the lines {@code git -c core.quotePath=true ls-tree -r --full-tree HEAD --} prints for
 *       {@link #PATHS}, but those {@link #inRecipe} leaves out (documentation and tests), each
 *       ending in a newline; then
 *   <li>{@code runtime <group>:<name>:<version>} and a newline for each library on the agent's
 *       runtime classpath, sorted.
 * </ol>
 *
 * <p>Committed files only: it's the same on every machine and system, and uncommitted changes don't
 * count, since an image is built from a commit. The build runs this ({@code ./gradlew
 * coprocessorRecipeHash}), for the robot's compiled table and for the image workflow alike.
 */
public final class RecipeHash {
  /** What the recipe covers, from the repository's root. */
  public static final List<String> PATHS =
      List.of(
          "coprocessor/image",
          "coprocessor/agent",
          "coprocessor/common",
          "coprocessor/photonvision.lock",
          "gradle/quality.gradle");

  private RecipeHash() {}

  /**
   * A recipe hash, or why there's none.
   *
   * @param hash 64 lowercase hex digits; empty when there's none
   * @param why why there's none; empty when there is one
   */
  public record Result(String hash, String why) {}

  /**
   * The recipe hash of the repository at {@code root}, with the agent's runtime libraries; empty,
   * with the reason, when there's no Git to ask (not a checkout, no commit yet, no git to run, or
   * the project in a folder of a larger repository).
   */
  public static Result of(Path root, List<String> libraries) throws IOException {
    byte[] prefix = git(root, List.of("git", "rev-parse", "--show-prefix"));
    if (prefix == null) {
      return new Result("", "git rev-parse failed: not a Git checkout, or no git to run");
    }
    if (!new String(prefix, StandardCharsets.UTF_8).isBlank()) {
      return new Result(
          "", "the project is in a folder of its Git repository; the recipe is from its root");
    }
    List<String> command =
        new ArrayList<>(
            List.of(
                "git", "-c", "core.quotePath=true", "ls-tree", "-r", "--full-tree", "HEAD", "--"));
    command.addAll(PATHS);
    byte[] listing = git(root, command);
    if (listing == null) {
      return new Result("", "git ls-tree failed: no commit yet");
    }
    return new Result(of(new String(listing, StandardCharsets.UTF_8), libraries), "");
  }

  /** The hash of a listing, as {@code git ls-tree} printed it, and the libraries. */
  static String of(String listing, List<String> libraries) {
    StringBuilder manifest = new StringBuilder();
    for (String line : listing.split("\n")) {
      int tab = line.indexOf('\t');
      if (tab >= 0 && inRecipe(line.substring(tab + 1))) {
        manifest.append(line).append('\n');
      }
    }
    for (String library : new TreeSet<>(libraries)) {
      manifest.append("runtime ").append(library).append('\n');
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(manifest.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }

  /**
   * Whether a file under {@link #PATHS} (its path from the root, as git prints it) is part of the
   * recipe: all but documentation ({@code *.md}), tests (the image's {@code test/}, and {@code
   * src/test/} and {@code src/testFixtures/}), and the coverage floors.
   */
  static boolean inRecipe(String path) {
    // git quotes a path with unusual characters ("...\303\251..."); the rules hold within quotes.
    String plain =
        path.length() > 1 && path.startsWith("\"") && path.endsWith("\"")
            ? path.substring(1, path.length() - 1)
            : path;
    String name = plain.substring(plain.lastIndexOf('/') + 1);
    return !name.endsWith(".md")
        && !name.equals("coverage-floor.properties")
        && !plain.startsWith("coprocessor/image/test/")
        && !plain.contains("/src/test/")
        && !plain.contains("/src/testFixtures/");
  }

  /** What a git command prints, or null when it can't run or fails. */
  private static byte @Nullable [] git(Path root, List<String> command) throws IOException {
    Process git;
    try {
      git =
          new ProcessBuilder(command)
              .directory(root.toFile())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (IOException e) {
      return null;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = git.getInputStream()) {
      in.transferTo(out);
      if (!git.waitFor(60, TimeUnit.SECONDS)) {
        git.destroyForcibly();
        return null;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      git.destroyForcibly();
      return null;
    }
    return git.exitValue() == 0 ? out.toByteArray() : null;
  }
}
