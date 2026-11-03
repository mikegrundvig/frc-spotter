package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.manager.limits.Field;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;

/**
 * One of a board's values, as of the last {@link Manager#update}, or a field of a run's response:
 * what it is, its level by its limits, and why. A value's id is {@code pack.collector.field}, such
 * as {@code debian.memory.available}; a response field's is its name.
 *
 * <p>A board's value is the same object for its id for as long as the board is: each {@link
 * Manager#update} brings it up to date, so robot code looks it up once ({@link Board#value}) and
 * keeps it, and every read within a loop agrees. Read it from the robot loop's thread. A run's
 * response is its own, and doesn't change once the run is done.
 */
public final class Value {
  private Field field;
  private Reading.Kind kind = Reading.Kind.NONE;
  private double number = Double.NaN;
  private boolean flag;
  private Level status = Level.UNAVAILABLE;
  private String text = Reading.NOT_SENT;
  private long nanos;
  private long changed;
  private Level level = Level.UNAVAILABLE;
  private String reason = Reading.NOT_SENT;

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
    return kind != Reading.Kind.NONE && kind != Reading.Kind.UNAVAILABLE;
  }

  /** A number's value; NaN for anything else, or with no value. */
  public double number() {
    return kind == Reading.Kind.NUMBER ? number : Double.NaN;
  }

  /** Text's value, or a status's message; empty for anything else, or with no value. */
  public String text() {
    return kind == Reading.Kind.TEXT || kind == Reading.Kind.STATUS ? text : "";
  }

  /** A boolean's value; false for anything else, or with no value. */
  public boolean flag() {
    return kind == Reading.Kind.FLAG && flag;
  }

  /**
   * A status's level, the script's own verdict; {@link Level#UNAVAILABLE} for anything else, or
   * with no value.
   */
  public Level status() {
    return kind == Reading.Kind.STATUS ? status : Level.UNAVAILABLE;
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

  /** Its declaration, as a new description of the board's has it. */
  void bind(Field declared) {
    field = declared;
  }

  /** Brought up to date from the board's reading of it. */
  void copyFrom(Reading reading) {
    kind = reading.kind;
    number = reading.number;
    flag = reading.flag;
    status = reading.status;
    text = reading.text;
    nanos = reading.nanos;
    changed = reading.changed;
    level = reading.level;
    reason = reading.reason;
  }

  /** Has no value: the board has none by its id, as {@code "vision-front has no value x.y.z"}. */
  void absent(String board) {
    kind = Reading.Kind.NONE;
    number = Double.NaN;
    flag = false;
    status = Level.UNAVAILABLE;
    text = board + " has no value " + field.id;
    nanos = 0;
    level = Level.UNAVAILABLE;
    reason = text;
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
