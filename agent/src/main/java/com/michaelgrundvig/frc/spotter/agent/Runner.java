package com.michaelgrundvig.frc.spotter.agent;

import java.time.Duration;

/** What runs a pack's command: {@link Commands}, or a test's stand-in. */
interface Runner {
  /**
   * Runs a command once, on the caller's thread.
   *
   * @param folder its pack's folder on the coprocessor: where it runs, and where its {@code ./}
   *     programs are
   * @param timeout how long it may take
   * @param maxOutput the most of its output kept, in bytes
   */
  Commands.Result run(String folder, Command command, Duration timeout, int maxOutput);
}
