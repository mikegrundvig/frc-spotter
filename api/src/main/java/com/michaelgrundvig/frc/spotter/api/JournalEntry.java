package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * One journal entry.
 *
 * @param cursor where it is in the journal: pass it back as {@code cursor} for the entries after
 * @param realtimeMicros when it was logged by the board's wall clock, microseconds since 1970; the
 *     boards keep no reliable wall time, so prefer {@code monotonicMicros}
 * @param monotonicMicros when it was logged, microseconds since its boot
 * @param bootId the boot it was logged in
 * @param priority its syslog priority: 0 emergency to 7 debug (3 is an error, 4 a warning)
 * @param unit the systemd unit that logged it; empty for the kernel
 * @param identifier who logged it: {@code kernel}, {@code java}, ...
 * @param message the message, cut to {@link AgentApi#MAX_MESSAGE} characters
 * @param category what it's about, when it's one of the kinds counted ({@link
 *     JournalSummary#CATEGORIES}); empty otherwise
 */
public record JournalEntry(
    String cursor,
    long realtimeMicros,
    long monotonicMicros,
    String bootId,
    int priority,
    String unit,
    String identifier,
    String message,
    String category) {

  /** The entry as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("cursor", cursor)
        .put("realtimeMicros", realtimeMicros)
        .put("monotonicMicros", monotonicMicros)
        .put("bootId", bootId)
        .put("priority", priority)
        .put("unit", unit)
        .put("identifier", identifier)
        .put("message", message)
        .put("category", category)
        .build();
  }

  /** An entry from its JSON. */
  public static JournalEntry fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("journal entry");
    return new JournalEntry(
        o.string("cursor", ""),
        o.integer("realtimeMicros", 0L),
        o.integer("monotonicMicros", 0L),
        o.string("bootId", ""),
        o.integer("priority", 6),
        o.string("unit", ""),
        o.string("identifier", ""),
        o.string("message", ""),
        o.string("category", ""));
  }

  /** An entry from JSON text. */
  public static JournalEntry parse(String json) {
    return fromJson(Json.parse(json));
  }
}
