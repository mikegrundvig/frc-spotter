package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;

/**
 * One of a board's values, as of the last {@link Manager#update}: what it is, its level by its
 * limits, and why. Its id is {@code pack.collector.field}, such as {@code debian.memory.available}.
 *
 * <p>The manager reuses it: read it in the loop that got it, and keep what's needed, never the
 * value itself, past the next {@link Manager#update}.
 */
public final class Value {
  /** What it holds: one of {@code spotter.proto}'s {@code FieldValue} kinds. */
  enum Kind {
    /** Nothing sent yet. */
    NONE,
    NUMBER,
    TEXT,
    FLAG,
    STATUS,
    UNAVAILABLE
  }

  /** Why a value has none before the agent first sends it. */
  static final String NOT_SENT = "not sent yet";

  final Field field;
  Kind kind = Kind.NONE;
  double number = Double.NaN;
  boolean flag;
  Level status = Level.UNAVAILABLE;
  String text = NOT_SENT;
  long nanos;
  long changed;
  Level level = Level.UNAVAILABLE;
  String reason = NOT_SENT;

  Value(Field field) {
    this.field = field;
  }

  /** Its id: {@code pack.collector.field}. */
  public String id() {
    return field.id;
  }

  /** Its label, in its pack's words; its id when it has none. */
  public String label() {
    return field.label;
  }

  /** Its type: a number, text, a boolean, or a status. */
  public Spotter.FieldType type() {
    return field.type;
  }

  /** A number's unit; empty when it has none. */
  public String unit() {
    return field.unit;
  }

  /** The limits it's judged by: robot code's override, else its pack's. */
  public Limits limits() {
    return field.limits();
  }

  /** Whether robot code overrode its pack's limits. */
  public boolean overridden() {
    return field.overridden;
  }

  /** Whether it has a value: if not, {@link #unavailable} says why. */
  public boolean available() {
    return kind != Kind.NONE && kind != Kind.UNAVAILABLE;
  }

  /** A number's value; NaN for anything else, or with no value. */
  public double number() {
    return kind == Kind.NUMBER ? number : Double.NaN;
  }

  /** Text's value, or a status's message; empty for anything else, or with no value. */
  public String text() {
    return kind == Kind.TEXT || kind == Kind.STATUS ? text : "";
  }

  /** A boolean's value; false for anything else, or with no value. */
  public boolean flag() {
    return kind == Kind.FLAG && flag;
  }

  /**
   * A status's level, the script's own verdict; {@link Level#UNAVAILABLE} for anything else, or
   * with no value.
   */
  public Level status() {
    return kind == Kind.STATUS ? status : Level.UNAVAILABLE;
  }

  /** Why it has no value, as the agent said; empty when it has one. */
  public String unavailable() {
    return available() ? "" : text;
  }

  /** Its level, by its limits, or a status's own. */
  public Level level() {
    return level;
  }

  /**
   * Why it's at its level, in its pack's words: the comparison that matched ({@code "below 15 %"}),
   * a status's message, or why it has no value; empty when it's ok.
   */
  public String reason() {
    return reason;
  }

  /**
   * When the agent last sent it, on the robot's clock: every value is sent again every 10 s, and
   * each as it changes.
   */
  public long nanos() {
    return nanos;
  }

  /**
   * The board's {@link Board#changes} count when it last changed: it changed since a loop saw the
   * count {@code n} if this is greater than {@code n}.
   */
  public long changed() {
    return changed;
  }

  /** Takes another's state: a buffer brought up to date. */
  void copyFrom(Value other) {
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

  @Override
  public String toString() {
    String shown;
    switch (kind) {
      case NUMBER:
        shown = Field.number(number, field.unit);
        break;
      case FLAG:
        shown = Boolean.toString(flag);
        break;
      case TEXT:
      case STATUS:
        shown = text;
        break;
      default:
        shown = "unavailable: " + text;
    }
    return field.id + " = " + shown + " (" + level + (reason.isEmpty() ? "" : ": " + reason) + ")";
  }
}
