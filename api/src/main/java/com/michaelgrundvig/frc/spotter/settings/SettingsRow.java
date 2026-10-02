package com.michaelgrundvig.frc.spotter.settings;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of PhotonVision's settings database: its table, its key (the primary key's value), and
 * every other column's value. PhotonVision keeps JSON text in its columns, so each column's text is
 * read as JSON and kept as JSON, which a diff can show member by member. A column whose text isn't
 * JSON is kept as text, under its name plus {@link #TEXT_SUFFIX}, so nothing is lost or guessed.
 *
 * @param table the table, such as {@code global} or {@code cameras}
 * @param key the row's key, such as {@code networkConfig} or a camera's unique name
 * @param columns every other column, by name
 */
public record SettingsRow(String table, String key, JsonValue.Obj columns) {
  /** Marks a column kept as text because its text isn't JSON. */
  public static final String TEXT_SUFFIX = ".text";

  /** A row from the columns' text as the database holds it. */
  public static SettingsRow fromText(String table, String key, Map<String, String> columnText) {
    JsonValue.Obj.Builder columns = JsonValue.Obj.builder();
    columnText.forEach(
        (column, text) -> {
          try {
            columns.put(column, Json.parse(text));
          } catch (JsonException e) {
            columns.put(column + TEXT_SUFFIX, JsonValue.of(text));
          }
        });
    return new SettingsRow(table, key, columns.build());
  }

  /** The columns' text as the database should hold it: JSON on one line, or the kept text. */
  public Map<String, String> columnText() {
    Map<String, String> text = new LinkedHashMap<>();
    columns
        .members()
        .forEach(
            (name, value) -> {
              if (name.endsWith(TEXT_SUFFIX) && value instanceof JsonValue.Str raw) {
                text.put(name.substring(0, name.length() - TEXT_SUFFIX.length()), raw.value());
              } else {
                text.put(name, Json.compact(value));
              }
            });
    return text;
  }
}
