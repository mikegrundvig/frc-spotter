package com.michaelgrundvig.frc.spotter.manager;

/** A value's level, judged by its limits (or, for a status, by the script that sent it). */
public enum Level {
  /** Within its limits, or with none. */
  OK,
  /** Its {@code warn} limit matched, or its status is a warning. */
  WARNING,
  /** Its {@code fail} limit matched, or its status is failing. */
  FAILING,
  /**
   * It has no value (or a status with no level), and no {@code missing} rule makes that a warning
   * or a failure: no alert.
   */
  UNAVAILABLE
}
