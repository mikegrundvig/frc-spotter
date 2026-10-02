package com.michaelgrundvig.frc.spotter.probes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The agent's own measurements a {@link ProbeKind#THRESHOLD} probe may hold against limits: what
 * its health already reads, by name. Each is a number; a yes-or-no one is 1 or 0.
 */
public final class Metrics {
  private Metrics() {}

  /** Each metric's name, with what it is and in what unit. */
  public static final Map<String, String> NAMES = names();

  private static Map<String, String> names() {
    Map<String, String> names = new LinkedHashMap<>();
    names.put("cpu.busiest.percent", "the busiest core's busy time, in percent");
    names.put("cpu.total.percent", "every core's busy time together, in percent");
    names.put("cpu.capped", "1 when a cluster's clock is held below its most (throttled), else 0");
    names.put("thermal.hottest.celsius", "the hottest thermal zone, in °C");
    names.put(
        "thermal.margin.celsius",
        "how far below its first passive trip point the zone nearest one is, in °C");
    names.put("memory.available.percent", "memory available, in percent of all of it");
    names.put("memory.available.mb", "memory available, in MiB");
    names.put("disk.root.free.percent", "the root filesystem's free space, in percent");
    names.put("disk.data.free.percent", "/data's free space, in percent");
    names.put("disk.data.free.mb", "/data's free space, in MiB");
    names.put("drive.celsius", "the NVMe drive's temperature, in °C");
    names.put("drive.used.percent", "how much of its rated endurance the NVMe drive has used");
    names.put("drive.critical.warning", "the NVMe drive's critical warning bits; 0 is healthy");
    names.put("drive.media.errors", "the NVMe drive's media and data integrity errors");
    names.put("journal.errors", "errors in the journal this boot, of every kind");
    names.put("boot.root.readonly", "1 when the root filesystem is mounted read-only, else 0");
    names.put("uptime.seconds", "how long since boot, in seconds");
    return java.util.Collections.unmodifiableMap(names);
  }

  /** Whether a metric of this name exists. */
  public static boolean exists(String name) {
    return NAMES.containsKey(name);
  }
}
