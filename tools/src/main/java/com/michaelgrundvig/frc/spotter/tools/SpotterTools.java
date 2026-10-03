package com.michaelgrundvig.frc.spotter.tools;

import com.michaelgrundvig.frc.spotter.agent.PackCheck;
import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.Level;
import com.michaelgrundvig.frc.spotter.manager.Manager;
import com.michaelgrundvig.frc.spotter.manager.Recorder;
import com.michaelgrundvig.frc.spotter.manager.Robot;
import com.michaelgrundvig.frc.spotter.manager.Settings;
import com.michaelgrundvig.frc.spotter.manager.Value;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Spotter's tools, as a plain command line: {@code java -jar spotter-tools-all.jar <command>}.
 *
 * <ul>
 *   <li>{@code check <folder>...}: reads packs with the agent's own reader, on any computer, with
 *       no board: every problem as {@code file:line: what}, and a non-zero exit on any, for a build
 *       and CI.
 *   <li>{@code status <board>}: reads a board (its address, or {@code host:port}) with the robot's
 *       own manager: its problems first, then each value, then its identity and packs.
 * </ul>
 *
 * <p>What it prints is plain text on standard output, one fact a line; its usage and a failure to
 * run go to standard error. Its exit code: {@value #OK} when all is well, {@value #FOUND} for
 * problems found or a board not reached, {@value #USAGE} for a command it doesn't know.
 */
public final class SpotterTools {
  /** All is well. */
  public static final int OK = 0;

  /** Problems found, or the board not reached. */
  public static final int FOUND = 1;

  /** Not a command it knows, or its arguments are wrong. */
  public static final int USAGE = 2;

  /** How long status waits for a board, unless it's told. */
  static final Duration WAIT = Duration.ofSeconds(5);

  static final String USAGE_TEXT =
      String.join(
          "\n",
          "Usage: java -jar spotter-tools-all.jar <command>",
          "  check <folder>...         Check packs as the agent reads them: each folder a pack, or",
          "                            a folder of packs. Exits 1 on any problem.",
          "  status <board> [--wait=5s]  A board's problems, values, identity and packs: its",
          "                            address, or host:port (5808 unless given). Exits 1 if it",
          "                            isn't reached.");

  private SpotterTools() {}

  /** Runs a command; its exit code is the process's. */
  public static void main(String[] args) {
    PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
    System.exit(run(List.of(args), out, err));
  }

  /** Runs a command, printing to these: its exit code. */
  static int run(List<String> args, PrintStream out, PrintStream err) {
    if (args.isEmpty()) {
      err.println(USAGE_TEXT);
      return USAGE;
    }
    List<String> rest = args.subList(1, args.size());
    switch (args.get(0)) {
      case "check":
        return check(rest, out, err);
      case "status":
        return status(rest, out, err);
      case "help":
      case "--help":
        out.println(USAGE_TEXT);
        return OK;
      default:
        err.println("spotter-tools: no command " + args.get(0));
        err.println(USAGE_TEXT);
        return USAGE;
    }
  }

  // ---- check ----

  private static int check(List<String> folders, PrintStream out, PrintStream err) {
    if (folders.isEmpty()) {
      err.println("spotter-tools: check needs a folder: a pack, or a folder of packs");
      return USAGE;
    }
    List<Path> paths = new ArrayList<>();
    for (String folder : folders) {
      paths.add(Path.of(folder));
    }
    PackCheck.Report report = PackCheck.check(paths);
    for (String problem : report.problems()) {
      out.println(problem);
    }
    for (String note : report.notes()) {
      out.println("note: " + note);
    }
    out.println(
        report.packs()
            + (report.packs() == 1 ? " pack" : " packs")
            + " checked: "
            + (report.passed()
                ? "no problems"
                : report.problems().size()
                    + (report.problems().size() == 1 ? " problem" : " problems")));
    return report.passed() ? OK : FOUND;
  }

  // ---- status ----

  private static int status(List<String> args, PrintStream out, PrintStream err) {
    @Nullable String address = null;
    Duration wait = WAIT;
    for (String arg : args) {
      if (arg.startsWith("--wait=")) {
        Duration given = duration(arg.substring("--wait=".length()));
        if (given == null) {
          err.println("spotter-tools: --wait is a duration, such as 5s, not " + arg.substring(7));
          return USAGE;
        }
        wait = given;
      } else if (address == null) {
        address = arg;
      } else {
        err.println("spotter-tools: status reads one board, not " + arg + " too");
        return USAGE;
      }
    }
    if (address == null || address.isBlank()) {
      err.println("spotter-tools: status needs a board: its address, or host:port");
      return USAGE;
    }
    Manager manager;
    try {
      manager =
          new Manager(
              new Robot(() -> false, () -> false, System::nanoTime),
              List.of(address),
              Settings.DEFAULTS,
              Recorder.NONE);
    } catch (IllegalArgumentException e) {
      err.println("spotter-tools: " + e.getMessage());
      return USAGE;
    }
    try (manager) {
      manager.start();
      Board board = manager.boards().get(0);
      long until = System.nanoTime() + wait.toNanos();
      while (!read(board) && System.nanoTime() < until) {
        manager.update();
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return FOUND;
        }
      }
      manager.update();
      if (board.description().getRevision() == 0) {
        out.println(
            address
                + ": not reached"
                + (board.why().isEmpty() ? "" : ": " + board.why())
                + " (waited "
                + wait.toMillis() / 1000.0
                + " s)");
        return FOUND;
      }
      print(board, out);
      return OK;
    }
  }

  /** Whether a board has described itself, and sent each of its values. */
  private static boolean read(Board board) {
    if (board.description().getRevision() == 0) {
      return false;
    }
    for (Value value : board.values()) {
      if (value.nanos() == 0) {
        return false;
      }
    }
    return true;
  }

  /** A board, as status prints it. */
  static void print(Board board, PrintStream out) {
    Spotter.Description description = board.description();
    out.println(
        board.name()
            + " ("
            + board.address()
            + "): agent "
            + description.getAgentVersion()
            + ", protocol "
            + board.protocol());
    List<String> problems = board.problems();
    out.println(problems.isEmpty() ? "Problems: none" : "Problems: " + problems.size());
    for (String problem : problems) {
      out.println("  " + problem);
    }
    out.println("Values: " + board.values().size());
    int width = 0;
    for (Value value : board.values()) {
      width = Math.max(width, value.id().length());
    }
    for (Value value : board.values()) {
      out.println("  " + pad(value.id(), width) + "  " + value(value));
    }
    Spotter.Identity identity = description.getIdentity();
    out.println("Identity:");
    out.println("  hostname   " + identity.getHostname());
    List<String> addresses = new ArrayList<>();
    for (int i = 0; i < identity.getAddresses().length(); i++) {
      addresses.add(identity.getAddresses().get(i));
    }
    out.println("  addresses  " + String.join(", ", addresses));
    out.println("  mac        " + identity.getMac());
    out.println("  boot       " + identity.getBootId());
    for (Spotter.Identity.OsReleaseEntry entry : identity.getOsRelease()) {
      if (entry.getKey().equals("PRETTY_NAME")) {
        out.println("  os         " + entry.getValue());
      }
    }
    out.println("Packs: " + description.getPacks().length());
    for (Spotter.Pack pack : description.getPacks()) {
      out.println(
          "  "
              + pack.getName()
              + (pack.getVersion().isEmpty() ? "" : " " + pack.getVersion())
              + "  "
              + (pack.getPushed() ? "pushed" : "installed")
              + "  "
              + pack.getFolder());
    }
  }

  /**
   * A value as status prints it: {@code value unit (label)}, or {@code unavailable: reason}; its
   * level after it, unless it's ok.
   */
  static String value(Value value) {
    String shown;
    if (!value.available()) {
      shown = "unavailable: " + value.unavailable();
    } else if (value.type() == Spotter.FieldType.FIELD_TYPE_NUMBER) {
      shown = number(value.number()) + (value.unit().isEmpty() ? "" : " " + value.unit());
    } else if (value.type() == Spotter.FieldType.FIELD_TYPE_BOOLEAN) {
      shown = Boolean.toString(value.flag());
    } else if (value.type() == Spotter.FieldType.FIELD_TYPE_STATUS) {
      shown = value.status().name().toLowerCase(java.util.Locale.ROOT) + ": " + value.text();
    } else {
      shown = value.text();
    }
    String label = value.label().equals(value.id()) ? "" : "  (" + value.label() + ")";
    String level =
        value.level() == Level.WARNING || value.level() == Level.FAILING
            ? "  " + value.level().name().toLowerCase(java.util.Locale.ROOT) + ": " + value.reason()
            : "";
    return shown + label + level;
  }

  private static String number(double number) {
    return number == Math.rint(number) && Math.abs(number) < 1e15
        ? Long.toString((long) number)
        : Double.toString(number);
  }

  private static String pad(String text, int width) {
    StringBuilder padded = new StringBuilder(text);
    while (padded.length() < width) {
      padded.append(' ');
    }
    return padded.toString();
  }

  /** A duration as {@code 500ms}, {@code 5s} or {@code 1m}; null when it isn't one. */
  static @Nullable Duration duration(String text) {
    try {
      if (text.endsWith("ms")) {
        return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
      }
      if (text.endsWith("s")) {
        return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
      }
      if (text.endsWith("m")) {
        return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
      }
    } catch (NumberFormatException e) {
      // Not a number: not a duration.
    }
    return null;
  }
}
