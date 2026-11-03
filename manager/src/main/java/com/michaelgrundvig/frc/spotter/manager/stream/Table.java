package com.michaelgrundvig.frc.spotter.manager.stream;

import com.michaelgrundvig.frc.spotter.manager.Alert;
import com.michaelgrundvig.frc.spotter.manager.limits.Field;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.List;

/**
 * Everything the manager knows of one board, as one buffer: its description, its values, and how
 * it's reached. A board's thread keeps one up to date, and copies it into another to publish; the
 * loop reads the one it took (see {@link Exchange}). Copying moves references and numbers only,
 * except when the description changed, so it allocates nothing in steady state.
 */
public final class Table {
  /** A board's description before it's described itself. */
  public static final Spotter.Description NONE = Spotter.Description.newInstance();

  public Spotter.Description description = NONE;
  public Reading[] values = new Reading[0];

  /** The agent's protocol version, from its last answer; empty before one. */
  public String protocol = "";

  /** Whether its last answer was another major version, or none. */
  public boolean otherProtocol;

  /** Why the last attempt to reach it, or its stream, failed; empty while its stream is open. */
  public String why = "";

  /** Whether it's been heard from: an answer, or an event. */
  public boolean heard;

  /** When it was last heard from, on the robot's clock. */
  public long heardNanos;

  /** How many times its description or a value has changed. */
  public long changes;

  /** One alert per value at warning or failing, in the description's order. */
  public List<Alert> alerts = List.of();

  /** What its packs' alert says, when they differ from the robot's; empty when there's none. */
  public String packs = "";

  /** Its description's problems: what its agent ignored, and why. */
  public List<String> problems = List.of();

  /** What its problems' alert says, while it has any; empty when there are none. */
  public String problemsAlert = "";

  /**
   * Why its description wasn't taken (it has more than the manager takes), as its alert says it;
   * empty when it was.
   */
  public String refused = "";

  /** Takes a new description: its values, none sent yet. */
  public void describe(Spotter.Description described, Field[] fields) {
    description = described;
    values = new Reading[fields.length];
    for (int i = 0; i < fields.length; i++) {
      values[i] = new Reading(fields[i]);
    }
  }

  /** Takes another's state: everything, its values' structure only when its description differs. */
  public void copyFrom(Table other) {
    if (description != other.description) {
      description = other.description;
      values = new Reading[other.values.length];
      for (int i = 0; i < values.length; i++) {
        values[i] = new Reading(other.values[i].field);
      }
    }
    for (int i = 0; i < values.length; i++) {
      values[i].copyFrom(other.values[i]);
    }
    protocol = other.protocol;
    otherProtocol = other.otherProtocol;
    why = other.why;
    heard = other.heard;
    heardNanos = other.heardNanos;
    changes = other.changes;
    alerts = other.alerts;
    packs = other.packs;
    problems = other.problems;
    problemsAlert = other.problemsAlert;
    refused = other.refused;
  }
}
