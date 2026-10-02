package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * A camera plugged in: a USB video device, by the path PhotonVision matches cameras by.
 *
 * @param path its stable path, such as {@code
 *     /dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0}: which port it's in
 * @param port the USB port as the kernel names it, such as {@code 7-1}
 * @param speedMbps the speed it's connected at, Mb/s: 480 for USB 2, 5000 for USB 3; 0 if unknown
 * @param vendorId the USB vendor ID, four hex digits
 * @param productId the USB product ID, four hex digits
 * @param product the product name it reports
 */
public record UsbCamera(
    String path, String port, double speedMbps, String vendorId, String productId, String product) {

  /** The camera as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("path", path)
        .put("port", port)
        .put("speedMbps", speedMbps)
        .put("vendorId", vendorId)
        .put("productId", productId)
        .put("product", product)
        .build();
  }

  /** A camera from its JSON. */
  public static UsbCamera fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("USB camera");
    return new UsbCamera(
        o.string("path", ""),
        o.string("port", ""),
        o.number("speedMbps", 0),
        o.string("vendorId", ""),
        o.string("productId", ""),
        o.string("product", ""));
  }
}
