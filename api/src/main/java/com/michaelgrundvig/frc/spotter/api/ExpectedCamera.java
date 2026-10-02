package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * A camera this computer should run, and whether it's where PhotonVision expects it. PhotonVision
 * tells identical cameras apart only by the USB port, so a swapped cable swaps calibrations.
 *
 * @param name the PhotonVision camera name, from the computer's role
 * @param usbPath the USB path PhotonVision's settings match it by; empty when its settings don't
 *     name one (no settings for that camera yet)
 * @param present whether a camera is plugged in at that path
 */
public record ExpectedCamera(String name, String usbPath, boolean present) {
  /** The camera as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("usbPath", usbPath)
        .put("present", present)
        .build();
  }

  /** A camera from its JSON. */
  public static ExpectedCamera fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("expected camera");
    return new ExpectedCamera(
        o.string("name", ""), o.string("usbPath", ""), o.bool("present", false));
  }
}
