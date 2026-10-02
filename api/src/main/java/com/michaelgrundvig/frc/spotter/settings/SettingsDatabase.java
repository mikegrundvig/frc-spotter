package com.michaelgrundvig.frc.spotter.settings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Settings read from and written to PhotonVision's SQLite database, through JDBC's own API: the
 * driver comes from whoever calls (the agent brings xerial's sqlite-jdbc), so the robot program,
 * which only hashes files, needs none.
 *
 * <p>Nothing here knows PhotonVision's tables: it reads every table, with its primary key as the
 * row's key and every other column as JSON, so a column a new version adds comes along unasked.
 * What isn't a plain table with one primary key (a view, a virtual table, a table keyed by two
 * columns, or one with a column named like a kept-text column, {@code *.text}) is left out, and
 * left alone; PhotonVision makes none.
 *
 * <p>A database is treated as untrusted: its schema runs no functions ({@code trusted_schema} off),
 * and reading stops once the settings are larger than the caller allows.
 *
 * <p>Two things don't round-trip, and PhotonVision's schema has neither: an SQL NULL reads as JSON
 * {@code null} and is written back as the text {@code null} (its columns are NOT NULL); and a
 * column named {@code x.text} would read as the kept text of a column {@code x}, so such a table is
 * left out.
 */
public final class SettingsDatabase {
  /** No limit on how much is read. */
  public static final long UNLIMITED = Long.MAX_VALUE;

  private SettingsDatabase() {}

  /** Every row of every table, and the schema version, parsed. */
  public static Settings read(Connection connection) throws SQLException {
    return readText(connection).parse();
  }

  /**
   * Every row of every table, and the schema version, as the database's text: one quick pass, which
   * holds PhotonVision's writes off for as short a time as it can.
   */
  public static SettingsText readText(Connection connection) throws SQLException {
    return readText(connection, UNLIMITED, UNLIMITED);
  }

  /**
   * As {@link #readText(Connection)}, stopping with a {@link SettingsException} as soon as one
   * column's text is longer than {@code maxValue} characters, or all of it longer than {@code
   * maxTotal}: checked as each value is read, so a database too large to hold is never held.
   */
  public static SettingsText readText(Connection connection, long maxValue, long maxTotal)
      throws SQLException {
    // One transaction, so every table is read as of one moment, between PhotonVision's saves.
    boolean autoCommit = connection.getAutoCommit();
    distrust(connection);
    connection.setAutoCommit(false);
    try {
      return readTextInTransaction(connection, maxValue, maxTotal);
    } finally {
      connection.rollback();
      connection.setAutoCommit(autoCommit);
    }
  }

