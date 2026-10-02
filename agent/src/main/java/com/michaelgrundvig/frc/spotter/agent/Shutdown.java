package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.probes.Step;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The agent's one action: a soft power-off, so a coprocessor kept powered after the robot is
 * switched off (by a battery pack, say) shuts down cleanly rather than losing power mid-write. It
 * runs the steps the computer's packs define first (a pack stops the software it watches, so its
 * settings are saved), each within its own time, then powers the computer off, whether the steps
 * succeeded or not. It happens once: asking again while it's under way does nothing more.
 *
 * <p>The agent runs unprivileged; a polkit rule its package installs lets its user power off, and
 * each pack's rule lets it do what that pack's steps need (docs/agent.md).
 */
final class Shutdown {
  /** How long powering off may take to be accepted. */
  static final Duration POWEROFF_TIMEOUT = Duration.ofSeconds(30);

  private final Host host;
  private final List<Step> steps;
  private final Executor executor;
  private final AtomicBoolean requested = new AtomicBoolean();
  private volatile String failure = "";

  Shutdown(Host host, List<Step> steps, Executor executor) {
    this.host = host;
    this.steps = List.copyOf(steps);
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
    host.log(
        "Shutdown asked for by "
            + from
            + ": "
            + (steps.isEmpty()
                ? ""
                : "running "
                    + String.join(", ", steps.stream().map(Step::name).toList())
                    + ", then ")
            + "powering off");
    executor.execute(this::run);
    return true;
  }

  private void run() {
    for (Step step : steps) {
      Duration timeout = Duration.ofMillis(Math.round(step.timeoutSeconds() * 1000));
      try {
        Commands.Output output = host.commands().run(step.argv(), timeout, 16, 4096);
        if (output.exit() != 0) {
          host.log(
              "Step " + step.name() + " failed (" + output.describe() + "); powering off anyway");
        }
      } catch (IOException e) {
        host.log("Step " + step.name() + " failed (" + e.getMessage() + "); powering off anyway");
      }
    }
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
