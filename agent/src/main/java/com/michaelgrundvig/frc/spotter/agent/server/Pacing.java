package com.michaelgrundvig.frc.spotter.agent.server;

/**
 * When a stream sends, as a function of what's happened and the time, and nothing else: changed
 * values wait {@link Stream#BUNDLE_NANOS} for others to go with them; every value goes again each
 * {@code complete}; and a heartbeat goes when nothing else was sent for the {@code heartbeat}
 * interval. Every method takes the time, on the stream's clock, so it's tested without waiting. The
 * stream holds it under its lock.
 */
final class Pacing {
  private final long heartbeat;
  private final long complete;
  private long lastSent;
  private long nextComplete;
  private boolean changed;
  private long changedSince;

  /**
   * @param heartbeat the longest a stream stays silent, in nanoseconds
   * @param complete how often every value is sent again, in nanoseconds
   * @param now the time it connected, when every value was sent
   */
  Pacing(long heartbeat, long complete, long now) {
    this.heartbeat = heartbeat;
    this.complete = complete;
    this.lastSent = now;
    this.nextComplete = now + complete;
  }

  /** A value changed: it waits for others, from the first change since the last send of them. */
  void changed(long now) {
    if (!changed) {
      changed = true;
      changedSince = now;
    }
  }

  /** Whether changed values are due: the first changed at least {@link Stream#BUNDLE_NANOS} ago. */
  boolean changesDue(long now) {
    return changed && now - changedSince >= Stream.BUNDLE_NANOS;
  }

  /** The changed values were taken to send: none is waiting until the next change. */
  void changesTaken() {
    changed = false;
  }

  /** Whether every value is due again. */
  boolean completeDue(long now) {
    return now - nextComplete >= 0;
  }

  /** Every value was sent: the next time is {@code complete} from now. */
  void completeSent(long now) {
    nextComplete = now + complete;
  }

  /** Whether a heartbeat is due: nothing was sent for the heartbeat interval. */
  boolean heartbeatDue(long now) {
    return now - lastSent >= heartbeat;
  }

  /** Something was sent. */
  void sent(long now) {
    lastSent = now;
  }

  /** When the next thing is due, if nothing happens before then. */
  long wake() {
    long wake = Math.min(lastSent + heartbeat, nextComplete);
    return changed ? Math.min(wake, changedSince + Stream.BUNDLE_NANOS) : wake;
  }
}
