package com.michaelgrundvig.frc.spotter.agent;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * A log that writes at most one line per period: one for the first event, then one per period for
 * those since, counted, so a flood of refused requests can't flood the journal.
 */
final class RateLimitedLog {
  private final Consumer<String> log;
  private final LongSupplier micros;
  private final long periodMicros;
  private long lastMicros = Long.MIN_VALUE;
  private int held;

  RateLimitedLog(Consumer<String> log, LongSupplier micros, long periodMicros) {
    this.log = log;
    this.micros = micros;
    this.periodMicros = periodMicros;
  }

  /** Logs the message, unless one was logged within the period; then counts it for the next. */
  synchronized void log(String message) {
    long now = micros.getAsLong();
    if (lastMicros != Long.MIN_VALUE && now - lastMicros < periodMicros) {
      held++;
      return;
    }
    log.accept(held == 0 ? message : message + " (and " + held + " more like it before)");
    held = 0;
    lastMicros = now;
  }
}
