package com.michaelgrundvig.frc.spotter.manager;

/**
 * An agent's monotonic clock mapped onto the robot's: the offset between them is the least seen
 * between when an event says it was sent and when it was heard (its trip can only add), over the
 * last 10 to 20 s of the robot's clock, so the two clocks' drift is followed. Each connection
 * starts afresh: an agent that restarted may be on another boot.
 */
final class ClockMap {
  /** How long each window of the least offset lasts, on the robot's clock. */
  static final long WINDOW_NANOS = 10_000_000_000L;

  private boolean any;
  private long least;
  private long previous;
  private long windowStart;

  /** Forgets the agent's clock: a new connection. */
  void reset() {
    any = false;
  }

  /** An event the agent sent at {@code agentNanos}, heard at {@code robotNanos}. */
  void heard(long agentNanos, long robotNanos) {
    long offset = robotNanos - agentNanos;
    if (!any) {
      any = true;
      least = offset;
      previous = offset;
      windowStart = robotNanos;
    } else if (robotNanos - windowStart >= WINDOW_NANOS) {
      previous = least;
      least = offset;
      windowStart = robotNanos;
    } else if (offset < least) {
      least = offset;
    }
  }

  /** A time on the agent's clock, on the robot's. */
  long robot(long agentNanos) {
    return agentNanos + Math.min(least, previous);
  }
}
