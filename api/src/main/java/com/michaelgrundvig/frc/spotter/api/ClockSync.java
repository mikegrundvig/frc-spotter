package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import org.jspecify.annotations.Nullable;

/**
 * Whether the computer's clock is synchronized, and how far it is from its time source, whatever
 * keeps it: a clock offset is a fault the robot's localization must be able to see.
 *
 * @param synced whether the kernel counts the clock synchronized ({@code timedatectl}'s
 *     NTPSynchronized, which any NTP daemon sets); null when unknown
 * @param source the daemon the offset is from: {@code chrony} or {@code systemd-timesyncd}; empty
 *     when neither said
 * @param offsetMillis how far the clock is behind its time source, in milliseconds (negative when
 *     ahead), as the daemon last measured it; NaN when unknown
 */
public record ClockSync(@Nullable Boolean synced, String source, double offsetMillis) {
  /** Nothing known. */
  public static final ClockSync UNKNOWN = new ClockSync(null, "", Double.NaN);

  /** The clock as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .putOptional("synced", synced)
        .put("source", source)
        .put("offsetMillis", offsetMillis)
        .build();
  }

  /** A clock from its JSON. */
  public static ClockSync fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("clock");
    return new ClockSync(
        o.optionalBool("synced"), o.string("source", ""), o.number("offsetMillis", Double.NaN));
  }
}
