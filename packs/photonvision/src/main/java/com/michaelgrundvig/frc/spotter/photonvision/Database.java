package com.michaelgrundvig.frc.spotter.photonvision;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsException;
import com.michaelgrundvig.frc.spotter.settings.SettingsText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.sqlite.SQLiteConfig;

/**
 * PhotonVision's settings database, read the only way this pack reads it: read-only, in one
 * transaction (so PhotonVision's writes wait milliseconds at most), and bounded as it's read, not
 * after. A hostile or broken database can't make the helper hold a huge value, run anything, or
 * read past its bounds; past one, it's refused, saying which.
 *
 * <p>The bounds are measured: PhotonVision's settings parse to about nine times their text, so one
 * value of 2 MB (a calibration is about 1 MB) parses within a 64 MB heap beside 16 MB of text.
 *
 * @param maxDatabase the largest database read, in bytes of file (with its write-ahead log)
 * @param maxSettings the most settings text read, in characters, all values together
 * @param maxValue the longest one setting (a column's text) read, in characters
 */
record Database(long maxDatabase, long maxSettings, int maxValue) {
  /** The helper's bounds on a coprocessor. */
  static final Database DEFAULT =
      new Database(32L * 1024 * 1024, 16L * 1024 * 1024, 2 * 1024 * 1024);

  /** How long to wait for PhotonVision to finish a write before giving up, in milliseconds. */
  static final int BUSY_TIMEOUT = 2000;

  /**
   * The global row holding the AprilTag layout: PhotonVision's current name, then its older one.
   */
  static final List<String> LAYOUT_ROWS = List.of("fieldLayout", "apriltagFieldLayout");

  /** The settings in a database, as text; empty when there's no database there. */
  Optional<SettingsText> read(Path file) throws IOException {
    long size;
    try {
      size = Files.size(file);
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
    // In write-ahead-log mode, a save grows the log, not the file.
    Path log = file.resolveSibling(file.getFileName() + "-wal");
    if (Files.isRegularFile(log)) {
      size += Files.size(log);
    }
    if (size > maxDatabase) {
      throw new IOException(
          file + " is " + size + " bytes, more than the " + maxDatabase + " read");
    }
    try (Connection connection = openReadOnly(file)) {
      return Optional.of(SettingsDatabase.readText(connection, maxValue, maxSettings));
    } catch (SQLException | SettingsException e) {
      throw new IOException(file + ": " + e.getMessage(), e);
    }
  }

  /**
   * A read-only connection. SQLite itself refuses any string or row longer than twice {@code
   * maxValue} bytes (SQLITE_LIMIT_LENGTH), and the schema's functions are distrusted.
   */
  Connection openReadOnly(Path file) throws SQLException {
    SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    config.setBusyTimeout(BUSY_TIMEOUT);
    config.setPragma(
        SQLiteConfig.Pragma.LIMIT_LENGTH,
        Long.toString(Math.min(Integer.MAX_VALUE, 2L * maxValue)));
    return config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
  }

  /** A connection that may write: for building a database at stamping, never a live one. */
  static Connection open(Path file) throws SQLException {
    SQLiteConfig config = new SQLiteConfig();
    config.setBusyTimeout(BUSY_TIMEOUT);
    return config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
  }

  /** The AprilTag layout PhotonVision holds, as WPILib's JSON; empty when it holds none. */
  static Optional<String> layout(SettingsText text) {
    for (String name : LAYOUT_ROWS) {
      for (SettingsText.Row row : text.rows()) {
        if (row.table().equals("global") && row.key().equals(name)) {
          String contents = row.columns().get("contents");
          if (contents != null && !contents.isBlank()) {
            return Optional.of(contents);
          }
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Where each camera is expected, as PhotonVision matches it, by its name (nickname): its matched
   * camera's unique path, the first by-path entry (sorted) among its other paths, or failing those
   * its path.
   */
  static Map<String, String> usbPaths(SettingsText text) {
    Map<String, String> paths = new LinkedHashMap<>();
    for (SettingsText.Row row : text.rows()) {
      if (!row.table().equals("cameras")) {
        continue;
      }
      String config = row.columns().get("config_json");
      if (config == null) {
        continue;
      }
      JsonValue.Obj camera;
      try {
        camera = Json.parse(config).asObject("config_json");
      } catch (JsonException e) {
        continue;
      }
      String nickname = camera.string("nickname", "");
      JsonValue.Obj matched = camera.objectOrEmpty("matchedCameraInfo");
      String path = matched.string("uniquePath", "");
      if (path.isEmpty()) {
        List<String> others = new ArrayList<>();
        for (JsonValue other : matched.items("otherPaths")) {
          if (other instanceof JsonValue.Str
              && ((JsonValue.Str) other).value().contains("/by-path/")) {
            others.add(((JsonValue.Str) other).value());
          }
        }
        Collections.sort(others);
        path = others.isEmpty() ? matched.string("path", "") : others.get(0);
      }
      if (!nickname.isEmpty()) {
        paths.put(nickname, path);
      }
    }
    return paths;
  }
}
