package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.api.TripPoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every thermal zone, with its trip points as the running kernel has them: a vendor kernel's may
 * differ from mainline's, so they're read, not assumed.
 */
final class ThermalSource {
  static final String THERMAL = "/sys/class/thermal";
  static final int MAX_ZONES = 32;
  static final int MAX_TRIPS = 12;

  private final Host host;

  ThermalSource(Host host) {
    this.host = host;
  }

  List<ThermalZone> read() throws IOException {
    List<String> zones = new ArrayList<>();
    for (String name : host.list(THERMAL)) {
      if (name.matches("thermal_zone[0-9]+")) {
        zones.add(name);
      }
    }
    zones.sort(Comparator.comparingInt(name -> Integer.parseInt(name.substring(12))));
    List<ThermalZone> read = new ArrayList<>();
    for (String zone : zones.subList(0, Math.min(zones.size(), MAX_ZONES))) {
      String folder = THERMAL + "/" + zone + "/";
      Optional<Double> celsius = celsius(folder + "temp");
      if (celsius.isEmpty()) {
        continue; // a zone that can't be read now (some report an error while off)
      }
      List<TripPoint> trips = new ArrayList<>();
      for (int i = 0; i < MAX_TRIPS; i++) {
        Optional<String> type = host.line(folder + "trip_point_" + i + "_type");
        Optional<Double> temp = celsius(folder + "trip_point_" + i + "_temp");
        if (type.isEmpty() || temp.isEmpty()) {
          break;
        }
        trips.add(new TripPoint(type.get(), temp.get()));
      }
      trips.sort(Comparator.comparingDouble(TripPoint::celsius));
      read.add(new ThermalZone(host.line(folder + "type").orElse(zone), celsius.get(), trips));
    }
    return read;
  }

  /** A sysfs temperature (millidegrees) in °C, to a tenth. */
  private Optional<Double> celsius(String file) {
    try {
      return host.line(file).map(milli -> Math.round(Long.parseLong(milli) / 100.0) / 10.0);
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }
}
