package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the manager knows of one board, as one buffer: its description, its values, and how
 * it's reached. A board's thread keeps one up to date, and copies it into another to publish; the
 * loop reads the one it took (see {@link Exchange}). Copying moves references and numbers only,
 * except when the description changed, so it allocates nothing in steady state.
 */
final class Table {
  /** A board's description before it's described itself. */
  static final Spotter.Description NONE = Spotter.Description.newInstance();

  Spotter.Description description = NONE;
  Value[] values = new Value[0];
  List<Value> list = List.of();
  Map<String, Value> byId = Map.of();

  /** The agent's protocol version, from its last answer; empty before one. */
  String protocol = "";

  /** Whether its last answer was another major version, or none. */
  boolean otherProtocol;

  /** Why the last attempt to reach it, or its stream, failed; empty while its stream is open. */
  String why = "";

  /** Whether it's been heard from: an answer, or an event. */
  boolean heard;

  /** When it was last heard from, on the robot's clock. */
  long heardNanos;

  /** How many times its description or a value has changed. */
  long changes;

  /** One alert per value at warning or failing, in the description's order. */
  List<Alert> alerts = List.of();

  /** What its packs' alert says, when they differ from the robot's; empty when there's none. */
  String packs = "";

  /** Takes a new description: its values, none sent yet. */
  void describe(Spotter.Description described, Field[] fields) {
    description = described;
    values = new Value[fields.length];
    for (int i = 0; i < fields.length; i++) {
      values[i] = new Value(fields[i]);
    }
    index();
  }

  private void index() {
    list = Collections.unmodifiableList(Arrays.asList(values));
    Map<String, Value> ids = new HashMap<>();
    for (Value value : values) {
      ids.put(value.id(), value);
    }
    byId = Collections.unmodifiableMap(ids);
  }

  /** Takes another's state: everything, its values' structure only when its description differs. */
  void copyFrom(Table other) {
    if (description != other.description) {
      description = other.description;
      values = new Value[other.values.length];
      for (int i = 0; i < values.length; i++) {
        values[i] = new Value(other.values[i].field);
      }
      index();
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
  }
}
