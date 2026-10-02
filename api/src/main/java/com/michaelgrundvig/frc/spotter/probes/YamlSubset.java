package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The part of YAML a pack is written in, read without a YAML library (so nothing extra reaches the
 * robot program or the agent). It reads:
 *
 * <ul>
 *   <li>mappings, one {@code key: value} per line, nested by indenting with spaces;
 *   <li>lists, one {@code - item} per line, at the key's indent or deeper; an item may itself be a
 *       mapping ({@code - id: vision.unit} and its keys beneath);
 *   <li>one-line lists of plain values: {@code [vision.service, other.service]};
 *   <li>plain, {@code 'single'}, and {@code "double"} quoted values;
 *   <li>{@code #} comments, blank lines, and a leading {@code ---}.
 * </ul>
 *
 * <p>Everything else YAML allows (tabs for indents, {@code {...}} mappings, anchors, tags,
 * multi-line values, several documents) is refused with its line number, rather than read in a way
 * the writer didn't mean. Values stay text: what a value means (a number, a name) is for whoever
 * reads it, so a value written {@code no} stays {@code "no"}.
 */
final class YamlSubset {
  /** A parsed value. */
  sealed interface Node permits Mapping, Sequence, Scalar {
    /** The line it starts on, from 1. */
    int line();
  }

  /** A mapping: keys in order, each with the line it's on. */
  record Mapping(int line, Map<String, Entry> entries) implements Node {}

  /** A mapping's entry. */
  record Entry(int line, Node value) {}

  /** A list. */
  record Sequence(int line, List<Node> items) implements Node {}

  /**
   * A value as written. {@code empty} is a key with nothing after it; {@code quoted} is a value
   * written in quotes, which is always text.
   */
  record Scalar(int line, String text, boolean quoted, boolean empty) implements Node {}

  private record Line(int number, int indent, String content) {}

  /** The deepest a file nests: a pack needs three levels. */
  static final int MAX_DEPTH = 16;

  private final String source;
  private final List<Line> lines;
  private int pos;
  private int depth;

  private YamlSubset(String source, List<Line> lines) {
    this.source = source;
    this.lines = lines;
  }

  /** Parses the text; {@code source} names it in messages. */
  static Node parse(String text, String source) {
    List<Line> lines = new ArrayList<>();
    String[] raw = text.split("\n", -1);
    boolean started = false;
    for (int i = 0; i < raw.length; i++) {
      String physical = raw[i];
      if (physical.endsWith("\r")) {
        physical = physical.substring(0, physical.length() - 1);
      }
      if (i == 0 && physical.startsWith("\uFEFF")) {
        physical = physical.substring(1);
      }
      int indent = 0;
      while (indent < physical.length() && physical.charAt(indent) == ' ') {
        indent++;
      }
      String content = stripComment(physical.substring(indent)).stripTrailing();
      if (content.isEmpty()) {
        continue;
      }
      if (content.charAt(0) == '\t') {
        throw error(source, i + 1, "indent with spaces, not tabs");
      }
      if (indent == 0 && (content.equals("---") || content.startsWith("--- "))) {
        if (started) {
          throw error(source, i + 1, "only one document is allowed; remove this ---");
        }
        started = true;
        if (content.equals("---")) {
          continue;
        }
        throw error(source, i + 1, "start the file on the line after ---");
      }
      if (indent == 0 && content.equals("...")) {
        throw error(source, i + 1, "remove this ...; nothing may follow the file");
      }
      started = true;
      lines.add(new Line(i + 1, indent, content));
    }
    if (lines.isEmpty()) {
      throw error(source, 1, "the file is empty");
    }
    YamlSubset parser = new YamlSubset(source, lines);
    Node root = parser.block(lines.get(0).indent());
    if (parser.pos < lines.size()) {
      throw parser.error(lines.get(parser.pos), "unexpected indentation");
    }
    return root;
  }

  private Node block(int indent) {
    if (++depth > MAX_DEPTH) {
      throw error(lines.get(pos), "nested deeper than " + MAX_DEPTH + " levels");
    }
    try {
      Line first = lines.get(pos);
      return isItem(first.content()) ? sequence(indent) : mapping(indent);
    } finally {
      depth--;
    }
  }

  private Mapping mapping(int indent) {
    int start = lines.get(pos).number();
    Map<String, Entry> entries = new LinkedHashMap<>();
    while (pos < lines.size()) {
      Line line = lines.get(pos);
      if (line.indent() < indent) {
        break;
      }
      if (line.indent() > indent) {
        throw error(line, "unexpected indentation");
      }
      if (isItem(line.content())) {
        throw error(line, "a list item where a key was expected");
      }
      KeyValue kv =
          splitKey(line).orElseThrow(() -> error(line, "expected a key and a colon (key: value)"));
      Entry first = entries.get(kv.key());
      if (first != null) {
        throw error(
            line, "\"" + kv.key() + "\" is given twice (first on line " + first.line() + ")");
      }
      pos++;
      Node value;
      if (!kv.rest().isEmpty()) {
        value = inline(line, kv.rest());
      } else if (pos < lines.size() && lines.get(pos).indent() > indent) {
        value = block(lines.get(pos).indent());
      } else if (pos < lines.size()
          && lines.get(pos).indent() == indent
          && isItem(lines.get(pos).content())) {
        value = sequence(indent);
      } else {
        value = new Scalar(line.number(), "", false, true);
      }
      entries.put(kv.key(), new Entry(line.number(), value));
    }
    return new Mapping(start, entries);
  }

  private Sequence sequence(int indent) {
    int start = lines.get(pos).number();
    List<Node> items = new ArrayList<>();
    while (pos < lines.size()) {
      Line line = lines.get(pos);
      if (line.indent() < indent || !isItem(line.content())) {
        break;
      }
      if (line.indent() > indent) {
        throw error(line, "unexpected indentation");
      }
      String rest = line.content().substring(1);
      int spaces = 0;
      while (spaces < rest.length() && rest.charAt(spaces) == ' ') {
        spaces++;
      }
      String item = rest.substring(spaces);
      if (item.isEmpty()) {
        pos++;
        if (pos < lines.size() && lines.get(pos).indent() > indent) {
          items.add(block(lines.get(pos).indent()));
        } else {
          items.add(new Scalar(line.number(), "", false, true));
        }
      } else if (isItem(item) || splitKey(new Line(line.number(), 0, item)).isPresent()) {
        // The item is a nested block that starts on this line: read it as if it began here.
        int column = indent + 1 + spaces;
        lines.set(pos, new Line(line.number(), column, item));
        items.add(block(column));
      } else {
        pos++;
        items.add(inline(line, item));
      }
    }
    return new Sequence(start, items);
  }

  private record KeyValue(String key, String rest) {}

  private Optional<KeyValue> splitKey(Line line) {
    String content = line.content();
    char c = content.charAt(0);
    if (c == '?') {
      throw error(line, "complex keys (?) aren't supported");
    }
    if (c == '[' || c == '{') {
      return Optional.empty();
    }
    if (c == '"' || c == '\'') {
      int end = quotedEnd(line, content, 0);
      String key = unquote(line, content.substring(0, end + 1));
      String after = content.substring(end + 1).stripLeading();
      if (!after.startsWith(":") || (after.length() > 1 && after.charAt(1) != ' ')) {
        return Optional.empty();
      }
      return Optional.of(new KeyValue(key, after.substring(1).strip()));
    }
    int colon = content.indexOf(':');
    while (colon >= 0 && colon + 1 < content.length() && content.charAt(colon + 1) != ' ') {
      colon = content.indexOf(':', colon + 1);
    }
    if (colon <= 0) {
      return Optional.empty();
    }
    String key = content.substring(0, colon).strip();
    return Optional.of(new KeyValue(key, content.substring(colon + 1).strip()));
  }

  private Node inline(Line line, String text) {
    if (text.charAt(0) == '[') {
      return flowSequence(line, text);
    }
    if (text.charAt(0) == '"' || text.charAt(0) == '\'') {
      int end = quotedEnd(line, text, 0);
      if (end != text.length() - 1) {
        throw error(line, "unexpected text after the closing quote");
      }
      return new Scalar(line.number(), unquote(line, text), true, false);
    }
    return plain(line, text);
  }

  /**
   * A plain (unquoted) value, refused when it starts with what YAML reads as something else: an
   * anchor, alias, tag, directive, block, or mapping, or a character YAML reserves.
   */
  private Scalar plain(Line line, String text) {
    char c = text.charAt(0);
    switch (c) {
      case '{' -> throw error(line, "{...} mappings aren't supported: write one key per line");
      case '[' -> throw error(line, "lists inside [...] aren't supported");
      case '&', '*' -> throw error(line, "anchors and aliases (& and *) aren't supported");
      case '!' -> throw error(line, "tags (!) aren't supported");
      case '|', '>' -> throw error(line, "multi-line values (| and >) aren't supported");
      case '%', '@', '`' ->
          throw error(line, "a value can't start with " + c + "; put it in quotes");
      case '?', ':', '-' -> {
        if (text.length() == 1 || text.charAt(1) == ' ') {
          throw error(line, "a value can't start with \"" + c + " \"; put it in quotes");
        }
      }
      default -> {}
    }
    return new Scalar(line.number(), text, false, false);
  }

  private Sequence flowSequence(Line line, String text) {
    if (!text.endsWith("]")) {
      throw error(line, "a [...] list must close on the same line");
    }
    String inner = text.substring(1, text.length() - 1).strip();
    List<Node> items = new ArrayList<>();
    int i = 0;
    while (i < inner.length()) {
      while (i < inner.length() && inner.charAt(i) == ' ') {
        i++;
      }
      if (i >= inner.length()) {
        break;
      }
      char c = inner.charAt(i);
      if (c == '[' || c == '{') {
        throw error(line, "lists inside [...] aren't supported");
      }
      String item;
      boolean quoted = c == '"' || c == '\'';
      if (quoted) {
        int end = quotedEnd(line, inner, i);
        item = unquote(line, inner.substring(i, end + 1));
        i = end + 1;
        while (i < inner.length() && inner.charAt(i) == ' ') {
          i++;
        }
        if (i < inner.length() && inner.charAt(i) != ',') {
          throw error(line, "expected a comma after the closing quote");
        }
      } else {
        int comma = inner.indexOf(',', i);
        int end = comma < 0 ? inner.length() : comma;
        item = inner.substring(i, end).strip();
        if (item.isEmpty()) {
          throw error(line, "an empty item in [...]");
        }
        if (item.contains("]") || item.contains(": ")) {
          throw error(line, "unexpected \"" + item + "\" in [...]");
        }
        plain(line, item);
        i = end;
      }
      items.add(new Scalar(line.number(), item, quoted, false));
      i++; // past the comma
    }
    return new Sequence(line.number(), items);
  }

  /** Where the quoted value starting at {@code start} ends: its closing quote's index. */
  private int quotedEnd(Line line, String text, int start) {
    char quote = text.charAt(start);
    for (int i = start + 1; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quote == '"' && c == '\\') {
        i++;
      } else if (c == quote) {
        if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
          i++; // '' is a quote inside single quotes
        } else {
          return i;
        }
      }
    }
    throw error(line, "a quoted value must close on the same line");
  }

  private String unquote(Line line, String quoted) {
    String inner = quoted.substring(1, quoted.length() - 1);
    if (quoted.charAt(0) == '\'') {
      return inner.replace("''", "'");
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c != '\\') {
        out.append(c);
        continue;
      }
      char escape = i + 1 < inner.length() ? inner.charAt(++i) : '\0';
      switch (escape) {
        case '"' -> out.append('"');
        case '\\' -> out.append('\\');
        case '/' -> out.append('/');
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case ' ' -> out.append(' ');
        case 'u' -> {
          int code = i + 5 > inner.length() ? -1 : Json.hexValue(inner.substring(i + 1, i + 5));
          if (code < 0) {
            throw error(line, "a \\u escape needs four hex digits");
          }
          out.append((char) code);
          i += 4;
        }
        default -> throw error(line, "unknown escape \\" + escape + " in a quoted value");
      }
    }
    return out.toString();
  }

  /** The line without its comment: a # at the start, or after a space, outside quotes. */
  private static String stripComment(String content) {
    char quote = 0;
    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      if (quote != 0) {
        if (quote == '"' && c == '\\') {
          i++;
        } else if (c == quote) {
          quote = 0;
        }
      } else if (c == '#' && (i == 0 || content.charAt(i - 1) == ' ')) {
        return content.substring(0, i);
      } else if ((c == '"' || c == '\'') && startsValue(content, i)) {
        quote = c;
      }
    }
    return content;
  }

  /** Whether a quote at {@code i} opens a value, rather than sitting inside a plain one (it's). */
  private static boolean startsValue(String content, int i) {
    if (i == 0) {
      return true;
    }
    char before = content.charAt(i - 1);
    return before == ' ' || before == '[' || before == ',';
  }

  private static boolean isItem(String content) {
    return content.equals("-") || content.startsWith("- ");
  }

  private PackException error(Line line, String message) {
    return error(source, line.number(), message);
  }

  private static PackException error(String source, int line, String message) {
    return new PackException(List.of(source + ":" + line + ": " + message));
  }
}
