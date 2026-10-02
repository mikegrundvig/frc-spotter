package com.michaelgrundvig.frc.spotter.photonvision;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.layout.LayoutFingerprint;
import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsException;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import com.michaelgrundvig.frc.spotter.settings.SettingsRow;
import com.michaelgrundvig.frc.spotter.settings.SettingsText;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PhotonVision's pack helper: the one program the pack's definitions name, each use a fixed command
 * line (docs/agent.md, "Packs"). It reads what the agent itself mustn't (PhotonVision's SQLite
 * database, its jar) with the code the robot's build uses, so the two never disagree:
 *
 * <ul>
 *   <li>{@code version JAR}: PhotonVision's version, as its jar says it (its {@code
 *       PhotonVersion.versionString});
 *   <li>{@code hash DB}: the settings' hash ({@link Settings#hash}), as the robot's build hashes
 *       what's committed;
 *   <li>{@code fingerprint DB}: the AprilTag layout's fingerprint ({@link LayoutFingerprint});
 *   <li>{@code camera DB NAME}: whether that camera is plugged in at the USB port PhotonVision's
 *       settings match it by, and at what link speed;
 *   <li>{@code settings-json DB}: the settings as canonical rows, with their hash;
 *   <li>{@code settings-zip DB COMPUTER}: the settings as a zip laid out like the repository's
 *       folder for the computer, with every file's SHA-256 last;
 *   <li>{@code settings-db ROWS_DIR EMPTY_DB OUT_DB}: builds a database from committed settings,
 *       for stamping an image, and prints their hash.
 * </ul>
 *
 * <p>Exit status: 0 when it found what it was asked for; 1 when it found otherwise (a camera not
 * plugged in); 2 for a wrong command line; 3 when there's no database yet; 4 when it failed. What
 * it prints on success is one line, but for the downloads; why it failed goes to standard error.
 */
public final class Helper {
  static final int FOUND = 0;
  static final int NOT_FOUND = 1;
  static final int USAGE = 2;
  static final int NO_DATABASE = 3;
  static final int FAILED = 4;

  private final Database database;
  private final Path root;

  /**
   * @param database the bounds the database is read within
   * @param root where the computer's files are: {@code /}, but for tests
   */
  Helper(Database database, Path root) {
    this.database = database;
    this.root = root;
  }

  /** Runs one command; see the class. */
  public static void main(String[] args) {
    System.exit(
        new Helper(Database.DEFAULT, Path.of("/")).run(List.of(args), System.out, System.err));
  }

  /** Runs one command, printing to {@code out} and {@code err}; the exit status. */
  int run(List<String> args, PrintStream out, PrintStream err) {
    try {
      String command = args.isEmpty() ? "" : args.get(0);
      switch (command) {
        case "version":
          if (args.size() == 2) {
            out.println(PhotonVisionJar.version(Path.of(args.get(1))));
            return FOUND;
          }
          break;
        case "hash":
          if (args.size() == 2) {
            return settings(args.get(1), err)
                .map(text -> print(out, text.hash()))
                .orElse(NO_DATABASE);
          }
          break;
        case "fingerprint":
          if (args.size() == 2) {
            Optional<SettingsText> text = settings(args.get(1), err);
            if (text.isEmpty()) {
              return NO_DATABASE;
            }
            Optional<String> layout = Database.layout(text.get());
            if (layout.isEmpty()) {
              err.println(
                  "PhotonVision holds no AprilTag layout of its own: it uses WPILib's default");
              return NOT_FOUND;
            }
            out.println(LayoutFingerprint.ofJson(layout.get()));
            return FOUND;
          }
          break;
        case "camera":
          if (args.size() == 3) {
            Optional<SettingsText> text = settings(args.get(1), err);
            return text.isEmpty() ? NO_DATABASE : camera(text.get(), args.get(2), out);
          }
          break;
        case "settings-json":
          if (args.size() == 2) {
            Optional<SettingsText> text = settings(args.get(1), err);
            if (text.isPresent()) {
              settingsJson(text.get(), out);
              return FOUND;
            }
            return NO_DATABASE;
          }
          break;
        case "settings-zip":
          if (args.size() == 3) {
            Optional<SettingsText> text = settings(args.get(1), err);
            if (text.isPresent()) {
              settingsZip(text.get(), args.get(2), out);
              return FOUND;
            }
            return NO_DATABASE;
          }
          break;
        case "settings-db":
          if (args.size() == 4) {
            out.println(
                settingsDb(Path.of(args.get(1)), Path.of(args.get(2)), Path.of(args.get(3))));
            return FOUND;
          }
          break;
        default:
          break;
      }
      err.println(
          "Usage: photonvision-helper version JAR | hash DB | fingerprint DB | camera DB NAME"
              + " | settings-json DB | settings-zip DB COMPUTER | settings-db ROWS_DIR EMPTY_DB OUT_DB");
      return USAGE;
    } catch (IOException | SQLException | RuntimeException e) {
      err.println("photonvision-helper: " + e.getMessage());
      return FAILED;
    }
  }

  private static int print(PrintStream out, String line) {
    out.println(line);
    return FOUND;
  }

  /** The database's settings; empty, said, when there's no database yet. */
  private Optional<SettingsText> settings(String file, PrintStream err) throws IOException {
    Optional<SettingsText> text = database.read(Path.of(file));
    if (text.isEmpty()) {
      err.println("there's no settings database at " + file + " yet");
    }
    return text;
  }

