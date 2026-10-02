package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * A thermal zone's trip point.
 *
 * @param type what the kernel does there: {@code active} (a fan), {@code passive} (slow the cores),
 *     {@code hot}, or {@code critical} (shut down)
 * @param celsius the temperature it trips at, °C
 */
public record TripPoint(String type, double celsius) {
  /** The trip point as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder().put("type", type).put("celsius", celsius).build();
  }

  /** A trip point from its JSON. */
  public static TripPoint fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("trip point");
    return new TripPoint(o.string("type", ""), o.number("celsius", Double.NaN));
  }
}
