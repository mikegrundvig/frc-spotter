package com.michaelgrundvig.frc.spotter.photonvision;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.layout.LayoutFingerprint;
import com.michaelgrundvig.frc.spotter.settings.PhotonVisionDatabase;
import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import com.michaelgrundvig.frc.spotter.settings.SettingsText;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The helper's commands on a PhotonVision database made from its schema and example rows, and on a
 * jar carrying a PhotonVersion: what each prints, and its exit status.
 */
class HelperTest {
  @TempDir Path dir;
  Path db;
  Settings settings;
  final ByteArrayOutputStream out = new ByteArrayOutputStream();
  final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @BeforeEach
  void aDatabase() throws Exception {
    db = PhotonVisionDatabase.configured(dir.resolve("config"));
    try (Connection connection = PhotonVisionDatabase.open(db)) {
      settings = SettingsDatabase.read(connection);
    }
  }

  private int run(Helper helper, String... args) {
    out.reset();
    err.reset();
    return helper.run(
        List.of(args),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  private int run(String... args) {
    return run(new Helper(Database.DEFAULT, dir.resolve("root")), args);
  }

  private String printed() {
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void theHashIsTheSettingsAsTheRobotsBuildHashesThem() {
    assertThat(run("hash", db.toString())).isEqualTo(Helper.FOUND);
    assertThat(printed().strip()).isEqualTo(settings.hash());
    assertThat(run("hash", dir.resolve("none.sqlite").toString())).isEqualTo(Helper.NO_DATABASE);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("no settings database");
  }

  @Test
  void theFingerprintIsTheLayoutPhotonVisionHolds() {
    assertThat(run("fingerprint", db.toString())).isEqualTo(Helper.FOUND);
    String layout =
        com.michaelgrundvig.frc.spotter.json.Json.compact(
            Objects.requireNonNull(
                settings.rows("global").stream()
                    .filter(row -> row.key().equals("fieldLayout"))
                    .findFirst()
                    .orElseThrow()
                    .columns()
                    .get("contents")));
    assertThat(printed().strip())
        .isEqualTo(LayoutFingerprint.ofJson(layout))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void theVersionIsTheJarsOwn() throws Exception {
    Path classes = dir.resolve("classes");
    Path source = dir.resolve("src/org/photonvision/PhotonVersion.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        "package org.photonvision; public final class PhotonVersion {"
            + " public static final long buildTime = 1L;"
            + " public static final String versionString = \"v2027.0.0-alpha-2\";"
            + " public static final boolean isRelease = false; }");
    assertThat(
            ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", classes.toString(), source.toString()))
        .isZero();
    Path jar = dir.resolve("photonvision.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new JarEntry(PhotonVisionJar.VERSION_CLASS));
      out.write(Files.readAllBytes(classes.resolve(PhotonVisionJar.VERSION_CLASS)));
      out.closeEntry();
    }
    assertThat(run("version", jar.toString())).isEqualTo(Helper.FOUND);
    assertThat(printed().strip()).isEqualTo("v2027.0.0-alpha-2");

    Path other = dir.resolve("other.jar");
    try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(other))) {
      out.putNextEntry(new JarEntry("x.txt"));
      out.closeEntry();
    }
    assertThat(run("version", other.toString())).isEqualTo(Helper.FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("is it PhotonVision's jar?");
  }

  @Test
  void aCameraIsPresentAtThePortItsSettingsMatchItBy() throws IOException {
    Map<String, String> paths =
        Database.usbPaths(new Database(1 << 25, 1 << 24, 1 << 22).read(db).orElseThrow());
    assertThat(paths).isNotEmpty();
    String name = paths.keySet().iterator().next();
    String path = Objects.requireNonNull(paths.get(name));
    assertThat(run("camera", db.toString(), name)).isEqualTo(Helper.NOT_FOUND);
    assertThat(printed().strip()).isEqualTo("missing " + path);

    // Plugged in: a by-path link to its video device, and that device's USB parent in sysfs.
    Path root = dir.resolve("root");
    Path node = root.resolve("dev/video0");
    Files.createDirectories(node.getParent());
    Files.writeString(node, "");
    Path byPath = root.resolve(path.substring(1));
    Files.createDirectories(byPath.getParent());
    Files.createSymbolicLink(byPath, node);
    Path usb = root.resolve("sys/devices/platform/usb3/3-1");
    Files.createDirectories(usb.resolve("3-1:1.0"));
    Files.writeString(usb.resolve("speed"), "480\n");
    Files.writeString(usb.resolve("idVendor"), "0c45\n");
    Path classEntry = root.resolve("sys/class/video4linux/video0");
    Files.createDirectories(classEntry);
    Files.createSymbolicLink(classEntry.resolve("device"), usb.resolve("3-1:1.0"));
    assertThat(run("camera", db.toString(), name)).isEqualTo(Helper.FOUND);
    assertThat(printed().strip()).isEqualTo("present " + path + " 480 Mb/s");

    assertThat(run("camera", db.toString(), "nobody")).isEqualTo(Helper.NOT_FOUND);
    assertThat(printed()).startsWith("unknown camera nobody");
  }

  @Test
  void theSettingsAreSentAsJsonAndAsAZipWithEveryFilesHashLast() throws IOException {
    assertThat(run("settings-json", db.toString())).isEqualTo(Helper.FOUND);
    assertThat(Settings.parse(printed())).isEqualTo(settings);

    assertThat(run("settings-zip", db.toString(), "vision-front")).isEqualTo(Helper.FOUND);
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        files.put(entry.getName(), in.readAllBytes());
      }
    }
    List<String> names = List.copyOf(files.keySet());
    assertThat(names.get(names.size() - 1)).isEqualTo("coprocessors/vision-front/settings.sha256");
    String sums =
        new String(
            files.remove("coprocessors/vision-front/settings.sha256"), StandardCharsets.UTF_8);
    Map<String, String> expected = new TreeMap<>();
    SettingsFiles.render(settings)
        .forEach((path, text) -> expected.put("coprocessors/vision-front/settings/" + path, text));
    Map<String, String> got = new TreeMap<>();
    files.forEach((name, bytes) -> got.put(name, new String(bytes, StandardCharsets.UTF_8)));
    assertThat(got).isEqualTo(expected);
    for (String line : sums.lines().toList()) {
      String[] parts = line.split("  ", 2);
      byte[] content = Objects.requireNonNull(files.get(parts[1]), parts[1]);
      assertThat(HexFormat.of().formatHex(sha256(content))).as(parts[1]).isEqualTo(parts[0]);
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void settingsTooLargeToReadAreRefusedAndSaySo() {
    for (Database bounds :
        List.of(
            new Database(4096, 1 << 24, 1 << 22),
            new Database(1 << 25, 2000, 1 << 22),
            new Database(1 << 25, 1 << 24, 1500),
            new Database(1 << 25, 1 << 24, 500))) {
      assertThat(run(new Helper(bounds, dir), "hash", db.toString()))
          .as("%s", bounds)
          .isEqualTo(Helper.FAILED);
      assertThat(err.toString(StandardCharsets.UTF_8)).containsAnyOf("more than the", "too big");
    }
  }

  @Test
  void settingsDbBuildsTheDatabaseFromCommittedRowsAndPrintsTheirHash() throws Exception {
    Path rows = dir.resolve("coprocessors/vision-front/settings");
    SettingsFiles.write(settings, rows);
    Path empty = PhotonVisionDatabase.empty(dir.resolve("empty"));
    Path built = dir.resolve("photon.sqlite");
    assertThat(run("settings-db", rows.toString(), empty.toString(), built.toString()))
        .isEqualTo(Helper.FOUND);
    assertThat(printed().strip()).isEqualTo(settings.hash());
    try (Connection connection = PhotonVisionDatabase.open(built)) {
      assertThat(SettingsDatabase.read(connection)).isEqualTo(settings);
    }
    assertThat(
            run("settings-db", dir.resolve("none").toString(), empty.toString(), built.toString()))
        .isEqualTo(Helper.FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("there are no settings in");
    assertThat(run("settings-db", rows.toString(), dir.resolve("x").toString(), built.toString()))
        .isEqualTo(Helper.FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("there's no database at");
  }

  @Test
  void aWrongCommandLineShowsTheUsage() {
    assertThat(run()).isEqualTo(Helper.USAGE);
    assertThat(run("hash")).isEqualTo(Helper.USAGE);
    assertThat(run("reboot", "now")).isEqualTo(Helper.USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Usage:");
  }

  @Test
  void aLayoutIsFoundByEitherOfItsNamesAndNoneIsSaid() {
    String layout = "{\"tags\": []}";
    assertThat(
            Database.layout(
                new SettingsText(
                    2,
                    List.of(
                        new SettingsText.Row(
                            "global", "apriltagFieldLayout", Map.of("contents", layout))))))
        .contains(layout);
    assertThat(Database.layout(new SettingsText(2, List.of()))).isEmpty();
    Path empty;
    try {
      empty = PhotonVisionDatabase.empty(dir.resolve("blank"));
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    assertThat(run("fingerprint", empty.toString())).isEqualTo(Helper.NOT_FOUND);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("uses WPILib's default");
  }
}