  private static SettingsText readTextInTransaction(
      Connection connection, long maxValue, long maxTotal) throws SQLException {
    List<SettingsText.Row> rows = new ArrayList<>();
    long total = 0;
    for (TableShape shape : shapes(connection).values()) {
      String sql =
          "SELECT * FROM "
              + quote(shape.table())
              + " ORDER BY "
              + quote(shape.key())
              + " COLLATE BINARY";
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        while (result.next()) {
          String key = result.getString(shape.key());
          if (key == null) {
            throw new SettingsException("table " + shape.table() + " has a row with no key");
          }
          total += key.length();
          Map<String, String> columns = new LinkedHashMap<>();
          for (String column : shape.columns()) {
            String text = result.getString(column);
            String value = text == null ? "null" : text;
            if (value.length() > maxValue) {
              throw new SettingsException(
                  shape.table()
                      + "/"
                      + key
                      + " has a value of "
                      + value.length()
                      + " characters, more than the "
                      + maxValue
                      + " read");
            }
            total += value.length();
            if (total > maxTotal) {
              throw new SettingsException(
                  "the settings are more than the " + maxTotal + " characters read");
            }
            columns.put(column, value);
          }
          rows.add(new SettingsText.Row(shape.table(), key, columns));
        }
      }
    }
    return new SettingsText(userVersion(connection), rows);
  }

  /** Runs nothing the database's schema asks for: its views and triggers call no functions. */
  private static void distrust(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA trusted_schema = OFF");
    }
  }

  /**
   * Replaces every row of every table with the settings' rows, in one transaction. The database
   * must be at the settings' schema version, and have each table and column they name: that is, be
   * made by the PhotonVision version the settings came from.
   */
  public static void write(Connection connection, Settings settings) throws SQLException {
    int version = userVersion(connection);
    if (version != settings.userVersion()) {
      throw new SettingsException(
          "the settings are for schema version "
              + settings.userVersion()
              + " and the database is at "
              + version
              + ": save the settings again from the PhotonVision version the database is for");
    }
    distrust(connection);
    Map<String, TableShape> tables = shapes(connection);
    for (SettingsRow row : settings.rows()) {
      if (!tables.containsKey(row.table())) {
        throw new SettingsException(
            "the database has no table " + row.table() + "; it's for another PhotonVision version");
      }
    }
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try {
      for (TableShape shape : tables.values()) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM " + quote(shape.table()));
        }
        for (SettingsRow row : settings.rows(shape.table())) {
          insert(connection, shape, row);
        }
      }
      connection.commit();
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  private static void insert(Connection connection, TableShape shape, SettingsRow row)
      throws SQLException {
    Map<String, String> text = row.columnText();
    for (String column : text.keySet()) {
      if (!shape.columns().contains(column)) {
        throw new SettingsException(
            "table "
                + shape.table()
                + " has no column "
                + column
                + " (row \""
                + row.key()
                + "\"); the settings are for another PhotonVision version");
      }
    }
    List<String> names = new ArrayList<>();
    names.add(shape.key());
    names.addAll(text.keySet());
    String sql =
        "INSERT INTO "
            + quote(shape.table())
            + " ("
            + String.join(", ", names.stream().map(SettingsDatabase::quote).toList())
            + ") VALUES ("
            + String.join(", ", names.stream().map(name -> "?").toList())
            + ")";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, row.key());
      int index = 2;
      for (String value : text.values()) {
        statement.setString(index++, value);
      }
      statement.executeUpdate();
    }
  }

  private record TableShape(String table, String key, List<String> columns) {}

  /**
   * The settings' tables, by name: the database's plain tables (not views, virtual tables, or
   * SQLite's own) with a single primary key and no column named like kept text.
   */
  private static Map<String, TableShape> shapes(Connection connection) throws SQLException {
    Map<String, TableShape> shapes = new TreeMap<>();
    for (String table : tables(connection)) {
      TableShape shape = shape(connection, table);
      if (shape != null) {
        shapes.put(table, shape);
      }
    }
    return shapes;
  }

  private static @Nullable TableShape shape(Connection connection, String table)
      throws SQLException {
    String key = null;
    int keys = 0;
    List<String> columns = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
      while (result.next()) {
        String name = result.getString("name");
        if (name.endsWith(SettingsRow.TEXT_SUFFIX)) {
          return null;
        }
        if (result.getInt("pk") > 0) {
          key = name;
          keys++;
        } else {
          columns.add(name);
        }
      }
    }
    return key == null || keys != 1 ? null : new TableShape(table, key, columns);
  }

  /** The plain tables in the main database: no views, virtual or shadow tables, or SQLite's. */
  private static Set<String> tables(Connection connection) throws SQLException {
    Set<String> tables = new TreeSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA main.table_list")) {
      while (result.next()) {
        String name = result.getString("name");
        if (result.getString("type").equals("table") && !name.startsWith("sqlite_")) {
          tables.add(name);
        }
      }
    }
    return tables;
  }

  /** The database's schema version: {@code PRAGMA user_version}. */
  public static int userVersion(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA user_version")) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  /** An identifier, quoted for SQLite. */
  static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
