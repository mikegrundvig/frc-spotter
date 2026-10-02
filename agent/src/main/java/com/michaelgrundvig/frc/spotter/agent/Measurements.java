package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.CpuCluster;
import com.michaelgrundvig.frc.spotter.api.Disk;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.api.TripPoint;
import com.michaelgrundvig.frc.spotter.probes.Metrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent's own measurements by {@link Metrics} name, from one health reading: what a threshold
 * probe holds against its limits. A measurement the computer can't give (no thermal zones, no NVMe
 * drive, no {@code /data}) is left out, and a probe on it reports that it isn't measured here.
 */
final class Measurements {
  private Measurements() {}

  static Map<String, Double> of(Health health) {
    Map<String, Double> values = new LinkedHashMap<>();
    List<Double> busy = health.cpu().busyPercent();
    if (!busy.isEmpty()) {
      values.put(
          "cpu.busiest.percent", busy.stream().mapToDouble(Double::doubleValue).max().orElse(0));
      values.put(
          "cpu.total.percent", busy.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }
    List<CpuCluster> clusters = health.cpu().clusters();
    if (!clusters.isEmpty()) {
      boolean capped = clusters.stream().anyMatch(c -> c.maxMhz() > 0 && c.limitMhz() < c.maxMhz());
      values.put("cpu.capped", capped ? 1.0 : 0.0);
    }
    if (!health.thermal().isEmpty()) {
      values.put(
          "thermal.hottest.celsius",
          health.thermal().stream().mapToDouble(ThermalZone::celsius).max().orElse(0));
      double margin = Double.POSITIVE_INFINITY;
      for (ThermalZone zone : health.thermal()) {
        double trip = firstPassive(zone);
        if (!Double.isNaN(trip)) {
          margin = Math.min(margin, trip - zone.celsius());
        }
      }
      if (Double.isFinite(margin)) {
        values.put("thermal.margin.celsius", margin);
      }
    }
    if (health.memory().totalMb() > 0) {
      values.put("memory.available.percent", health.memory().availablePercent());
      values.put("memory.available.mb", (double) health.memory().availableMb());
    }
    for (Disk disk : health.disks()) {
      if (disk.totalMb() <= 0) {
        continue;
      }
      if (disk.mount().equals("/")) {
        values.put("disk.root.free.percent", disk.freePercent());
      } else if (disk.mount().equals("/data")) {
        values.put("disk.data.free.percent", disk.freePercent());
        values.put("disk.data.free.mb", (double) disk.freeMb());
      }
    }
    Drive drive = health.drive();
    if (drive != null) {
      values.put("drive.celsius", drive.celsius());
      values.put("drive.used.percent", (double) drive.percentUsed());
      values.put("drive.critical.warning", (double) drive.criticalWarning());
      values.put("drive.media.errors", (double) drive.mediaErrors());
    }
    values.put(
        "journal.errors",
        (double) health.journal().counts().values().stream().mapToInt(Integer::intValue).sum());
    if (!health.boot().rootDevice().isEmpty()) {
      values.put("boot.root.readonly", health.boot().rootReadOnly() ? 1.0 : 0.0);
    }
    if (Double.isFinite(health.boot().uptimeSeconds())) {
      values.put("uptime.seconds", health.boot().uptimeSeconds());
    }
    return values;
  }

  /**
   * A zone's first passive trip point (where the kernel slows the cores), else its first; NaN for
   * none.
   */
  static double firstPassive(ThermalZone zone) {
    for (TripPoint trip : zone.trips()) {
      if (trip.type().equals("passive")) {
        return trip.celsius();
      }
    }
    return zone.trips().isEmpty() ? Double.NaN : zone.trips().get(0).celsius();
  }
}
