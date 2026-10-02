package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * A page of the journal: {@code GET /v1/journal}.
 *
 * @param entries the entries, oldest first
 * @param cursor pass it back as {@code cursor} for the entries after these; when there were none,
 *     the cursor that was asked with
 * @param more whether more entries were waiting after these when the page was read
 */
public record JournalPage(List<JournalEntry> entries, String cursor, boolean more) {
  public JournalPage {
    entries = List.copyOf(entries);
  }

  /** The page as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("entries", JsonValue.array(entries, JournalEntry::toJson))
        .put("cursor", cursor)
        .put("more", more)
        .build();
  }

  /** A page from its JSON. */
  public static JournalPage fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("journal page");
    return new JournalPage(
        o.list("entries", JournalEntry::fromJson), o.string("cursor", ""), o.bool("more", false));
  }

  /** A page from JSON text: {@code /v1/journal}'s answer. */
  public static JournalPage parse(String json) {
    return fromJson(Json.parse(json));
  }
}
