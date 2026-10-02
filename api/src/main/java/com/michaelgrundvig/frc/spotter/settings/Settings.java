package com.michaelgrundvig.frc.spotter.settings;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PhotonVision's settings, as rows: everything in its database ({@code photon.sqlite}), in a form
 * that's the same however it was read. Rows are kept in order of table, then key, so two sets of
 * the same settings are equal, and write the same files.
 *
 * @param userVersion the database's schema version ({@code PRAGMA user_version}), which
 *     PhotonVision raises with each migration
 * @param rows every row of every table
 */
public record Settings(int userVersion, List<SettingsRow> rows) {
  /** By table, then key. */
  static final Comparator<SettingsRow> ORDER =
      Comparator.comparing(SettingsRow::table).thenComparing(SettingsRow::key);

  public Settings {
    List<SettingsRow> sorted = new ArrayList<>(rows);
    sorted.sort(ORDER);
    for (int i = 1; i < sorted.size(); i++) {
      if (ORDER.compare(sorted.get(i - 1), sorted.get(i)) == 0) {
        throw new SettingsException(
            "two rows of table "
                + sorted.get(i).table()
                + " have the key \""
                + sorted.get(i).key()
                + "\"");
      }
    }
    rows = List.copyOf(sorted);
  }

  /** The row with this table and key, if there is one. */
  public Optional<SettingsRow> row(String table, String key) {
    return rows.stream()
        .filter(row -> row.table().equals(table) && row.key().equals(key))
        .findFirst();
  }

  /** The rows of one table, in order of key. */
  public List<SettingsRow> rows(String table) {
    return rows.stream().filter(row -> row.table().equals(table)).toList();
  }

  /** The settings' hash (see {@link SettingsHash}). */
  public String hash() {
    return SettingsHash.of(this);
  }

  /** The settings as JSON, with their hash: what {@code /v1/settings} answers. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("hash", hash())
        .put("userVersion", userVersion)
        .put(
            "rows",
            JsonValue.array(
                rows,
                row ->
                    JsonValue.Obj.builder()
                        .put("table", row.table())
                        .put("key", row.key())
                        .put("columns", row.columns())
                        .build()))
        .build();
  }

  /** Settings from their JSON; the hash in it, if any, is ignored (it's recomputed). */
  public static Settings fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("settings");
    return new Settings(
        o.integer("userVersion", 0),
        o.list(
            "rows",
            item -> {
              JsonValue.Obj row = item.asObject("row");
              return new SettingsRow(
                  row.string("table", ""), row.string("key", ""), row.objectOrEmpty("columns"));
            }));
  }

  /** Settings from JSON text: {@code /v1/settings}'s answer. */
  public static Settings parse(String json) {
    return fromJson(Json.parse(json));
  }
}
