package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Runs a command and reads what it prints, within bounds: the agent's one way to run anything
 * ({@code journalctl}, {@code systemctl}). Commands are fixed in the agent's code; nothing a
 * request says is run, only passed as a validated argument. Injected, so tests answer with canned
 * output.
 */
interface Commands {
  /**
   * Runs a command, reading at most {@code maxLines} lines and {@code maxBytes} characters of what
   * it prints (it's stopped once either is reached), for at most {@code timeout}.
   *
   * @throws IOException when the command can't be started
   */
  Output run(List<String> command, Duration timeout, int maxLines, int maxBytes) throws IOException;

  /**
   * What a command printed.
   *
   * @param exit its exit status; -1 when it was stopped early (a bound reached, or timed out)
   * @param lines the lines it printed, without their line endings
   * @param truncated whether it had more to print than was read
   * @param timedOut whether it ran out of time
   */
  record Output(int exit, List<String> lines, boolean truncated, boolean timedOut) {
    public Output {
      lines = List.copyOf(lines);
    }

    /** Whether it finished on its own and succeeded, or was stopped only for printing enough. */
    boolean ok() {
      return !timedOut && (exit == 0 || truncated);
    }
  }
}
