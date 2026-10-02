package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsException;
import com.michaelgrundvig.frc.spotter.settings.SettingsText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * PhotonVision's live settings, read from its database read-only. Only their summary is kept (the
 * hash, and where each camera is expected), worked out again when the database file changes; the
 * settings themselves are read when they're sent, and dropped after. Reading them is bounded
 * ({@link Limits}), and one copy at most is in memory at a time: a summary isn't worked out while
 * the settings are being sent (the last one stands in), and a send waits for its turn.
 */
final class SettingsSource {
  /** How long a send waits for its turn before the request is refused as busy. */
  static final long SEND_WAIT_MILLIS = 5000;

  private final Host host;
  private final String database;
  private final Limits limits;
  private final Semaphore memory = new Semaphore(1);
  private final Object lock = new Object();
  private @Nullable Known known;

  /**
   * The settings' summary as of one version of the database file.
   *
   * @param version the file's (and its write-ahead log's) size and modification time
   * @param hash the settings' hash
   * @param usbPaths where each camera is expected (its by-path), by camera name (nickname)
   */
  record Summary(String version, String hash, Map<String, String> usbPaths) {}

  /** What's known of one version of the file: its summary, or why there's none. */
  private record Known(String version, @Nullable Summary summary, String failure) {}

  /** The settings couldn't be sent now: another send is under way. */
  static final class Busy extends Exception {
    private static final long serialVersionUID = 1L;

    Busy() {
      super("the settings are being sent; ask again shortly");
    }
  }

  /** Sends the settings' text. */
  interface Sending<T> {
    T send(SettingsText text) throws IOException;
  }

  SettingsSource(Host host, String database, Limits limits) {
    this.host = host;
    this.database = database;
    this.limits = limits;
  }

  /**
   * The settings' summary now; empty when PhotonVision has no database yet. A file that couldn't be
   * read is not read again until it changes; while the settings are being sent, the last summary
   * stands in.
   */
  Optional<Summary> summary() throws IOException {
    Optional<String> version = version();
    if (version.isEmpty()) {
      synchronized (lock) {
        known = null;
      }
      return Optional.empty();
    }
    Known last;
    synchronized (lock) {
      last = known;
    }
    if (last != null && last.version().equals(version.get())) {
      return found(last);
    }
    if (!memory.tryAcquire()) {
      Summary stale = last == null ? null : last.summary();
      if (stale != null) {
        return Optional.of(stale);
      }
      throw new IOException("the settings are being sent; their hash comes next time");
    }
    Known now;
    try {
      SettingsText text = read();
      now = new Known(version.get(), new Summary(version.get(), text.hash(), usbPaths(text)), "");
    } catch (IOException | RuntimeException e) {
      now = new Known(version.get(), null, String.valueOf(e.getMessage()));
    } finally {
      memory.release();
    }
    synchronized (lock) {
      known = now;
    }
    return found(now);
  }

  private static Optional<Summary> found(Known known) throws IOException {
    Summary summary = known.summary();
    if (summary == null) {
      throw new IOException(known.failure());
    }
    return Optional.of(summary);
  }

  /**
   * Reads the settings now and sends them; empty when PhotonVision has no database yet.
   *
   * @throws Busy when another send held the settings for {@link #SEND_WAIT_MILLIS}
   */
  <T> Optional<T> send(Sending<T> sending) throws IOException, Busy {
    if (version().isEmpty()) {
      return Optional.empty();
    }
    try {
      if (!memory.tryAcquire(SEND_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
        throw new Busy();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Busy();
    }
    try {
      return Optional.of(sending.send(read()));
    } finally {
      memory.release();
    }
  }

  /** The database file's version (sizes and times), or empty when there's none. */
  private Optional<String> version() throws IOException {
    Path file = host.path(database);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    BasicFileAttributes main = Files.readAttributes(file, BasicFileAttributes.class);
    long size = main.size();
    String version = main.size() + "@" + main.lastModifiedTime().toMillis();
    // In write-ahead-log mode, a save changes the log, not the file.
    Path log = host.path(database + "-wal");
    if (Files.isRegularFile(log)) {
      BasicFileAttributes wal = Files.readAttributes(log, BasicFileAttributes.class);
      size += wal.size();
      version += "+" + wal.size() + "@" + wal.lastModifiedTime().toMillis();
    }
    if (size > limits.maxDatabase()) {
      throw new IOException(
          database + " is " + size + " bytes, more than the " + limits.maxDatabase() + " read");
    }
    return Optional.of(version);
  }

  private SettingsText read() throws IOException {
    try (Connection connection = Sqlite.openReadOnly(host.path(database), limits.maxValue())) {
      return SettingsDatabase.readText(connection, limits.maxValue(), limits.maxSettings());
    } catch (SQLException | SettingsException e) {
      throw new IOException(database + ": " + e.getMessage(), e);
    }
  }

  /**
   * Where each camera is expected, as PhotonVision matches it: its matched camera's unique path,
   * the first by-path entry (sorted) among its other paths, or failing those its path.
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
          if (other instanceof JsonValue.Str string && string.value().contains("/by-path/")) {
            others.add(string.value());
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
