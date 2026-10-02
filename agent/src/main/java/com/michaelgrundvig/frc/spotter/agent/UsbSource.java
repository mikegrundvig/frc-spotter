package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.UsbDevice;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Every USB device by the port it's plugged into, from {@code /sys/bus/usb/devices} (its root hubs
 * and interfaces left out), with how often a device was unplugged from each port this boot, as the
 * journal's count has it; a port whose device was unplugged and hasn't come back is there too, as
 * not present.
 */
final class UsbSource {
  static final String DEVICES = "/sys/bus/usb/devices";

  /** The most ports reported. */
  static final int MAX_DEVICES = 32;

  /** A device's name in sysfs, which is its port path: bus, then each port, {@code 7-1.2}. */
  static final Pattern PORT = Pattern.compile("[0-9]+-[0-9]+(\\.[0-9]+){0,6}");

  private final Host host;

  UsbSource(Host host) {
    this.host = host;
  }

  /**
   * The devices plugged in now, and the ports a device was unplugged from.
   *
   * @param disconnects how often a device was unplugged from each port this boot
   */
  List<UsbDevice> read(Map<String, Integer> disconnects) throws IOException {
    Map<String, UsbDevice> ports = new TreeMap<>(PORTS);
    for (String port : host.list(DEVICES)) {
      if (!PORT.matcher(port).matches()) {
        continue;
      }
      String folder = DEVICES + "/" + port + "/";
      ports.put(
          port,
          new UsbDevice(
              port,
              true,
              host.line(folder + "idVendor").orElse(""),
              host.line(folder + "idProduct").orElse(""),
              host.line(folder + "product").orElse(""),
              speed(folder + "speed"),
              disconnects.getOrDefault(port, 0)));
    }
    disconnects.forEach(
        (port, count) -> ports.putIfAbsent(port, new UsbDevice(port, false, "", "", "", 0, count)));
    List<UsbDevice> devices = new ArrayList<>(ports.values());
    return devices.subList(0, Math.min(devices.size(), MAX_DEVICES));
  }

  /** Ports in the order a person reads them: bus by bus, then port by port, as numbers. */
  static final Comparator<String> PORTS =
      (a, b) -> {
        String[] left = a.split("[-.]");
        String[] right = b.split("[-.]");
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
          int compared = compareNumbers(left[i], right[i]);
          if (compared != 0) {
            return compared;
          }
        }
        return Integer.compare(left.length, right.length);
      };

  private static int compareNumbers(String a, String b) {
    try {
      return Long.compare(Long.parseLong(a), Long.parseLong(b));
    } catch (NumberFormatException e) {
      return a.compareTo(b);
    }
  }

  private double speed(String file) {
    try {
      return host.line(file).map(Double::parseDouble).orElse(0.0);
    } catch (IOException | NumberFormatException e) {
      return 0;
    }
  }
}
