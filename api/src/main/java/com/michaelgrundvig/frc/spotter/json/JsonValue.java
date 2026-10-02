package com.michaelgrundvig.frc.spotter.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A JSON value, parsed ({@link Json#parse}) or built. Objects keep their members in the order they
 * were read or added; numbers keep the text they were written as, so a value read and written again
 * is unchanged, digit for digit.
 *
 * <p>Reading a record from one is tolerant in one direction only: a member that's missing (or
 * {@code null}) takes a default, and a member nobody asked for is ignored, so an agent and a robot
 * built a version apart still understand each other; a member of the wrong kind is an error.
 */
public sealed interface JsonValue
    permits JsonValue.Obj,
        JsonValue.Arr,
        JsonValue.Str,
        JsonValue.Num,
        JsonValue.Bool,
        JsonValue.Null {

  /** JSON's {@code null}. */
  Null NULL = new Null();

  /** {@code true}. */
  Bool TRUE = new Bool(true);

  /** {@code false}. */
  Bool FALSE = new Bool(false);

  /** A string. */
  static Str of(String value) {
    return new Str(value);
  }

  /** A whole number. */
  static Num of(long value) {
    return new Num(Long.toString(value));
  }

  /**
   * A number, written as briefly as it reads back exactly: {@code 45} for 45.0, {@code 0.1} for
   * 0.1. Not a number or infinite is {@code null}, which JSON can say.
   */
  static JsonValue of(double value) {
    if (!Double.isFinite(value)) {
      return NULL;
    }
    if (value == Math.rint(value) && Math.abs(value) < 1e15) {
      return new Num(Long.toString((long) value));
    }
    return new Num(Double.toString(value));
  }

  /** {@code true} or {@code false}. */
  static Bool of(boolean value) {
    return value ? TRUE : FALSE;
  }

  /** An array of strings. */
  static Arr strings(List<String> values) {
    return new Arr(values.stream().<JsonValue>map(Str::new).toList());
  }

  /** An array of the values each item turns into. */
  static <T> Arr array(List<T> items, Function<? super T, ? extends JsonValue> toJson) {
    return new Arr(items.stream().<JsonValue>map(toJson).toList());
  }

  /** What kind of value this is, for messages: "an object", "a string", and so on. */
  String kind();

  /** This value as an object, or an error naming {@code what} it was read as. */
  default Obj asObject(String what) {
    if (this instanceof Obj object) {
      return object;
    }
    throw new JsonException(what + ": expected an object, found " + kind());
  }

  /** This value as an array, or an error naming {@code what} it was read as. */
  default Arr asArray(String what) {
    if (this instanceof Arr array) {
      return array;
    }
    throw new JsonException(what + ": expected an array, found " + kind());
  }

  /** This value as a string, or an error naming {@code what} it was read as. */
  default String asString(String what) {
    if (this instanceof Str string) {
      return string.value();
    }
    throw new JsonException(what + ": expected a string, found " + kind());
  }

  /** This value as a number, or an error naming {@code what} it was read as. */
  default double asDouble(String what) {
    if (this instanceof Num number) {
      return number.doubleValue();
    }
    throw new JsonException(what + ": expected a number, found " + kind());
  }

  /** This value as a whole number, or an error naming {@code what} it was read as. */
  default long asLong(String what) {
    if (this instanceof Num number) {
      return number.longValue(what);
    }
    throw new JsonException(what + ": expected a number, found " + kind());
  }

  /** An object: members by name, in order. */
  record Obj(Map<String, JsonValue> members) implements JsonValue {
    /** An object with no members. */
    public static final Obj EMPTY = new Obj(Map.of());

    public Obj {
      members = Members.copyOf(members);
    }

    /** Starts an object, its members in the order they're added. */
    public static Builder builder() {
      return new Builder();
    }

    @Override
    public String kind() {
      return "an object";
    }

    /** The member, or null when it's missing. */
    public @Nullable JsonValue get(String name) {
      return members.get(name);
    }

    /** The member as a string; {@code fallback} when it's missing or null. */
    public String string(String name, String fallback) {
      JsonValue value = members.get(name);
      return value == null || value instanceof Null ? fallback : value.asString(name);
    }

    /** The member as a whole number; {@code fallback} when it's missing or null. */
    public long integer(String name, long fallback) {
      JsonValue value = members.get(name);
      if (value == null || value instanceof Null) {
        return fallback;
      }
      if (value instanceof Num number) {
        return number.longValue(name);
      }
      throw new JsonException(name + ": expected a number, found " + value.kind());
    }

    /** The member as a whole number that fits an int; {@code fallback} when missing or null. */
    public int integer(String name, int fallback) {
      long value = integer(name, (long) fallback);
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new JsonException(name + ": " + value + " is out of range");
      }
      return (int) value;
    }

    /** The member as a number; {@code fallback} when it's missing or null. */
    public double number(String name, double fallback) {
      JsonValue value = members.get(name);
      if (value == null || value instanceof Null) {
        return fallback;
      }
      if (value instanceof Num number) {
        return number.doubleValue();
      }
      throw new JsonException(name + ": expected a number, found " + value.kind());
    }

    /** The member as true or false; {@code fallback} when it's missing or null. */
    public boolean bool(String name, boolean fallback) {
      JsonValue value = members.get(name);
      if (value == null || value instanceof Null) {
        return fallback;
      }
      if (value instanceof Bool bool) {
        return bool.value();
      }
      throw new JsonException(name + ": expected true or false, found " + value.kind());
    }

    /** The member as true, false, or null (unknown); null when it's missing. */
    public @Nullable Boolean optionalBool(String name) {
      JsonValue value = members.get(name);
      return value == null || value instanceof Null ? null : bool(name, false);
    }

    /** The member as an object; null when it's missing or null. */
    public @Nullable Obj object(String name) {
      JsonValue value = members.get(name);
      return value == null || value instanceof Null ? null : value.asObject(name);
    }

    /** The member as an object; an empty object when it's missing or null. */
    public Obj objectOrEmpty(String name) {
      Obj object = object(name);
      return object == null ? EMPTY : object;
    }

    /** The member's items; none when it's missing or null. */
    public List<JsonValue> items(String name) {
      JsonValue value = members.get(name);
      return value == null || value instanceof Null ? List.of() : value.asArray(name).items();
    }

    /** The member's items, each read by {@code read}; none when it's missing or null. */
    public <T> List<T> list(String name, Function<JsonValue, T> read) {
      List<T> list = new ArrayList<>();
      for (JsonValue item : items(name)) {
        list.add(read.apply(item));
      }
      return List.copyOf(list);
    }

    /** The member's items as strings; none when it's missing or null. */
    public List<String> strings(String name) {
      return list(name, item -> item.asString(name + "[]"));
    }

    /** Builds an object, members in the order added. */
    public static final class Builder {
      private final Map<String, JsonValue> members = new LinkedHashMap<>();

      private Builder() {}

      /** Adds a member, replacing one of the same name. */
      public Builder put(String name, JsonValue value) {
        members.put(name, value);
        return this;
      }

      /** Adds a string member. */
      public Builder put(String name, String value) {
        return put(name, JsonValue.of(value));
      }

      /** Adds a whole-number member. */
      public Builder put(String name, long value) {
        return put(name, JsonValue.of(value));
      }

      /** Adds a number member ({@code null} when not finite). */
      public Builder put(String name, double value) {
        return put(name, JsonValue.of(value));
      }

      /** Adds a true-or-false member. */
      public Builder put(String name, boolean value) {
        return put(name, JsonValue.of(value));
      }

      /** Adds a member that's true, false, or null (unknown). */
      public Builder putOptional(String name, @Nullable Boolean value) {
        return put(name, value == null ? NULL : JsonValue.of(value.booleanValue()));
      }

      /** Adds an array-of-strings member. */
      public Builder put(String name, List<String> values) {
        return put(name, JsonValue.strings(values));
      }

      /** The object. */
      public Obj build() {
        return new Obj(members);
      }
    }
  }

  /** An array. */
  record Arr(List<JsonValue> items) implements JsonValue {
    public Arr {
      items = List.copyOf(items);
    }

    @Override
    public String kind() {
      return "an array";
    }
  }

  /** A string. */
  record Str(String value) implements JsonValue {
    @Override
    public String kind() {
      return "a string";
    }
  }

  /**
   * A number, as the text it was written as: {@code 1.0} and {@code 1} are different texts, so a
   * value read and written again comes back exactly as it was.
   */
  record Num(String text) implements JsonValue {
    private static final Pattern GRAMMAR =
        Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    public Num {
      if (text.length() > Json.MAX_NUMBER) {
        throw new JsonException("a number longer than " + Json.MAX_NUMBER + " characters");
      }
      if (!GRAMMAR.matcher(text).matches()) {
        throw new JsonException("not a number: " + text);
      }
      int exponent = Math.max(text.indexOf('e'), text.indexOf('E'));
      if (exponent >= 0) {
        String digits = text.substring(exponent + 1).replaceFirst("^[+-]", "");
        if (digits.length() > Json.MAX_EXPONENT_DIGITS) {
          throw new JsonException(
              "a number's exponent of more than " + Json.MAX_EXPONENT_DIGITS + " digits: " + text);
        }
      }
    }

    @Override
    public String kind() {
      return "a number";
    }

    /** The number as a double (the nearest one). */
    public double doubleValue() {
      return Double.parseDouble(text);
    }

    /** The number exactly. */
    public BigDecimal decimalValue() {
      try {
        return new BigDecimal(text);
      } catch (NumberFormatException e) {
        throw new JsonException("number out of range: " + text);
      }
    }

    /** The number as a whole number, or an error naming {@code what} it was read as. */
    public long longValue(String what) {
      try {
        return decimalValue().longValueExact();
      } catch (ArithmeticException e) {
        throw new JsonException(what + ": expected a whole number, found " + text);
      }
    }
  }

  /** {@code true} or {@code false}. */
  record Bool(boolean value) implements JsonValue {
    @Override
    public String kind() {
      return value ? "true" : "false";
    }
  }

  /** {@code null}. */
  record Null() implements JsonValue {
    @Override
    public String kind() {
      return "null";
    }
  }
}
