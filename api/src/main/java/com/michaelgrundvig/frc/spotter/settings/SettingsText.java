package com.michaelgrundvig.frc.spotter.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings as the database holds them: each column's text, unparsed. A calibration's JSON is about
 * a megabyte of text and many times that parsed, so the agent keeps settings like this and parses
 * one row at a time ({@link #row}), for the hash, for {@code /v1/settings}, and for the backup.
 *
 * @param userVersion the database's schema version
 * @param rows each row's table, key, and column text, in order of table, then key
 */
public record SettingsText(int userVersion, List<Row> rows) {
  /**
   * One row's text.
   *
   * @param table its table
   * @param key its key
   * @param columns each other column's text, by name
   */
  public record Row(String table, String key, Map<String, String> columns) {
    public Row {
      columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /** The row, parsed. */
    public SettingsRow parse() {
      return SettingsRow.fromText(table, key, columns);
    }
  }

  public SettingsText {
    List<Row> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator.comparing(Row::table).thenComparing(Row::key));
    rows = List.copyOf(sorted);
  }

  /** Every row, parsed: all of it in memory at once. */
  public Settings parse() {
    return new Settings(userVersion, rows.stream().map(Row::parse).toList());
  }

  /** The settings' hash, parsing one row at a time; the same as {@code parse().hash()}. */
  public String hash() {
    SettingsHash.Digest digest = new SettingsHash.Digest();
    for (Row row : rows) {
      digest.add(row.parse());
    }
    return digest.finish(userVersion);
  }

  /** How many characters of text the settings are, about their size in bytes. */
  public long length() {
    long length = 0;
    for (Row row : rows) {
      length += row.key().length();
      for (String text : row.columns().values()) {
        length += text.length();
      }
    }
    return length;
  }
}