  /** Whether a camera is plugged in where PhotonVision's settings expect it. */
  private int camera(SettingsText text, String name, PrintStream out) throws IOException {
    String path = Database.usbPaths(text).get(name);
    if (path == null) {
      out.println(
          "unknown camera " + name + ": PhotonVision's settings have no camera of that name");
      return NOT_FOUND;
    }
    if (path.isEmpty()) {
      out.println("unknown port: PhotonVision's settings name no port for " + name);
      return NOT_FOUND;
    }
    Path at = root.resolve(path.substring(1));
    if (!Files.exists(at)) {
      out.println("missing " + path);
      return NOT_FOUND;
    }
    out.println(String.format(Locale.ROOT, "present %s %.0f Mb/s", path, speed(at)));
    return FOUND;
  }

  /** A device node's USB link speed, in Mb/s, from sysfs: 0 when it can't be found. */
  double speed(Path byPath) {
    try {
      String node = byPath.toRealPath().getFileName().toString();
      Path device =
          root.resolve("sys/class/video4linux").resolve(node).resolve("device").toRealPath();
      for (int up = 0; up < 8 && device != null; up++, device = device.getParent()) {
        Path speed = device.resolve("speed");
        if (Files.isRegularFile(speed) && Files.isRegularFile(device.resolve("idVendor"))) {
          return Double.parseDouble(Files.readString(speed, StandardCharsets.UTF_8).strip());
        }
      }
    } catch (IOException | NumberFormatException e) {
      // Unplugged as it was read, or not a USB device: present, at a speed unknown.
    }
    return 0;
  }

  /** The settings as JSON, as {@code Settings.toJson()} writes them, a row at a time. */
  static void settingsJson(SettingsText text, OutputStream stream) throws IOException {
    Writer out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    out.write("{\"hash\":" + Json.compact(JsonValue.of(text.hash())));
    out.write(",\"userVersion\":" + text.userVersion() + ",\"rows\":[");
    boolean first = true;
    for (SettingsText.Row row : text.rows()) {
      SettingsRow parsed = row.parse();
      out.write(first ? "" : ",");
      first = false;
      Json.writeCompact(
          JsonValue.Obj.builder()
              .put("table", parsed.table())
              .put("key", parsed.key())
              .put("columns", parsed.columns())
              .build(),
          out);
    }
    out.write("]}");
    out.flush();
  }

  /**
   * The settings as a zip laid out like the repository: {@code coprocessors/<computer>/settings/}
   * and its files, so unzipping it at the repository's root puts them in place; and last, beside
   * that folder, {@code settings.sha256}: every file's SHA-256, as {@code sha256sum} writes them,
   * so a zip that was cut short shows it ({@code sha256sum -c} from the repository's root).
   */
  static void settingsZip(SettingsText text, String computer, OutputStream stream)
      throws IOException {
    String folder = SettingsFiles.folder(computer);
    List<String> paths =
        text.rows().stream().map(row -> SettingsFiles.path(row.table(), row.key())).toList();
    SettingsFiles.checkCase(paths);
    StringBuilder sums = new StringBuilder();
    try (ZipOutputStream zip = new ZipOutputStream(stream)) {
      String database = folder + "/" + SettingsFiles.DATABASE_FILE;
      sums.append(
              entry(zip, database, out -> out.append(SettingsFiles.database(text.userVersion()))))
          .append("  ")
          .append(database)
          .append('\n');
      for (int i = 0; i < paths.size(); i++) {
        SettingsRow row = text.rows().get(i).parse();
        String path = folder + "/" + paths.get(i);
        sums.append(entry(zip, path, out -> SettingsFiles.write(row, out)))
            .append("  ")
            .append(path)
            .append('\n');
      }
      entry(zip, folder + ".sha256", out -> out.append(sums));
    }
  }

  private interface Content {
    void write(Appendable out) throws IOException;
  }

  /** Writes one zip entry as UTF-8 text; its SHA-256. */
  private static String entry(ZipOutputStream zip, String path, Content content)
      throws IOException {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
    zip.putNextEntry(new ZipEntry(path));
    // Not closing the zip when the entry's writer is done.
    OutputStream entry =
        new FilterOutputStream(zip) {
          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
          }

          @Override
          public void close() throws IOException {
            flush();
          }
        };
    Writer out =
        new BufferedWriter(
            new OutputStreamWriter(new DigestOutputStream(entry, sha256), StandardCharsets.UTF_8));
    content.write(out);
    out.close();
    zip.closeEntry();
    return HexFormat.of().formatHex(sha256.digest());
  }

  /**
   * Builds {@code target} from a copy of {@code empty} (the database the pinned PhotonVision made,
   * empty) and the committed settings in {@code rows}, reads it back to check nothing was lost, and
   * answers the settings' hash: the one an image's stamp carries.
   */
  static String settingsDb(Path rows, Path empty, Path target) throws IOException, SQLException {
    Settings settings =
        SettingsFiles.read(rows)
            .orElseThrow(() -> new SettingsException("there are no settings in " + rows));
    if (!Files.isRegularFile(empty)) {
      throw new SettingsException("there's no database at " + empty);
    }
    Files.copy(empty, target, StandardCopyOption.REPLACE_EXISTING);
    Settings written;
    try (Connection connection = Database.open(target)) {
      SettingsDatabase.write(connection, settings);
      written = SettingsDatabase.read(connection);
    }
    if (!written.equals(settings)) {
      throw new SettingsException(target + " doesn't hold the settings it was given");
    }
    return settings.hash();
  }
}
