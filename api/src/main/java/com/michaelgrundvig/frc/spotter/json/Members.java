package com.michaelgrundvig.frc.spotter.json;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An object's members, unchangeable, in order, in two arrays. A calibration is tens of thousands of
 * two-member objects (points), so this is a quarter the size of a LinkedHashMap, which matters to
 * an agent with 64 MB of heap. Lookups scan: objects in settings have tens of members at most.
 */
final class Members extends AbstractMap<String, JsonValue> {
  private final String[] names;
  private final JsonValue[] values;

  private Members(String[] names, JsonValue[] values) {
    this.names = names;
    this.values = values;
  }

  static Members copyOf(Map<String, JsonValue> members) {
    if (members instanceof Members already) {
      return already;
    }
    String[] names = new String[members.size()];
    JsonValue[] values = new JsonValue[members.size()];
    int i = 0;
    for (Map.Entry<String, JsonValue> member : members.entrySet()) {
      names[i] = member.getKey();
      values[i] = member.getValue();
      i++;
    }
    return new Members(names, values);
  }

  @Override
  public int size() {
    return names.length;
  }

  @Override
  public @Nullable JsonValue get(@Nullable Object name) {
    for (int i = 0; i < names.length; i++) {
      if (names[i].equals(name)) {
        return values[i];
      }
    }
    return null;
  }

  @Override
  public boolean containsKey(@Nullable Object name) {
    return get(name) != null;
  }

  @Override
  public Set<Map.Entry<String, JsonValue>> entrySet() {
    return new AbstractSet<>() {
      @Override
      public int size() {
        return names.length;
      }

      @Override
      public Iterator<Map.Entry<String, JsonValue>> iterator() {
        return new Iterator<>() {
          private int next;

          @Override
          public boolean hasNext() {
            return next < names.length;
          }

          @Override
          public Map.Entry<String, JsonValue> next() {
            if (next >= names.length) {
              throw new NoSuchElementException();
            }
            Map.Entry<String, JsonValue> entry =
                new AbstractMap.SimpleImmutableEntry<>(names[next], values[next]);
            next++;
            return entry;
          }
        };
      }
    };
  }
}
