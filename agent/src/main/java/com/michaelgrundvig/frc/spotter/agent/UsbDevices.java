package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * A device at its stable {@code by-path} name, followed to the USB device behind it: which port
 * it's plugged into, at what link speed, and what it says it is. Through the device node's class
 * entry in sysfs ({@code /sys/class/video4linux/video0/device} for a camera, {@code tty} for a
 * serial port, and so on), up to the USB device that holds it.
 */
final class UsbDevices {
  /** The device classes a by-path node is looked up in. */
  static final List<String> CLASSES =
      List.of("video4linux", "tty", "hidraw", "input", "sound", "usbmisc", "block");

  /** How far up from a node's device a USB device is looked for. */
  static final int MAX_DEPTH = 8;

  private final Host host;

  UsbDevices(Host host) {
    this.host = host;
  }

  /**
   * A device found at its by-path name.
   *
   * @param port the USB device's name in sysfs, which is its port: {@code 7-1}; empty when it isn't
   *     a USB device, or it couldn't be followed
   * @param speedMbps its link speed, in Mb/s: 480 for USB 2, 5000 for USB 3; 0 when unknown
   * @param vendorId its USB vendor ID, hex
   * @param productId its USB product ID, hex
   * @param product what it calls itself
   */
  record Found(String port, double speedMbps, String vendorId, String productId, String product) {}

  /** The device at a by-path name; empty when nothing is plugged in there. */
  Optional<Found> at(String byPath) throws IOException {
    if (!host.exists(byPath)) {
      return Optional.empty();
    }
    String node;
    try {
      node = host.resolve(byPath);
    } catch (IOException e) {
      // Unplugged as it was read: there a moment ago, so present, but not followed.
      return Optional.of(new Found("", 0, "", "", ""));
    }
    String name = node.substring(node.lastIndexOf('/') + 1);
    for (String deviceClass : CLASSES) {
      String link = "/sys/class/" + deviceClass + "/" + name + "/device";
      if (!host.exists(link)) {
        continue;
      }
      String device;
      try {
        device = host.resolve(link);
      } catch (IOException e) {
        break;
      }
      for (int up = 0; up < MAX_DEPTH && device.startsWith("/sys/devices/"); up++) {
        if (host.exists(device + "/speed") && host.exists(device + "/idVendor")) {
          return Optional.of(
              new Found(
                  device.substring(device.lastIndexOf('/') + 1),
                  host.line(device + "/speed").map(UsbDevices::number).orElse(0.0),
                  host.line(device + "/idVendor").orElse(""),
                  host.line(device + "/idProduct").orElse(""),
                  host.line(device + "/product").orElse("")));
        }
        device = device.substring(0, device.lastIndexOf('/'));
      }
      break;
    }
    return Optional.of(new Found("", 0, "", "", ""));
  }

  private static double number(String text) {
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
