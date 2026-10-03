package com.michaelgrundvig.frc.spotter.agent;

import io.avaje.json.JsonException;
import io.avaje.json.mapper.JsonMapper;
import java.util.Map;
import java.util.Optional;

/**
 * JSON as the agent reads it, with avaje-jsonb's mapper (WPILib's JSON library): {@code agent.json}
 * and the JSON a collector or an action prints, as maps, lists, text, numbers ({@code Long} when
 * whole, else {@code Double}), booleans and nulls. The mapper recurses as deep as the JSON nests,
 * so JSON nested deeper than {@link #MAX_DEPTH} isn't read as JSON at all: a count of brackets
 * outside strings, before the mapper sees it.
 */
final class JsonText {
  /** The one mapper, safe to share between threads. */
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** How deep JSON may nest to be read: a script's output needs a few levels. */
  static final int MAX_DEPTH = 64;

  private JsonText() {}

  /**
   * A JSON object's members.
   *
   * @throws IllegalArgumentException when the text isn't a JSON object
   */
  static Map<String, Object> object(String json) {
    return asObject(json).orElseThrow(() -> new IllegalArgumentException("it isn't a JSON object"));
  }

  /** A JSON object's members, or empty when the text, trimmed, isn't one. */
  static Optional<Map<String, Object>> asObject(String json) {
    String text = json.strip();
    if (!text.startsWith("{") || !text.endsWith("}") || tooDeep(text)) {
      return Optional.empty();
    }
    try {
      return Optional.of(MAPPER.fromJsonObject(text));
    } catch (JsonException | IllegalStateException | ClassCastException e) {
      return Optional.empty();
    }
  }

  /**
   * Whether JSON nests deeper than {@link #MAX_DEPTH}: its {@code [} and <code>{</code> outside
   * strings, counted. Only the structure is looked at, never what's in it.
   */
  static boolean tooDeep(String json) {
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '{' || c == '[') {
        if (++depth > MAX_DEPTH) {
          return true;
        }
      } else if (c == '}' || c == ']') {
        depth--;
      }
    }
    return false;
  }

  /** A value as JSON text. */
  static String write(Object value) {
    return MAPPER.toJson(value);
  }
}
