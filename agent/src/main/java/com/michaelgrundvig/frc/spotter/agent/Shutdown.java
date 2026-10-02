package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent's one action: a soft power-off, so a coprocessor kept powered after the robot is
 * switched off (by a battery pack, say) shuts down cleanly rather than losing power mid-write. It
 * stops PhotonVision first, so its settings are saved, then powers the computer off. It happens
 * once: asking again while it's under way does nothing more.
 *
 * <p>The agent runs unprivileged; a polkit rule on the image lets its user stop PhotonVision's unit
 * and power off, and nothing else (coprocessor/README.md).
 */
final class Shutdown {
  /** How long PhotonVision may take to stop before the computer powers off anyway. */
  static final Duration STOP_TIMEOUT = Duration.ofSeconds(120);

  private final Host host;
  private final String unit;
  private final Executor executor;
  private final AtomicBoolean requested = new AtomicBoolean();
  private volatile String failure = "";

  Shutdown(Host host, String unit, Executor executor) {
    this.host = host;
    this.unit = unit;
    this.executor = executor;
  }

  /** Whether a shutdown is under way. */
  boolean requested() {
    return requested.get();
  }

  /** Why the last shutdown failed to power off, or empty: asking again tries again. */
  String failure() {
    return failure;
  }

  /**
   * Starts the shutdown, unless it's under way: whether this request started it. Logs who asked
   * before anything is done.
   */
  boolean request(String from) {
    if (!requested.compareAndSet(false, true)) {
      host.log("Shutdown asked for again by " + from + ": already shutting down");
      return false;
    }
    failure = "";
    host.log("Shutdown asked for by " + from + ": stopping " + unit + ", then powering off");
    executor.execute(this::run);
    return true;
  }

  private void run() {
    try {
      Commands.Output stopped =
          host.commands().run(List.of("systemctl", "stop", unit), STOP_TIMEOUT, 16, 4096);
      if (stopped.exit() != 0) {
        host.log("Stopping " + unit + " failed (" + describe(stopped) + "); powering off anyway");
      }
    } catch (IOException e) {
      host.log("Stopping " + unit + " failed (" + e.getMessage() + "); powering off anyway");
    }
    String failed;
    try {
      Commands.Output off =
          host.commands().run(List.of("systemctl", "poweroff"), Duration.ofSeconds(30), 16, 4096);
      failed = off.exit() == 0 ? "" : describe(off);
    } catch (IOException e) {
      failed = String.valueOf(e.getMessage());
    }
    if (!failed.isEmpty()) {
      // Not shutting down after all: say so, and let the next request try again.
      host.log("Powering off failed (" + failed + "); a shutdown may be asked for again");
      failure = "powering off failed (" + failed + ")";
      requested.set(false);
    }
  }

  private static String describe(Commands.Output output) {
    return output.timedOut() ? "timed out" : "exit " + output.exit();
  }
}
