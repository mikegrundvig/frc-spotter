package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * How busy each core is, and how fast each cluster runs. Per core, because vision software often
 * runs on the big cores alone: four at 100% hide inside a 50% total.
 *
 * @param windowSeconds the time the busy percentages are measured over: since the previous answer
 *     (or since boot, for the first)
 * @param busyPercent each core's busy time, by core number, 0 to 100
 * @param clusters the cores that share a clock, each with its frequencies
 */
public record Cpu(double windowSeconds, List<Double> busyPercent, List<CpuCluster> clusters) {
  public Cpu {
    busyPercent = List.copyOf(busyPercent);
    clusters = List.copyOf(clusters);
  }

  /** Nothing known. */
  public static final Cpu UNKNOWN = new Cpu(0, List.of(), List.of());

  /** The big cores' clusters: those with the highest maximum frequency. */
  public List<CpuCluster> bigClusters() {
    int fastest = clusters.stream().mapToInt(CpuCluster::maxMhz).max().orElse(0);
    return clusters.stream().filter(cluster -> cluster.maxMhz() == fastest).toList();
  }

  /** Whether any big core is held below its maximum frequency (thermal or power limits). */
  public boolean bigCoresCapped() {
    return bigClusters().stream().anyMatch(CpuCluster::capped);
  }

  /** The CPU as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("windowSeconds", windowSeconds)
        .put("busyPercent", JsonValue.array(busyPercent, JsonValue::of))
        .put("clusters", JsonValue.array(clusters, CpuCluster::toJson))
        .build();
  }

  /** The CPU from its JSON. */
  public static Cpu fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("cpu");
    return new Cpu(
        o.number("windowSeconds", 0),
        o.list("busyPercent", item -> item.asDouble("busyPercent[]")),
        o.list("clusters", CpuCluster::fromJson));
  }
}
