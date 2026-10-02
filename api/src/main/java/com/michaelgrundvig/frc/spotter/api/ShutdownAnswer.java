package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * The answer to {@code POST /v1/shutdown} (status 202): the computer is shutting down. It stops
 * PhotonVision, then powers off; the agent stops answering once it's down.
 *
 * @param alreadyRequested whether a shutdown was already under way, so this request changed nothing
 */
public record ShutdownAnswer(boolean alreadyRequested) {
  /** The answer as JSON: {@code {"shuttingDown": true, "alreadyRequested": ...}}. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("shuttingDown", true)
        .put("alreadyRequested", alreadyRequested)
        .build();
  }

  /** An answer from its JSON. */
  public static ShutdownAnswer fromJson(JsonValue json) {
    return new ShutdownAnswer(json.asObject("shutdown").bool("alreadyRequested", false));
  }

  /** An answer from JSON text: {@code POST /v1/shutdown}'s. */
  public static ShutdownAnswer parse(String json) {
    return fromJson(Json.parse(json));
  }
}
