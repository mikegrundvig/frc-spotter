package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;
import java.util.Optional;

/**
 * A temperature sensor the kernel watches, with the points where it acts. The trip points are read
 * from the running kernel, since a vendor kernel's may differ from mainline's.
 *
 * @param type the zone's name, such as {@code bigcore0-thermal}
 * @param celsius its temperature, °C
 * @param trips where the kernel acts, coolest first
 */
public record ThermalZone(String type, double celsius, List<TripPoint> trips) {
  public ThermalZone {
    trips = List.copyOf(trips);
  }

  /** The first passive trip point: where the kernel starts slowing the cores to cool them. */
  public Optional<TripPoint> firstPassive() {
    return trips.stream()
        .filter(trip -> trip.type().equals("passive"))
        .min((a, b) -> Double.compare(a.celsius(), b.celsius()));
  }

  /** The zone as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("type", type)
        .put("celsius", celsius)
        .put("trips", JsonValue.array(trips, TripPoint::toJson))
        .build();
  }

  /** A zone from its JSON. */
  public static ThermalZone fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("thermal zone");
    return new ThermalZone(
        o.string("type", ""),
        o.number("celsius", Double.NaN),
        o.list("trips", TripPoint::fromJson));
  }
}
