package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * The cameras the computer should run against those plugged in.
 *
 * @param expected the cameras in the computer's role, each with where it's expected
 * @param present every USB camera plugged in
 */
public record Cameras(List<ExpectedCamera> expected, List<UsbCamera> present) {
  public Cameras {
    expected = List.copyOf(expected);
    present = List.copyOf(present);
  }

  /** Nothing known. */
  public static final Cameras UNKNOWN = new Cameras(List.of(), List.of());

  /** The expected cameras not plugged in where expected. */
  public List<ExpectedCamera> missing() {
    return expected.stream().filter(camera -> !camera.present()).toList();
  }

  /** The cameras as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("expected", JsonValue.array(expected, ExpectedCamera::toJson))
        .put("present", JsonValue.array(present, UsbCamera::toJson))
        .build();
  }

  /** The cameras from their JSON. */
  public static Cameras fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("cameras");
    return new Cameras(
        o.list("expected", ExpectedCamera::fromJson), o.list("present", UsbCamera::fromJson));
  }
}
