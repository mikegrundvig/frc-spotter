package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs a command and reads what it prints, within bounds: the agent's one way to run anything
 * ({@code journalctl}, {@code systemctl}, a pack's probes, steps, and downloads). Commands are
 * fixed in the agent's code or in the computer's packs; nothing a request says is run, only passed
 * as a validated argument. Injected, so tests answer with canned output.
 */
interface Commands {
  /**
   * The environment variable every command runs with: the Java the agent runs on, so a pack's
   * helper written in Java runs on it too (the package's own runtime, or the system's), and no pack
   * names a path to one.
   */
  String JAVA_VARIABLE = "FRC_AGENT_JAVA";

  /**
   * Runs a command, reading at most {@code maxLines} lines and {@code maxBytes} characters of what
   * it prints (it's stopped once either is reached), for at most {@code timeout}.
   *
   * @throws IOException when the command can't be started
   */
  Output run(List<String> command, Duration timeout, int maxLines, int maxBytes) throws IOException;

  /**
   * Runs a command, writing what it prints to {@code file}, at most {@code maxBytes} (it's stopped
   * once more arrives), for at most {@code timeout}: a download, sent only once it has finished.
   *
   * @return its exit status, and whether it was stopped; its lines are empty
   * @throws IOException when the command can't be started, or the file can't be written
   */
  Output toFile(List<String> command, Duration timeout, long maxBytes, Path file)
      throws IOException;

  /**
   * What a command printed.
   *
   * @param exit its exit status; -1 when it was stopped early (a bound reached, or timed out)
   * @param lines the lines it printed, without their line endings
   * @param truncated whether it had more to print than was read
   * @param timedOut whether it ran out of time
   * @param errors the first line it wrote to standard error, at most {@link #MAX_ERRORS}
   *     characters: why it failed, as a pack's helper says it; empty when it wrote none
   */
  record Output(int exit, List<String> lines, boolean truncated, boolean timedOut, String errors) {
    /** The most of a command's standard error kept. */
    static final int MAX_ERRORS = 200;

    public Output {
      lines = List.copyOf(lines);
    }

    Output(int exit, List<String> lines, boolean truncated, boolean timedOut) {
      this(exit, lines, truncated, timedOut, "");
    }

    /** How it went, for a log line or a probe's detail: its exit, and why when it said. */
    String describe() {
      String how = timedOut ? "timed out" : "exit " + exit;
      return errors.isEmpty() ? how : how + ": " + errors;
    }

    /** Whether it finished on its own and succeeded, or was stopped only for printing enough. */
    boolean ok() {
      return !timedOut && (exit == 0 || truncated);
    }
  }
}
