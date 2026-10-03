package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.List;

/**
 * Every value's latest, by its index (its place in the description), and when each last changed: a
 * running count of changes, so a reader asks for what changed since the count it last saw. Until a
 * collector first runs, its values are unavailable, never made up.
 */
final class ValueStore {
  /** Why a value has none yet. */
  static final String NOT_YET = "not collected yet";

  private final Spotter.FieldValue[] values;
  private final long[] changed;
  private final Runnable onChange;
  private long changes;

  ValueStore(int size) {
    this(size, () -> {});
  }

  /**
   * @param onChange told, outside the store's lock, whenever a set changed a value
   */
  ValueStore(int size, Runnable onChange) {
    values = new Spotter.FieldValue[size];
    changed = new long[size];
    this.onChange = onChange;
    for (int i = 0; i < size; i++) {
      values[i] = Fill.unavailable(NOT_YET).setIndex(i);
    }
  }

  /** How many values there are. */
  int size() {
    return values.length;
  }

  /** Sets values from {@code first} on, in order, counting those that changed. */
  void set(int first, List<Spotter.FieldValue> filled) {
    boolean any = false;
    synchronized (this) {
      for (int i = 0; i < filled.size(); i++) {
        int index = first + i;
        Spotter.FieldValue value = filled.get(i).clone().setIndex(index);
        if (!value.equals(values[index])) {
          values[index] = value;
          changed[index] = ++changes;
          any = true;
        }
      }
    }
    if (any) {
      onChange.run();
    }
  }

  /** How many changes there have been. */
  synchronized long changes() {
    return changes;
  }

  /** One value as it is now. */
  synchronized Spotter.FieldValue get(int index) {
    return values[index].clone();
  }

  /**
   * The values that changed after the count {@code after}, or every value, as one {@code Values}.
   *
   * @param after a count from {@link #changes}; every value when negative
   * @param revision the description the indexes belong to
   * @param nanos the time, on the monotonic clock
   */
  synchronized Spotter.Values since(long after, int revision, long nanos) {
    return delta(after, revision, nanos).values();
  }

  /**
   * What changed after a count, and the count it brings the reader to, read together.
   *
   * @param values the values, as {@link #since} answers them
   * @param changes the count they bring the reader to
   */
  record Delta(Spotter.Values values, long changes) {}

  /** What changed after the count {@code after} (every value when negative), and the count now. */
  synchronized Delta delta(long after, int revision, long nanos) {
    Spotter.Values message =
        Spotter.Values.newInstance()
            .setRevision(revision)
            .setTimeNanos(nanos)
            .setComplete(after < 0);
    for (int i = 0; i < values.length; i++) {
      if (after < 0 || changed[i] > after) {
        message.addValues(values[i]);
      }
    }
    return new Delta(message, changes);
  }
}
