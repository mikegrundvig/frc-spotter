package com.michaelgrundvig.frc.spotter.manager.limits;

import com.michaelgrundvig.frc.spotter.manager.Level;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import us.hebi.quickbuf.Utf8String;

/**
 * One value as the manager keeps it while a board's thread updates it, in the buffers it trades
 * with the robot loop ({@link com.michaelgrundvig.frc.spotter.manager.stream.Table}): what it
 * holds, its level by its limits, and why. Robot code never sees one: each update copies it into
 * the board's {@link com.michaelgrundvig.frc.spotter.manager.Value}, which is what robot code
 * reads. A run's response is judged the same way, into readings, then values.
 */
public final class Reading {
  /** What it holds: one of {@code spotter.proto}'s {@code FieldValue} kinds. */
  public enum Kind {
    /** Nothing sent yet. */
    NONE,
    NUMBER,
    TEXT,
    FLAG,
    STATUS,
    UNAVAILABLE
  }

  /** Why a value has none before the agent first sends it. */
  public static final String NOT_SENT = "not sent yet";

  public final Field field;
  public Kind kind = Kind.NONE;
  public double number = Double.NaN;
  public boolean flag;
  public Level status = Level.UNAVAILABLE;
  public String text = NOT_SENT;
  public long nanos;
  public long changed;
  public Level level = Level.UNAVAILABLE;
  public String reason = NOT_SENT;

  public Reading(Field field) {
    this.field = field;
  }

  /** Its id: {@code pack.collector.field}, or a response field's name. */
  public String id() {
    return field.id;
  }

  /** Its label, in its pack's words; its id when it has none. */
  public String label() {
    return field.label;
  }

  /** Whether it has a value. */
  public boolean available() {
    return kind != Kind.NONE && kind != Kind.UNAVAILABLE;
  }

  /**
   * Takes a value as the board sent it: whether it changed. Text is decoded only when its bytes
   * differ from what it holds ({@code raw}, its bytes as last taken).
   */
  public boolean take(Utf8String raw, Spotter.FieldValue sent) {
    if (sent.hasNumber()) {
      double now = sent.getNumber();
      if (kind == Kind.NUMBER && Double.compare(now, number) == 0) {
        return false;
      }
      kind = Kind.NUMBER;
      number = now;
      return true;
    }
    if (sent.hasFlag()) {
      boolean now = sent.getFlag();
      if (kind == Kind.FLAG && now == flag) {
        return false;
      }
      kind = Kind.FLAG;
      flag = now;
      return true;
    }
    if (sent.hasText()) {
      return text(this, raw, Kind.TEXT, sent.getTextBytes(), Level.UNAVAILABLE);
    }
    if (sent.hasStatus()) {
      Spotter.Status status = sent.getStatus();
      return text(this, raw, Kind.STATUS, status.getMessageBytes(), level(status.getLevelValue()));
    }
    if (sent.hasUnavailable()) {
      return text(this, raw, Kind.UNAVAILABLE, sent.getUnavailableBytes(), Level.UNAVAILABLE);
    }
    if (sent.hasJson()) {
      return text(this, raw, Kind.TEXT, sent.getJsonBytes(), Level.UNAVAILABLE);
    }
    if (sent.hasFileUrl()) {
      return text(this, raw, Kind.TEXT, sent.getFileUrlBytes(), Level.UNAVAILABLE);
    }
    if (kind == Kind.UNAVAILABLE && text.equals(NO_VALUE)) {
      return false;
    }
    raw.clear();
    kind = Kind.UNAVAILABLE;
    text = NO_VALUE;
    return true;
  }

  /** Why a value sent with none of its kinds set has no value. */
  static final String NO_VALUE = "sent without a value";

  /**
   * Takes text (or a status's message, or why there's none): decoded only when its bytes differ.
   */
  private static boolean text(
      Reading value, Utf8String raw, Kind kind, Utf8String bytes, Level status) {
    if (value.kind == kind && value.status == status && raw.equals(bytes)) {
      return false;
    }
    raw.copyFrom(bytes);
    value.kind = kind;
    value.status = status;
    value.text = bytes.getString();
    return true;
  }

  /** A status's level, from {@code spotter.proto}'s; a status with none is unavailable. */
  private static Level level(int sent) {
    switch (sent) {
      case Spotter.Level.LEVEL_OK_VALUE:
        return Level.OK;
      case Spotter.Level.LEVEL_WARNING_VALUE:
        return Level.WARNING;
      case Spotter.Level.LEVEL_FAILING_VALUE:
        return Level.FAILING;
      default:
        return Level.UNAVAILABLE;
    }
  }

  /** Why it's at its level, its label first: {@code "CPU temperature above 80 °C"}. */
  public String why() {
    String label = label();
    if (kind == Kind.STATUS && status != Level.UNAVAILABLE) {
      return label + ": " + reason;
    }
    if (!available() || kind == Kind.STATUS) {
      return label + " unavailable: " + reason;
    }
    return label + " " + reason;
  }

  /** Takes another's state: a buffer brought up to date. */
  public void copyFrom(Reading other) {
    kind = other.kind;
    number = other.number;
    flag = other.flag;
    status = other.status;
    text = other.text;
    nanos = other.nanos;
    changed = other.changed;
    level = other.level;
    reason = other.reason;
  }
}
