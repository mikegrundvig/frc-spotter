package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * Cores that share a clock (a cpufreq policy).
 *
 * @param cores the core numbers, such as 4 and 5
 * @param currentMhz the frequency they run at now
 * @param limitMhz the most they're allowed now; below {@code maxMhz} when something (heat, power)
 *     holds them back
 * @param maxMhz the most they can run at
 */
public record CpuCluster(List<Integer> cores, int currentMhz, int limitMhz, int maxMhz) {
  public CpuCluster {
    cores = List.copyOf(cores);
  }

  /** Whether the cluster is held below its maximum frequency. */
  public boolean capped() {
    return limitMhz < maxMhz;
  }

  /** The cluster as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("cores", JsonValue.array(cores, core -> JsonValue.of((long) core)))
        .put("currentMhz", currentMhz)
        .put("limitMhz", limitMhz)
        .put("maxMhz", maxMhz)
        .build();
  }

  /** A cluster from its JSON. */
  public static CpuCluster fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("cluster");
    return new CpuCluster(
        o.list("cores", item -> (int) item.asLong("cores[]")),
        o.integer("currentMhz", 0),
        o.integer("limitMhz", 0),
        o.integer("maxMhz", 0));
  }
}
