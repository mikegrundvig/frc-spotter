package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import org.jspecify.annotations.Nullable;

/**
 * This boot, and what booted.
 *
 * @param bootId this boot's ID; it changes every boot
 * @param uptimeSeconds seconds since the kernel started
 * @param monotonicMicros the agent's monotonic clock when it answered, in microseconds since boot:
 *     the clock the journal's {@code monotonicMicros} use, so a log can place journal entries on
 *     its own timeline
 * @param lastShutdownClean whether the boot before this one shut down cleanly (its journal ended
 *     with journald stopping); null when that boot left no journal
 * @param rootDevice the device the root filesystem is on, such as {@code /dev/nvme0n1p2}; an SD
 *     card left in shows up here
 * @param rootReadOnly whether the root filesystem is mounted read-only
 */
public record Boot(
    String bootId,
    double uptimeSeconds,
    long monotonicMicros,
    @Nullable Boolean lastShutdownClean,
    String rootDevice,
    boolean rootReadOnly) {

  /** The boot as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("bootId", bootId)
        .put("uptimeSeconds", uptimeSeconds)
        .put("monotonicMicros", monotonicMicros)
        .putOptional("lastShutdownClean", lastShutdownClean)
        .put("rootDevice", rootDevice)
        .put("rootReadOnly", rootReadOnly)
        .build();
  }

  /** A boot from its JSON. */
  public static Boot fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("boot");
    return new Boot(
        o.string("bootId", ""),
        o.number("uptimeSeconds", 0),
        o.integer("monotonicMicros", 0L),
        o.optionalBool("lastShutdownClean"),
        o.string("rootDevice", ""),
        o.bool("rootReadOnly", false));
  }
}
