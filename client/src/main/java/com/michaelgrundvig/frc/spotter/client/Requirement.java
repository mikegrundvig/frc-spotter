package com.michaelgrundvig.frc.spotter.client;

/**
 * Something the robot needs of a coprocessor, as one of its probes reports it: that the probe
 * passes, or that its value is what the robot expects (the vision software's version equals the one
 * the robot code was built against, say). The robot declares them; the agent only reports, and
 * knows nothing of what the robot needs.
 *
 * @param probe the probe's id, as the computer's packs define it
 * @param equals the value it must report; empty when passing is all that's needed
 * @param level how much it matters when it isn't met
 * @param what what it means, for people: "the front-left camera is plugged in"
 */
public record Requirement(String probe, String equals, Level level, String what) {
  /** How much an unmet requirement matters: the robot program's alert levels map onto these. */
  public enum Level {
    /** It can't do its job: the wrong version, a camera missing in a match. */
    HIGH,
    /** Worth fixing soon: settings that differ from what's committed. */
    MEDIUM,
    /** Worth knowing. */
    LOW
  }

  public Requirement {
    if (probe.isEmpty()) {
      throw new IllegalArgumentException("a requirement names a probe");
    }
  }

  /** The probe must pass. */
  public static Requirement passes(String probe, Level level, String what) {
    return new Requirement(probe, "", level, what);
  }

  /** The probe must pass and report this value. */
  public static Requirement equals(String probe, String value, Level level, String what) {
    return new Requirement(probe, value, level, what);
  }
}
