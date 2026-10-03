package com.michaelgrundvig.frc.spotter.agent;

import io.avaje.json.JsonException;
import io.avaje.json.mapper.JsonMapper;
import java.util.Map;
import java.util.Optional;

/**
 * JSON as the agent reads it, with avaje-jsonb's mapper (WPILib's JSON library): {@code agent.json}
 * and the JSON a collector or an action prints, as maps, lists, text, numbers ({@code Long} when
 * whole, else {@code Double}), booleans and nulls.
 */
final class JsonText {
  /** The one mapper, safe to share between threads. */
  static final JsonMapper MAPPER = JsonMapper.builder().build();

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
    if (!text.startsWith("{") || !text.endsWith("}")) {
      return Optional.empty();
    }
    try {
      return Optional.of(MAPPER.fromJsonObject(text));
    } catch (JsonException | IllegalStateException | ClassCastException e) {
      return Optional.empty();
    }
  }

  /** A value as JSON text. */
  static String write(Object value) {
    return MAPPER.toJson(value);
  }
}
