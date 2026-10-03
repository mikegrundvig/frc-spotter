package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Packs checked off any board, by the agent's own reader: what spotter-tools' {@code check} runs,
 * on any computer, so a pack's mistakes show where its author is (a laptop, the robot program's
 * build), not on a board. Every problem the agent would list, each with its file and line, and the
 * bounds a board holds packs to; and mistakes the agent takes, but a pack shouldn't have:
 *
 * <ul>
 *   <li>a program of the pack's own ({@code ./name}) that isn't there, or has neither an execute
 *       bit nor a {@code #!} first line, so it won't run once pushed;
 *   <li>a {@code .} in an id or a field's name, which makes values' ids read two ways;
 *   <li>{@code equals} or {@code notEquals} on a {@code status} field, which is never applied.
 * </ul>
 *
 * And notes, which are no problem: a collector's field that's its command's own named part (an http
 * collector's {@code status} is the HTTP status, not a key of its output). The trust check (root's,
 * and written by root alone) is skipped: it means something only on a board.
 */
public final class PackCheck {
  private PackCheck() {}

  /**
   * What a check found.
   *
   * @param packs how many packs it read
   * @param problems every problem, each its file and line first: none when the packs pass
   * @param notes what's worth knowing, and no problem
   */
  public record Report(int packs, List<String> problems, List<String> notes) {
    public Report {
      problems = List.copyOf(problems);
      notes = List.copyOf(notes);
    }

    /** Whether the packs pass: no problem. */
    public boolean passed() {
      return problems.isEmpty();
    }
  }

  /** What's said of the trust check, which a check off a board skips. */
  public static final String TRUST_SKIPPED =
      "the trust check (root's, and written by root alone) is skipped: it means something only on"
          + " a board";

  /**
   * Checks packs: each folder a pack ({@code <name>/pack.yaml}), or a folder of them, as the robot
   * program's packs folder is; all of them together held to a board's bounds.
   */
  public static Report check(List<Path> folders) {
    List<String> problems = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    List<Pack> read = new ArrayList<>();
    for (Path folder : folders) {
      if (Files.isRegularFile(folder.resolve(Pack.FILE))) {
        pack(folder, read, problems, notes);
      } else if (!Files.isDirectory(folder)) {
        problems.add(show(folder) + ": no such folder");
      } else {
        for (Path each : entries(folder, problems)) {
          if (!Files.isDirectory(each)) {
            problems.add(
                show(each) + ": not a pack (a pack is a folder, <name>/pack.yaml), ignored");
          } else if (!Files.isRegularFile(each.resolve(Pack.FILE))) {
            problems.add(show(each) + ": has no " + Pack.FILE + ", ignored");
          } else {
            pack(each, read, problems, notes);
          }
        }
      }
    }
    // As one board would hold them all: its bounds on packs, values, actions, declarations.
    Packs.bounded(read, problems);
    notes.add(TRUST_SKIPPED);
    return new Report(read.size(), problems, notes);
  }

  private static List<Path> entries(Path folder, List<String> problems) {
    try (Stream<Path> each = Files.list(folder)) {
      return each.sorted().toList();
    } catch (IOException e) {
      problems.add(show(folder) + ": can't be read: " + e.getMessage());
      return List.of();
    }
  }

  private static void pack(
      Path folder, List<Pack> read, List<String> problems, List<String> notes) {
    String path = show(folder);
    String yaml;
    try (InputStream in = Files.newInputStream(folder.resolve(Pack.FILE))) {
      byte[] bytes = in.readNBytes(Host.MAX_FILE + 1);
      if (bytes.length > Host.MAX_FILE) {
        problems.add(
            path + "/" + Pack.FILE + ": more than the " + Host.MAX_FILE / 1024 + " KiB read");
        return;
      }
      yaml = new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException e) {
      problems.add(path + "/" + Pack.FILE + ": can't be read: " + e.getMessage());
      return;
    }
    PackReader.Checks checks =
        new PackReader.Checks() {
          @Override
          public @Nullable String program(String relative) {
            return PackCheck.program(folder, relative);
          }

          @Override
          public void note(String note) {
            notes.add(note);
          }
        };
    try {
      read.add(PackReader.read(yaml, path, true, checks));
    } catch (PackException e) {
      problems.addAll(e.problems());
    } catch (RuntimeException | StackOverflowError e) {
      problems.add(path + "/" + Pack.FILE + ": couldn't be read (" + e + ")");
    }
  }

  /**
   * Why a pack's own program ({@code ./name}) won't run once pushed: it isn't there, or it has
   * neither an execute bit nor a {@code #!} first line, which a push carries as executable; null
   * when it will.
   */
  static @Nullable String program(Path folder, String relative) {
    Path program = folder.resolve(relative);
    if (!Files.isRegularFile(program)) {
      return "./" + relative + " isn't in the pack's folder";
    }
    try {
      if (!PackHash.executable(program)) {
        return "./"
            + relative
            + " has neither an execute bit nor a #! first line, so it won't run once pushed:"
            + " chmod +x it, start it with #!, or run: [sh, ./"
            + relative
            + "]";
      }
    } catch (IOException e) {
      return "./" + relative + " can't be read: " + e.getMessage();
    }
    return null;
  }

  /** A path as a problem names it: with {@code /}, on any computer. */
  private static String show(Path path) {
    return path.toString().replace('\\', '/');
  }
}
