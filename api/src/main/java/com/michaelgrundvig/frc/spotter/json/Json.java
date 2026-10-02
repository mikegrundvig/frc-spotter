package com.michaelgrundvig.frc.spotter.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes JSON, with nothing but the JDK, so the robot program, the coprocessor's agent,
 * and the build share one implementation whose output never changes under them.
 *
 * <p>Four ways to write a value:
 *
 * <ul>
 *   <li>{@link #compact}: members in their order, no spaces. What the agent sends.
 *   <li>{@link #pretty}: members sorted by name, two-space indents, a newline at the end. The form
 *       settings take in Git, so a diff shows exactly what changed. A container of plain values
 *       short enough fits on one line (a point, {@code {"x": 1.5, "y": 2.0}}).
 *   <li>{@link #sorted}: members sorted by name, no spaces.
 *   <li>{@link #hashable}: sorted, no spaces, and every number in one form ({@code 1}, {@code 1.0}
 *       and {@code 1e0} alike), so a hash doesn't change when a value is only rewritten.
 * </ul>
 *
 * <p>Names sort by their UTF-16 code units ({@link String#compareTo}), as RFC 8785 does. Strings
 * escape only what JSON requires (the quote, the backslash, control characters) and unpaired
 * surrogates; everything else is written as UTF-8 text.
 */
public final class Json {
  /** Deeper nesting than this is refused: no settings come close, and it bounds the parser. */
  public static final int MAX_DEPTH = 256;

  /**
   * The longest a number may be, in characters. Longer ones are refused: no setting needs them, and
   * exact arithmetic on a number takes time that grows with the square of its length.
   */
  public static final int MAX_NUMBER = 1000;

  /** The most digits a number's exponent may have: 9 keeps every scale an {@code int}. */
  public static final int MAX_EXPONENT_DIGITS = 9;

  /** The widest a container of plain values may be and still go on one line in {@link #pretty}. */
  static final int INLINE_WIDTH = 80;

  private Json() {}

  /** Parses JSON text: one value, with nothing after it but whitespace. */
  public static JsonValue parse(String text) {
    Parser parser = new Parser(text);
    parser.skipWhitespace();
    JsonValue value = parser.value(0);
    parser.skipWhitespace();
    if (parser.pos < text.length()) {
      throw parser.error("unexpected text after the value");
    }
    return value;
  }

  /** The value with its members in their order and no spaces. */
  public static String compact(JsonValue value) {
    StringBuilder out = new StringBuilder();
    new Writer(out, false, false, Drain.NONE).write(value);
    return out.toString();
  }

  /** The value with its members sorted by name and no spaces. */
  public static String sorted(JsonValue value) {
    StringBuilder out = new StringBuilder();
    new Writer(out, true, false, Drain.NONE).write(value);
    return out.toString();
  }

  /** The value sorted, with every number in one form: what a hash is computed over. */
  public static String hashable(JsonValue value) {
    StringBuilder out = new StringBuilder();
    new Writer(out, true, true, Drain.NONE).write(value);
    return out.toString();
  }

  /** The value sorted and indented, ending in a newline: the form a file in Git takes. */
  public static String pretty(JsonValue value) {
    StringBuilder out = new StringBuilder();
    new Pretty(out, Drain.NONE).write(value, 0);
    return out.append('\n').toString();
  }

  /**
   * Writes {@link #compact} to {@code out} as it goes, a few kilobytes at a time, rather than
   * building it all first: for answers too large to hold twice.
   */
  public static void writeCompact(JsonValue value, Appendable out) throws IOException {
    write(out, (buffer, drain) -> new Writer(buffer, false, false, drain).write(value));
  }

  /** Writes {@link #hashable} to {@code out} as it goes (see {@link #writeCompact}). */
  public static void writeHashable(JsonValue value, Appendable out) throws IOException {
    write(out, (buffer, drain) -> new Writer(buffer, true, true, drain).write(value));
  }

  /** Writes {@link #pretty} to {@code out} as it goes (see {@link #writeCompact}). */
  public static void writePretty(JsonValue value, Appendable out) throws IOException {
    write(
        out,
        (buffer, drain) -> {
          new Pretty(buffer, drain).write(value, 0);
          buffer.append('\n');
        });
  }

  private interface Writing {
    void write(StringBuilder buffer, Drain drain);
  }

  private static void write(Appendable out, Writing writing) throws IOException {
    StringBuilder buffer = new StringBuilder();
    Drain drain =
        full -> {
          try {
            out.append(full);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          full.setLength(0);
        };
    try {
      writing.write(buffer, drain);
      drain.drain(buffer);
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  /** Empties a writer's buffer into where it's going, once the buffer is full enough. */
  private interface Drain {
    /** Keeps everything in the buffer: for writing a String. */
    Drain NONE = buffer -> {};

    /** How full a buffer is before it's emptied, in characters. */
    int SIZE = 8192;

    void drain(StringBuilder buffer);

    default void maybe(StringBuilder buffer) {
      if (buffer.length() >= SIZE) {
        drain(buffer);
      }
    }
  }

  /**
   * A number's one form, for hashing: the same text for every way of writing the same value. A
   * whole number of up to 20 digits is written plainly ({@code 1000}, {@code -3}); any other is its
   * digits and exponent ({@code 15e-1} for 1.5), which stays short whatever the exponent.
   */
  static String normalizedNumber(JsonValue.Num number) {
    BigDecimal value = number.decimalValue().stripTrailingZeros();
    if (value.signum() == 0) {
      return "0";
    }
    if (value.scale() <= 0 && value.precision() - value.scale() <= 20) {
      return value.toBigIntegerExact().toString();
    }
    BigInteger digits = value.unscaledValue();
    return digits + "e" + (-(long) value.scale());
  }

  /**
   * The value of exactly these hex digits (no sign, no prefix), or -1 when they aren't all hex
   * digits: what a {@code \\u} or {@code %} escape needs, where Integer.parseInt would also take a
   * sign.
   */
  public static int hexValue(String digits) {
    int value = 0;
    for (int i = 0; i < digits.length(); i++) {
      int digit = Character.digit(digits.charAt(i), 16);
      if (digit < 0 || digits.charAt(i) > 'f') {
        return -1;
      }
      value = value * 16 + digit;
    }
    return digits.isEmpty() ? -1 : value;
  }

  static void writeString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          boolean unpaired =
              Character.isHighSurrogate(c)
                  ? i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))
                  : Character.isLowSurrogate(c)
                      && (i == 0 || !Character.isHighSurrogate(value.charAt(i - 1)));
          if (c < 0x20 || unpaired) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static List<Map.Entry<String, JsonValue>> members(JsonValue.Obj object, boolean sort) {
    List<Map.Entry<String, JsonValue>> members = new ArrayList<>(object.members().entrySet());
    if (sort) {
      members.sort(Map.Entry.comparingByKey());
    }
    return members;
  }

  /** Writes on one line. */
  private record Writer(StringBuilder out, boolean sort, boolean normalize, Drain drain) {
    void write(JsonValue value) {
      if (value instanceof JsonValue.Obj object) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> member : members(object, sort)) {
          if (!first) {
            out.append(',');
          }
          first = false;
          writeString(out, member.getKey());
          out.append(':');
          write(member.getValue());
          drain.maybe(out);
        }
        out.append('}');
      } else if (value instanceof JsonValue.Arr array) {
        out.append('[');
        boolean first = true;
        for (JsonValue item : array.items()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          write(item);
          drain.maybe(out);
        }
        out.append(']');
      } else if (value instanceof JsonValue.Str string) {
        writeString(out, string.value());
      } else if (value instanceof JsonValue.Num number) {
        out.append(normalize ? normalizedNumber(number) : number.text());
      } else if (value instanceof JsonValue.Bool bool) {
        out.append(bool.value());
      } else {
        out.append("null");
      }
    }
  }

  /** Writes sorted and indented. */
  private record Pretty(StringBuilder out, Drain drain) {
    void write(JsonValue value, int depth) {
      String inline = inline(value);
      if (inline != null) {
        out.append(inline);
        return;
      }
      if (value instanceof JsonValue.Obj object) {
        out.append("{\n");
        List<Map.Entry<String, JsonValue>> members = members(object, true);
        for (int i = 0; i < members.size(); i++) {
          indent(depth + 1);
          writeString(out, members.get(i).getKey());
          out.append(": ");
          write(members.get(i).getValue(), depth + 1);
          out.append(i + 1 < members.size() ? ",\n" : "\n");
          drain.maybe(out);
        }
        indent(depth);
        out.append('}');
      } else if (value instanceof JsonValue.Arr array) {
        out.append("[\n");
        List<JsonValue> items = array.items();
        for (int i = 0; i < items.size(); i++) {
          indent(depth + 1);
          write(items.get(i), depth + 1);
          out.append(i + 1 < items.size() ? ",\n" : "\n");
          drain.maybe(out);
        }
        indent(depth);
        out.append(']');
      } else {
        throw new IllegalStateException("plain values are always inline");
      }
    }

    /** The value on one line, when it's plain, or a short container of plain values. */
    private static @Nullable String inline(JsonValue value) {
      StringBuilder line = new StringBuilder();
      if (value instanceof JsonValue.Obj object) {
        line.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> member : members(object, true)) {
          if (!isPlain(member.getValue())) {
            return null;
          }
          line.append(first ? "" : ", ");
          first = false;
          writeString(line, member.getKey());
          line.append(": ");
          new Writer(line, true, false, Drain.NONE).write(member.getValue());
        }
        line.append('}');
      } else if (value instanceof JsonValue.Arr array) {
        line.append('[');
        boolean first = true;
        for (JsonValue item : array.items()) {
          if (!isPlain(item)) {
            return null;
          }
          line.append(first ? "" : ", ");
          first = false;
          new Writer(line, true, false, Drain.NONE).write(item);
        }
        line.append(']');
      } else {
        new Writer(line, true, false, Drain.NONE).write(value);
        return line.toString();
      }
      return line.length() <= INLINE_WIDTH ? line.toString() : null;
    }

    private static boolean isPlain(JsonValue value) {
      if (value instanceof JsonValue.Obj object) {
        return object.members().isEmpty();
      }
      if (value instanceof JsonValue.Arr array) {
        return array.items().isEmpty();
      }
      return true;
    }

    private void indent(int depth) {
      out.append("  ".repeat(depth));
    }
  }

  /** A recursive-descent parser over the text, with the position for messages. */
  private static final class Parser {
    private final String text;
    // One copy of each member name: settings repeat a few names ("x", "y") tens of thousands of
    // times.
    private final Map<String, String> names = new HashMap<>();
    private int pos;

    Parser(String text) {
      this.text = text;
    }

    JsonValue value(int depth) {
      if (depth > MAX_DEPTH) {
        throw error("nested deeper than " + MAX_DEPTH);
      }
      if (pos >= text.length()) {
        throw error("expected a value, found the end");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> object(depth);
        case '[' -> array(depth);
        case '"' -> new JsonValue.Str(string());
        case 't' -> literal("true", JsonValue.TRUE);
        case 'f' -> literal("false", JsonValue.FALSE);
        case 'n' -> literal("null", JsonValue.NULL);
        default -> {
          if (c == '-' || (c >= '0' && c <= '9')) {
            yield number();
          }
          throw error("unexpected character '" + c + "'");
        }
      };
    }

    private JsonValue.Obj object(int depth) {
      pos++; // {
      Map<String, JsonValue> members = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        pos++;
        return new JsonValue.Obj(members);
      }
      while (true) {
        skipWhitespace();
        if (peek() != '"') {
          throw error("expected a member's name in quotes");
        }
        int at = pos;
        String name = names.computeIfAbsent(string(), n -> n);
        skipWhitespace();
        expect(':');
        skipWhitespace();
        JsonValue value = value(depth + 1);
        if (members.put(name, value) != null) {
          pos = at;
          throw error("duplicate member \"" + name + "\"");
        }
        skipWhitespace();
        char c = peek();
        pos++;
        if (c == '}') {
          return new JsonValue.Obj(members);
        }
        if (c != ',') {
          pos--;
          throw error("expected ',' or '}'");
        }
      }
    }

    private JsonValue.Arr array(int depth) {
      pos++; // [
      List<JsonValue> items = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        pos++;
        return new JsonValue.Arr(items);
      }
      while (true) {
        skipWhitespace();
        items.add(value(depth + 1));
        skipWhitespace();
        char c = peek();
        pos++;
        if (c == ']') {
          return new JsonValue.Arr(items);
        }
        if (c != ',') {
          pos--;
          throw error("expected ',' or ']'");
        }
      }
    }

    private String string() {
      pos++; // "
      StringBuilder value = new StringBuilder();
      while (true) {
        if (pos >= text.length()) {
          throw error("a string that never ends");
        }
        char c = text.charAt(pos++);
        if (c == '"') {
          return value.toString();
        }
        if (c < 0x20) {
          pos--;
          throw error("a control character in a string (escape it)");
        }
        if (c != '\\') {
          value.append(c);
          continue;
        }
        if (pos >= text.length()) {
          throw error("a string that never ends");
        }
        char escape = text.charAt(pos++);
        switch (escape) {
          case '"' -> value.append('"');
          case '\\' -> value.append('\\');
          case '/' -> value.append('/');
          case 'b' -> value.append('\b');
          case 'f' -> value.append('\f');
          case 'n' -> value.append('\n');
          case 'r' -> value.append('\r');
          case 't' -> value.append('\t');
          case 'u' -> {
            int code = pos + 4 > text.length() ? -1 : hexValue(text.substring(pos, pos + 4));
            if (code < 0) {
              throw error("a \\u escape needs four hex digits");
            }
            value.append((char) code);
            pos += 4;
          }
          default -> {
            pos--;
            throw error("unknown escape \\" + escape);
          }
        }
      }
    }

    private JsonValue.Num number() {
      int start = pos;
      if (peek() == '-') {
        pos++;
      }
      while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) {
        pos++;
        if (pos - start > MAX_NUMBER) {
          pos = start;
          throw error("a number longer than " + MAX_NUMBER + " characters");
        }
      }
      String lexeme = text.substring(start, pos);
      try {
        return new JsonValue.Num(lexeme);
      } catch (JsonException e) {
        pos = start;
        throw error(String.valueOf(e.getMessage()));
      }
    }

    private JsonValue literal(String word, JsonValue value) {
      if (!text.startsWith(word, pos)) {
        throw error("unexpected text, expected " + word);
      }
      pos += word.length();
      return value;
    }

    private char peek() {
      return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void expect(char c) {
      if (peek() != c) {
        throw error("expected '" + c + "'");
      }
      pos++;
    }

    void skipWhitespace() {
      while (pos < text.length()) {
        char c = text.charAt(pos);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          return;
        }
        pos++;
      }
    }

    JsonException error(String message) {
      int line = 1;
      int column = 1;
      for (int i = 0; i < Math.min(pos, text.length()); i++) {
        if (text.charAt(i) == '\n') {
          line++;
          column = 1;
        } else {
          column++;
        }
      }
      return new JsonException("line " + line + ", column " + column + ": " + message);
    }
  }
}
