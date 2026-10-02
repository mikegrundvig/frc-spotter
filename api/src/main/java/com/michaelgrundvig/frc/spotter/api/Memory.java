package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * The computer's memory, from {@code /proc/meminfo}: what's left before the kernel starts killing
 * processes is what matters to a vision program, so "available" (what can be had without swapping,
 * caches included), not "free".
 *
 * @param totalMb all of it, in MiB; 0 when unknown
 * @param availableMb what's available, in MiB
 */
public record Memory(long totalMb, long availableMb) {
  /** Not known: what the agent reports when it can't read it. */
  public static final Memory UNKNOWN = new Memory(0, 0);

  /** What's available, in percent of all of it; NaN when unknown. */
  public double availablePercent() {
    return totalMb > 0 ? 100.0 * availableMb / totalMb : Double.NaN;
  }

  /** The memory as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder().put("totalMb", totalMb).put("availableMb", availableMb).build();
  }

  /** Memory from its JSON. */
  public static Memory fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("memory");
    return new Memory(o.integer("totalMb", 0L), o.integer("availableMb", 0L));
  }
}
