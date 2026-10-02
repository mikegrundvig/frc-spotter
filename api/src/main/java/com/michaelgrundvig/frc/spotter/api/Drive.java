package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * The NVMe drive's health, as its own SMART log reports it.
 *
 * @param device the drive, such as {@code /dev/nvme0}
 * @param celsius its temperature, °C
 * @param percentUsed how much of its rated endurance it has used, as the drive estimates: 100 is
 *     its rated life, and it can go past
 * @param unsafeShutdowns how many times it has lost power without being told first, over its life:
 *     each power cut counts one
 * @param powerCycles how many times it has been powered on, over its life
 * @param powerOnHours hours powered on, over its life
 * @param mediaErrors unrecovered data errors, over its life
 * @param criticalWarning the drive's critical-warning bits: 0 when all is well
 */
public record Drive(
    String device,
    double celsius,
    int percentUsed,
    long unsafeShutdowns,
    long powerCycles,
    long powerOnHours,
    long mediaErrors,
    int criticalWarning) {

  /** The drive as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("device", device)
        .put("celsius", celsius)
        .put("percentUsed", percentUsed)
        .put("unsafeShutdowns", unsafeShutdowns)
        .put("powerCycles", powerCycles)
        .put("powerOnHours", powerOnHours)
        .put("mediaErrors", mediaErrors)
        .put("criticalWarning", criticalWarning)
        .build();
  }

  /** A drive from its JSON. */
  public static Drive fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("drive");
    return new Drive(
        o.string("device", ""),
        o.number("celsius", Double.NaN),
        o.integer("percentUsed", 0),
        o.integer("unsafeShutdowns", 0L),
        o.integer("powerCycles", 0L),
        o.integer("powerOnHours", 0L),
        o.integer("mediaErrors", 0L),
        o.integer("criticalWarning", 0));
  }
}
