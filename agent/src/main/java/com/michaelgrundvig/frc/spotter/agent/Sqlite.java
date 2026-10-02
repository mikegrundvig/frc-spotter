package com.michaelgrundvig.frc.spotter.agent;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.sqlite.SQLiteConfig;

/** SQLite connections, through xerial's sqlite-jdbc: the agent's only use of the driver. */
final class Sqlite {
  /** How long to wait for PhotonVision to finish a write before giving up, milliseconds. */
  static final int BUSY_TIMEOUT = 2000;

  private Sqlite() {}

  /**
   * A read-only connection: the agent never writes PhotonVision's live database. SQLite itself
   * refuses any string or row longer than twice {@code maxValue} bytes (SQLITE_LIMIT_LENGTH), so a
   * hostile database can't make the agent hold a huge value even once.
   */
  static Connection openReadOnly(Path file, int maxValue) throws SQLException {
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
}
