package com.michaelgrundvig.frc.spotter.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * PhotonVision's settings database for tests, made from its schema ({@code
 * photonvision/schema.sql}) and, if asked, an example configured coprocessor's rows ({@code
 * photonvision/rows.sql}).
 */
public final class PhotonVisionDatabase {
  private PhotonVisionDatabase() {}

  /** An empty database at PhotonVision's schema, as it is before PhotonVision first saves. */
  public static Path empty(Path folder) throws SQLException {
    try {
      Files.createDirectories(folder);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Path file = folder.resolve("photon.sqlite");
    try (Connection connection = open(file)) {
      run(connection, "photonvision/schema.sql");
    }
    return file;
  }

  /** A database with the example rows. */
  public static Path configured(Path folder) throws SQLException {
    Path file = empty(folder);
    try (Connection connection = open(file)) {
      run(connection, "photonvision/rows.sql");
    }
    return file;
  }

  /** A connection to a database file (made if it's missing). */
  public static Connection open(Path file) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
  }

  /** Runs a script of statements, each ending in a semicolon at the end of a line. */
  public static void run(Connection connection, String resource) throws SQLException {
    StringBuilder script = new StringBuilder();
    for (String line : text(resource).split("\n")) {
      if (!line.startsWith("--")) {
        script.append(line).append('\n');
      }
    }
    try (Statement statement = connection.createStatement()) {
      for (String sql : script.toString().split(";\n")) {
        if (!sql.isBlank()) {
          statement.executeUpdate(sql);
        }
      }
    }
  }

  private static String text(String resource) {
    try (InputStream in =
        Objects.requireNonNull(
            PhotonVisionDatabase.class.getClassLoader().getResourceAsStream(resource), resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
