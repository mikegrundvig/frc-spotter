package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;

/**
 * A field's limits, in the grammar its pack declares them in ({@code spotter.proto}'s {@code
 * Limit}): robot code's override of a field's own, by its id ({@link Settings#withLimits}). An
 * override replaces both of the field's levels; one left empty is no limit at that level.
 *
 * <pre>{@code
 * new Limits(Limits.below(15), Limits.below(5).setMissing(true))   // missing fails too
 * new Limits(Limits.notEqualTo("tracking"), Limits.none())
 * }</pre>
 *
 * <p>Each is copied as it's given and as it's read, as QuickBuffers' messages can be changed.
 *
 * @param warn the {@code warn} level's comparisons
 * @param fail the {@code fail} level's comparisons
 */
public record Limits(Spotter.Limit warn, Spotter.Limit fail) {
  /** No limits at either level. */
  public static final Limits NONE =
      new Limits(Spotter.Limit.newInstance(), Spotter.Limit.newInstance());

  public Limits {
    warn = warn.clone();
    fail = fail.clone();
  }

  /** No comparison: no limit at a level. */
  public static Spotter.Limit none() {
    return Spotter.Limit.newInstance();
  }

  /** Above a number. */
  public static Spotter.Limit above(double number) {
    return Spotter.Limit.newInstance().setAbove(number);
  }

  /** Below a number. */
  public static Spotter.Limit below(double number) {
    return Spotter.Limit.newInstance().setBelow(number);
  }

  /** Equal to a number. */
  public static Spotter.Limit equalTo(double number) {
    return Spotter.Limit.newInstance().setEquals(Spotter.Scalar.newInstance().setNumber(number));
  }

  /** Equal to text. */
  public static Spotter.Limit equalTo(String text) {
    return Spotter.Limit.newInstance().setEquals(Spotter.Scalar.newInstance().setText(text));
  }

  /** Equal to a boolean. */
  public static Spotter.Limit equalTo(boolean flag) {
    return Spotter.Limit.newInstance().setEquals(Spotter.Scalar.newInstance().setFlag(flag));
  }

  /** Anything but a number. */
  public static Spotter.Limit notEqualTo(double number) {
    return Spotter.Limit.newInstance().setNotEquals(Spotter.Scalar.newInstance().setNumber(number));
  }

  /** Anything but this text. */
  public static Spotter.Limit notEqualTo(String text) {
    return Spotter.Limit.newInstance().setNotEquals(Spotter.Scalar.newInstance().setText(text));
  }

  /** Anything but a boolean. */
  public static Spotter.Limit notEqualTo(boolean flag) {
    return Spotter.Limit.newInstance().setNotEquals(Spotter.Scalar.newInstance().setFlag(flag));
  }

  /** Unavailable, or empty text; add it to any other with {@code .setMissing(true)}. */
  public static Spotter.Limit missing() {
    return Spotter.Limit.newInstance().setMissing(true);
  }

  /** The {@code warn} level's comparisons: a copy. */
  @Override
  public Spotter.Limit warn() {
    return warn.clone();
  }

  /** The {@code fail} level's comparisons: a copy. */
  @Override
  public Spotter.Limit fail() {
    return fail.clone();
  }
}
