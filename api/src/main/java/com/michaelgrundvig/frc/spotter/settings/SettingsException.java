package com.michaelgrundvig.frc.spotter.settings;

/** Settings that can't be read or written, saying what and where. */
public class SettingsException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** A problem with settings. */
  public SettingsException(String message) {
    super(message);
  }

  /** A problem with settings, caused by another. */
  public SettingsException(String message, Throwable cause) {
    super(message, cause);
  }
}
