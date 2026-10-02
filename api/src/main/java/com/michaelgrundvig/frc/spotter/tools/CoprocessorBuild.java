package com.michaelgrundvig.frc.spotter.tools;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import com.michaelgrundvig.frc.spotter.table.Computer;
import com.michaelgrundvig.frc.spotter.table.Table;
import com.michaelgrundvig.frc.spotter.table.TableException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The build's coprocessor tasks, run by Gradle (gradle/coprocessors.gradle) with this project's
 * code, so the build checks exactly what the robot and the agents do:
 *
 * <ul>
 *   <li>{@code check-lock <repository> <marker>}: fails if the PhotonVision lock's version isn't
 *       PhotonLib's, and names the placeholders still in it;
 *   <li>{@code table <repository> <libraries> <out.json>}: compiles the coprocessor table for the
 *       robot program, with PhotonLib's version, the recipe hash, and each computer's committed
 *       settings hash ({@link CompiledTable});
 *   <li>{@code recipe-hash <repository> <libraries> <out>}: prints the recipe hash ({@link
 *       RecipeHash}), and writes it to {@code <out>}.
 * </ul>
 *
 * <p>{@code <libraries>} is a file of the agent's runtime libraries, {@code group:name:version} one
 * to a line, which the build writes from the agent's classpath.
 *
 * <ul>
 * </ul>
 *
 * <p>A failure prints what's wrong and exits with status 1.
 */
public final class CoprocessorBuild {
  private CoprocessorBuild() {}

  /** Runs one task; see the class. */
  public static void main(String[] args) throws IOException {
    int status = run(args, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  /** Runs one task, printing to {@code out} and {@code err}; the exit status. */
  static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
    try {
      if (args.length == 3 && args[0].equals("check-lock")) {
        out.print(checkLock(Path.of(args[1])));
        Files.writeString(Path.of(args[2]), "checked\n", StandardCharsets.UTF_8);
        return 0;
      }
      if (args.length == 4 && args[0].equals("table")) {
        Path target = Path.of(args[3]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        CompiledTable table = compile(Path.of(args[1]), libraries(Path.of(args[2])), out);
        Files.writeString(target, Json.pretty(table.toJson()), StandardCharsets.UTF_8);
        return 0;
      }
      if (args.length == 4 && args[0].equals("recipe-hash")) {
        RecipeHash.Result recipe = RecipeHash.of(Path.of(args[1]), libraries(Path.of(args[2])));
        if (recipe.hash().isEmpty()) {
          err.println("No recipe hash: " + recipe.why());
          return 1;
        }
        Path target = Path.of(args[3]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, recipe.hash() + "\n", StandardCharsets.UTF_8);
        out.println(recipe.hash());
        return 0;
      }
      err.println(
          "Usage: check-lock <repository> <marker> | table <repository> <libraries> <out.json>"
              + " | recipe-hash <repository> <libraries> <out>");
      return 2;
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /**
   * Checks the lock against PhotonLib's vendordep; what to print (the placeholders left), or an
   * {@link IllegalArgumentException} saying what's wrong.
   */
  static String checkLock(Path root) throws IOException {
    PhotonVisionLock lock =
        PhotonVisionLock.parse(read(root.resolve(PhotonVisionLock.PATH), PhotonVisionLock.PATH));
    String vendordep =
        PhotonVisionLock.vendordepVersion(
            read(root.resolve(PhotonVisionLock.VENDORDEP), PhotonVisionLock.VENDORDEP));
    List<String> problems = lock.checkAgainst(vendordep);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("\n", problems));
    }
    List<String> placeholders = lock.placeholders();
    if (placeholders.isEmpty()) {
      return "";
    }
    return PhotonVisionLock.PATH
        + ": not yet known, so images can't be built yet: "
        + String.join(", ", placeholders)
        + "\n";
  }

  /** The agent's runtime libraries, as the build lists them: one to a line. */
  static List<String> libraries(Path file) throws IOException {
    return read(file, file.toString())
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  /**
   * The compiled table for the repository at {@code root}, the agent running with {@code
   * libraries}; warnings go to {@code out}.
   */
  static CompiledTable compile(Path root, List<String> libraries, PrintStream out)
      throws IOException {
    Table table;
    try {
      table = Table.readYaml(root.resolve(Table.PATH));
    } catch (TableException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    String version =
        PhotonVisionLock.vendordepVersion(
            read(root.resolve(PhotonVisionLock.VENDORDEP), PhotonVisionLock.VENDORDEP));
    Map<String, String> hashes = new LinkedHashMap<>();
    for (Computer computer : table.computers()) {
      Optional<Settings> settings;
      Path folder = root.resolve(SettingsFiles.folder(computer.name()));
      try {
        settings = SettingsFiles.read(folder);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            SettingsFiles.folder(computer.name()) + ": " + e.getMessage(), e);
      }
      hashes.put(computer.name(), settings.map(Settings::hash).orElse(""));
    }
    RecipeHash.Result recipe = RecipeHash.of(root, libraries);
    if (recipe.hash().isEmpty()) {
      out.println(
          "No recipe hash in the compiled coprocessor table ("
              + recipe.why()
              + "): the robot will report each coprocessor's recipe as unknown.");
    }
    return new CompiledTable(table, version, recipe.hash(), hashes);
  }

  private static String read(Path file, String name) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException(name + " is missing");
    }
    return Files.readString(file, StandardCharsets.UTF_8);
  }
}
