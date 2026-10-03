package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;

/**
 * A field's limits, in the grammar its pack declares them in ({@code spotter.proto}'s {@code
 * Limit}): robot code's override of a field's own, by its id ({@link Settings#withLimits}). An
 * override replaces both of the field's levels; one left empty is no limit at that level.
 *
 * <pre>{@code
 * new Limits(
 *     Spotter.Limit.newInstance().setBelow(15),
 *     Spotter.Limit.newInstance().setBelow(5).setMissing(true))
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
