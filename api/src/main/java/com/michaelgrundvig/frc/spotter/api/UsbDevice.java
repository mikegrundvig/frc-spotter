package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * A USB device by the port it's plugged into, or a port a device was unplugged from this boot.
 * Cameras dropping out from static or vibration show here as disconnects, and so does any other
 * device; which camera is which is the vision software's to say.
 *
 * @param port its port path, as the kernel names it: {@code 7-1} is bus 7's port 1, {@code 7-1.2}
 *     port 2 of a hub there
 * @param present whether a device is plugged in there now
 * @param vendorId its USB vendor ID, hex; empty when none is there
 * @param productId its USB product ID, hex; empty when none is there
 * @param product what it calls itself; empty when it doesn't say, or none is there
 * @param speedMbps its link speed, in Mb/s: 480 for USB 2, 5000 for USB 3; 0 when unknown
 * @param disconnects how often a device was unplugged from there this boot, from the kernel's log
 */
public record UsbDevice(
    String port,
    boolean present,
    String vendorId,
    String productId,
    String product,
    double speedMbps,
    int disconnects) {

  /** The device as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("port", port)
        .put("present", present)
        .put("vendorId", vendorId)
        .put("productId", productId)
        .put("product", product)
        .put("speedMbps", speedMbps)
        .put("disconnects", disconnects)
        .build();
  }

  /** A device from its JSON. */
  public static UsbDevice fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("USB device");
    return new UsbDevice(
        o.string("port", ""),
        o.bool("present", false),
        o.string("vendorId", ""),
        o.string("productId", ""),
        o.string("product", ""),
        o.number("speedMbps", 0),
        o.integer("disconnects", 0));
  }
}
