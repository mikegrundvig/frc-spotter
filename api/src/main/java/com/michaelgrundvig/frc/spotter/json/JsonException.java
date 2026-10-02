package com.michaelgrundvig.frc.spotter.json;

/** JSON that couldn't be read: malformed text, or a member of the wrong kind. */
public class JsonException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** An error, saying what was wrong and where. */
  public JsonException(String message) {
    super(message);
  }
}
