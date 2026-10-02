package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * One filesystem's space: the root, and {@code /data} where the image keeps what's written while it
 * runs (logs, settings). A full {@code /data} fails saves silently, so it's watched.
 *
 * @param mount where it's mounted, such as {@code /data}
 * @param totalMb its size, in MiB
 * @param freeMb what an unprivileged program can still write, in MiB
 * @param readOnly whether it's mounted read-only
 */
public record Disk(String mount, long totalMb, long freeMb, boolean readOnly) {
  /** What's free, in percent of its size; NaN when its size is unknown. */
  public double freePercent() {
    return totalMb > 0 ? 100.0 * freeMb / totalMb : Double.NaN;
  }

  /** The disk as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("mount", mount)
        .put("totalMb", totalMb)
        .put("freeMb", freeMb)
        .put("readOnly", readOnly)
        .build();
  }

  /** A disk from its JSON. */
  public static Disk fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("disk");
    return new Disk(
        o.string("mount", ""),
        o.integer("totalMb", 0L),
        o.integer("freeMb", 0L),
        o.bool("readOnly", false));
  }
}
