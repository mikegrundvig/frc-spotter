package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent's one action: a soft power-off, so a coprocessor kept powered after the robot is
 * switched off (by a battery pack, say) shuts down cleanly rather than losing power mid-write.
 * {@code systemctl poweroff} stops every service in order, and waits for each, before the power
 * goes, so the software on it saves what it has. It happens once: asking again while it's under way
 * does nothing more.
 *
 * <p>The agent runs unprivileged; a polkit rule its package installs lets its user power off, and
 * nothing else (docs/agent.md).
 */
final class Shutdown {
  /** How long powering off may take to be accepted. */
  static final Duration POWEROFF_TIMEOUT = Duration.ofSeconds(30);

  private final Host host;
  private final Executor executor;
  private final AtomicBoolean requested = new AtomicBoolean();
  private volatile String failure = "";

  Shutdown(Host host, Executor executor) {
    this.host = host;
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
    host.log("Shutdown asked for by " + from + ": powering off");
    executor.execute(this::run);
    return true;
  }

  private void run() {
    String failed;
    try {
      Commands.Output off =
          host.commands().run(List.of("systemctl", "poweroff"), POWEROFF_TIMEOUT, 16, 4096);
      failed = off.exit() == 0 ? "" : off.describe();
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
}
