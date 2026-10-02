package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * A systemd service's state: PhotonVision's, as systemd sees it, which PhotonVision can't report
 * about itself when it has crashed.
 *
 * @param unit the unit, such as {@code photonvision.service}
 * @param activeState systemd's active state: {@code active}, {@code activating}, {@code failed},
 *     {@code inactive}, ...; empty when unknown
 * @param subState systemd's sub-state, such as {@code running} or {@code auto-restart}
 * @param result how it last ended: {@code success}, {@code exit-code}, {@code signal}, {@code
 *     oom-kill}, ...
 * @param restarts how many times systemd has restarted it this boot
 * @param activeSinceMicros when it last became active, on the monotonic clock (microseconds since
 *     boot); 0 if it hasn't
 */
public record Service(
    String unit,
    String activeState,
    String subState,
    String result,
    int restarts,
    long activeSinceMicros) {

  /** Whether it's running. */
  public boolean running() {
    return activeState.equals("active");
  }

  /** The service as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("unit", unit)
        .put("activeState", activeState)
        .put("subState", subState)
        .put("result", result)
        .put("restarts", restarts)
        .put("activeSinceMicros", activeSinceMicros)
        .build();
  }

  /** A service from its JSON. */
  public static Service fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("service");
    return new Service(
        o.string("unit", ""),
        o.string("activeState", ""),
        o.string("subState", ""),
        o.string("result", ""),
        o.integer("restarts", 0),
        o.integer("activeSinceMicros", 0L));
  }
}
