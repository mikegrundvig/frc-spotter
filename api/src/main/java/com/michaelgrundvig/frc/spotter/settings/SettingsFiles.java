package com.michaelgrundvig.frc.spotter.settings;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Settings as files in Git: one file per row, so a diff shows exactly what changed. A computer's
 * settings live in {@code coprocessors/<computer>/settings/}:
 *
 * <pre>
 * database.json                 {"userVersion": 2}
 * global/networkConfig.json     the row's columns, {"contents": {...}}
 * cameras/&lt;unique name&gt;.json    {"config_json": {...}, "drivermode_json": ..., ...}
 * </pre>
 *
 * <p>Each file is the row's columns as {@link Json#pretty} writes them: sorted, indented, ending in
 * a newline. A key becomes a file name with every character but letters, digits, {@code .}, {@code
 * _} and {@code -} written as {@code %XX} (its UTF-8 bytes), as are a leading dot and the names
 * Windows reserves ({@code CON}, {@code NUL}, ...), so every key makes a file on every system. Two
 * keys that differ only in case would be one file on Windows and macOS, so they're refused.
 */
public final class SettingsFiles {
  /** The file holding the database's schema version. */
  public static final String DATABASE_FILE = "database.json";

  /** Where a computer's settings live in a team's repository, from its root. */
  public static String folder(String computer) {
    return "coprocessors/" + computer + "/settings";
  }

  private static final String SUFFIX = ".json";
  private static final Set<String> RESERVED =
      Set.of(
          "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7",
          "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

  private SettingsFiles() {}

  /** The files for these settings: each one's path (with {@code /}) and its text. */
  public static SortedMap<String, String> render(Settings settings) {
    SortedMap<String, String> files = new TreeMap<>();
    files.put(DATABASE_FILE, database(settings.userVersion()));
    List<String> paths = new ArrayList<>();
    for (SettingsRow row : settings.rows()) {
      String path = path(row.table(), row.key());
      paths.add(path);
      files.put(path, text(row));
    }
    checkCase(paths);
    return files;
  }

  /** {@value #DATABASE_FILE}'s text: the schema version. */
  public static String database(int userVersion) {
    return Json.pretty(JsonValue.Obj.builder().put("userVersion", userVersion).build());
  }

  /** A row's file, from the settings folder: {@code <table>/<key>.json}, each name encoded. */
  public static String path(String table, String key) {
    return encode(table) + "/" + encode(key) + SUFFIX;
  }

  /** A row's file's text: its columns, sorted and indented. */
  public static String text(SettingsRow row) {
    return Json.pretty(row.columns());
  }

  /** Writes a row's file's text to {@code out} as it goes: the same as {@link #text}. */
  public static void write(SettingsRow row, Appendable out) throws IOException {
    Json.writePretty(row.columns(), out);
  }

  /** Refuses rows' files that differ only in case, which would be one file on Windows and macOS. */
  public static void checkCase(List<String> paths) {
    Map<String, String> folded = new HashMap<>();
    for (String path : paths) {
      String clash = folded.put(path.toLowerCase(Locale.ROOT), path);
      if (clash != null) {
        throw new SettingsException(
            "\""
                + clash
                + "\" and \""
                + path
                + "\" differ only in case, so they'd be one file on Windows and macOS");
      }
    }
  }

  /** Settings from their files (each path with {@code /}, and its text). */
  public static Settings parse(Map<String, String> files) {
    String database = files.get(DATABASE_FILE);
    if (database == null) {
      throw new SettingsException(DATABASE_FILE + " is missing");
    }
    int userVersion = read(DATABASE_FILE, database).integer("userVersion", -1);
    if (userVersion < 0) {
      throw new SettingsException(DATABASE_FILE + " has no userVersion");
    }
    List<SettingsRow> rows = new ArrayList<>();
    for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
      String path = file.getKey();
      if (path.equals(DATABASE_FILE)) {
        continue;
      }
      String[] parts = path.split("/", -1);
      if (parts.length != 2 || !parts[1].endsWith(SUFFIX)) {
        throw new SettingsException(
            path + " isn't a row: rows are <table>/<key>.json beside " + DATABASE_FILE);
      }
      String key = parts[1].substring(0, parts[1].length() - SUFFIX.length());
      rows.add(
          new SettingsRow(decode(path, parts[0]), decode(path, key), read(path, file.getValue())));
    }
    return new Settings(userVersion, rows);
  }

  /**
   * Reads the settings in a folder, or none when it doesn't exist. Files whose names start with a
   * dot ({@code .gitkeep}) and anything but {@code .json} files are left out.
   */
  public static Optional<Settings> read(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return Optional.empty();
    }
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(folder)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        String name = file.getFileName().toString();
        if (name.startsWith(".") || !name.endsWith(SUFFIX)) {
          continue;
        }
        String path = folder.relativize(file).toString().replace('\\', '/');
        files.put(path, Files.readString(file, StandardCharsets.UTF_8));
      }
    }
    return Optional.of(parse(files));
  }

  /**
   * Writes the settings into a folder, replacing the settings there: every {@code .json} file the
   * folder had is deleted first, so a row that's gone loses its file.
   */
  public static void write(Settings settings, Path folder) throws IOException {
    SortedMap<String, String> files = render(settings);
    if (Files.isDirectory(folder)) {
      List<Path> old;
      try (Stream<Path> walk = Files.walk(folder)) {
        old =
            walk.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                .toList();
      }
      for (Path file : old) {
        Files.delete(file);
      }
    }
    for (Map.Entry<String, String> file : files.entrySet()) {
      Path path = folder.resolve(file.getKey());
      Files.createDirectories(path.getParent());
      Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
    }
  }

  /** A table's or key's name as a file name. */
  static String encode(String name) {
    if (name.isEmpty()) {
      throw new SettingsException("a table or key with an empty name can't be a file");
    }
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    StringBuilder out = new StringBuilder();
    String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
    boolean reserved = RESERVED.contains(base.toLowerCase(Locale.ROOT));
    for (int i = 0; i < bytes.length; i++) {
      int b = bytes[i] & 0xff;
      boolean plain =
          (b >= 'a' && b <= 'z')
              || (b >= 'A' && b <= 'Z')
              || (b >= '0' && b <= '9')
              || b == '_'
              || b == '-'
              || (b == '.' && i > 0);
      if (plain && !(reserved && i == 0)) {
        out.append((char) b);
      } else {
        out.append(String.format("%%%02X", b));
      }
    }
    return out.toString();
  }

  /** A file name back to the table's or key's name. */
  static String decode(String path, String encoded) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 0; i < encoded.length(); i++) {
      char c = encoded.charAt(i);
      if (c != '%') {
        bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        continue;
      }
      int value = i + 2 >= encoded.length() ? -1 : Json.hexValue(encoded.substring(i + 1, i + 3));
      if (value < 0) {
        throw new SettingsException(path + ": a % in a name must be followed by two hex digits");
      }
      bytes.write(value);
      i += 2;
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static JsonValue.Obj read(String path, String text) {
    try {
      return Json.parse(text).asObject(path);
    } catch (JsonException e) {
      throw new SettingsException(path + ": " + e.getMessage(), e);
    }
  }
}
