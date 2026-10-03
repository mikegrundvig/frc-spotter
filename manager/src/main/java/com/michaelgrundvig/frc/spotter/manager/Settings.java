package com.michaelgrundvig.frc.spotter.manager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The manager's settings, each with a default ({@link #DEFAULTS}); robot code changes those it
 * needs: {@code Settings.DEFAULTS.withMissing(Duration.ofSeconds(2))}.
 *
 * @param heartbeat how often each agent says it's there when nothing else happens: asked for on
 *     connect, kept by the agent between 50 ms and 5 s (default 250 ms)
 * @param missing how long a board may be silent before it counts as missing, on the robot's clock;
 *     longer than the heartbeat (default 1 s). A connection that's silent this long is also
 *     dropped, and made again.
 * @param backoff the longest wait between attempts to reach a board: the first comes 250 ms after a
 *     failure, and each next one waits twice as long, up to this; a stream that opened starts it
 *     over (default 5 s)
 * @param limits robot code's limits for fields, by id ({@code pack.collector.field}), in place of
 *     their packs' (default none)
 */
public record Settings(
    Duration heartbeat, Duration missing, Duration backoff, Map<String, Limits> limits) {
  /** Every setting's default. */
  public static final Settings DEFAULTS =
      new Settings(Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(5), Map.of());

  public Settings {
    if (heartbeat.isNegative() || heartbeat.isZero()) {
      throw new IllegalArgumentException("the heartbeat must be positive: " + heartbeat);
    }
    if (missing.compareTo(heartbeat) <= 0) {
      throw new IllegalArgumentException(
          "a board counts as missing only after longer than its heartbeat ("
              + heartbeat
              + "): "
              + missing);
    }
    if (backoff.isNegative() || backoff.isZero()) {
      throw new IllegalArgumentException("the backoff must be positive: " + backoff);
    }
    limits = Map.copyOf(limits);
  }

  /** These settings with another heartbeat interval. */
  public Settings withHeartbeat(Duration heartbeat) {
    return new Settings(heartbeat, missing, backoff, limits);
  }

  /** These settings with another missing threshold. */
  public Settings withMissing(Duration missing) {
    return new Settings(heartbeat, missing, backoff, limits);
  }

  /** These settings with another longest backoff. */
  public Settings withBackoff(Duration backoff) {
    return new Settings(heartbeat, missing, backoff, limits);
  }

  /** These settings with one field's limits overridden, by its id. */
  public Settings withLimits(String id, Limits override) {
    Map<String, Limits> all = new HashMap<>(limits);
    all.put(id, override);
    return new Settings(heartbeat, missing, backoff, all);
  }
}
