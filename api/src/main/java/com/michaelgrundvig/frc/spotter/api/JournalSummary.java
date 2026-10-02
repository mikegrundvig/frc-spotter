package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This boot's journal, counted: the kinds of trouble a vision computer has, and the latest few.
 *
 * @param counts how many entries of each kind this boot, by {@link #CATEGORIES}
 * @param latest the latest few of them, oldest first
 */
public record JournalSummary(Map<String, Integer> counts, List<JournalEntry> latest) {
  /**
   * The kinds counted: USB (disconnects, resets, errors), UVC (the camera driver), filesystem
   * (ext4, I/O, NVMe errors), OOM (the out-of-memory killer), thermal (overheating, throttling),
   * and error (anything else at priority 3 or worse).
   */
  public static final List<String> CATEGORIES =
      List.of("usb", "uvc", "filesystem", "oom", "thermal", "error");

  public JournalSummary {
    counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    latest = List.copyOf(latest);
  }

  /** Nothing counted. */
  public static final JournalSummary EMPTY = new JournalSummary(Map.of(), List.of());

  /** How many entries of this kind this boot. */
  public int count(String category) {
    return counts.getOrDefault(category, 0);
  }

  /** The summary as JSON. */
  public JsonValue.Obj toJson() {
    JsonValue.Obj.Builder countsJson = JsonValue.Obj.builder();
    counts.forEach((category, count) -> countsJson.put(category, (long) count));
    return JsonValue.Obj.builder()
        .put("counts", countsJson.build())
        .put("latest", JsonValue.array(latest, JournalEntry::toJson))
        .build();
  }

  /** A summary from its JSON. */
  public static JournalSummary fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("journal summary");
    Map<String, Integer> counts = new LinkedHashMap<>();
    JsonValue.Obj countsJson = o.objectOrEmpty("counts");
    for (String category : countsJson.members().keySet()) {
      counts.put(category, countsJson.integer(category, 0));
    }
    return new JournalSummary(counts, o.list("latest", JournalEntry::fromJson));
  }
}
