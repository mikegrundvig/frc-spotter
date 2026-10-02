package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.CpuCluster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * How busy each core is, from {@code /proc/stat}, and each cluster's frequencies, from cpufreq.
 * Busy time is measured between answers: the first answer covers the time since boot, and each
 * later one the time since the one before (at least {@link #MIN_WINDOW_MICROS}; an answer sooner
 * than that repeats the last).
 */
final class CpuSource {
  static final String STAT = "/proc/stat";
  static final String CPUFREQ = "/sys/devices/system/cpu/cpufreq";
  static final long MIN_WINDOW_MICROS = 250_000;

  private final Host host;
  private @Nullable Sample last;
  private @Nullable Cpu answer;

  CpuSource(Host host) {
    this.host = host;
  }

  /** Each core's counters at one moment: busy and total jiffies. */
  private record Sample(long micros, long[] busy, long[] total) {}

  synchronized Cpu read() throws IOException {
    long now = host.monotonicMicros();
    Sample previous = last;
    Cpu previousAnswer = answer;
    if (previous != null && previousAnswer != null && now - previous.micros() < MIN_WINDOW_MICROS) {
      return previousAnswer;
    }
    Sample sample = sample(now);
    List<Double> busy = new ArrayList<>();
    for (int core = 0; core < sample.total().length; core++) {
      long busyJiffies = sample.busy()[core];
      long totalJiffies = sample.total()[core];
      if (previous != null && core < previous.total().length) {
        busyJiffies -= previous.busy()[core];
        totalJiffies -= previous.total()[core];
      }
      busy.add(totalJiffies <= 0 ? 0.0 : Math.round(1000.0 * busyJiffies / totalJiffies) / 10.0);
    }
    double window =
        previous == null
            ? host.line(StampSource.UPTIME)
                .map(line -> Double.parseDouble(line.split("\\s+")[0]))
                .orElse(0.0)
            : (now - previous.micros()) / 1e6;
    Cpu cpu = new Cpu(Math.round(window * 100) / 100.0, busy, clusters());
    last = sample;
    answer = cpu;
    return cpu;
  }

  private Sample sample(long now) throws IOException {
    List<long[]> cores = new ArrayList<>();
    for (String line : host.read(STAT).orElse("").split("\n")) {
      String[] fields = line.trim().split("\\s+");
      if (!fields[0].startsWith("cpu") || fields[0].equals("cpu")) {
        continue;
      }
      int core = Integer.parseInt(fields[0].substring(3));
      // user nice system idle iowait irq softirq steal; guest time is already in user.
      long total = 0;
      for (int i = 1; i <= 8 && i < fields.length; i++) {
        total += Long.parseLong(fields[i]);
      }
      long idle = Long.parseLong(fields[4]) + (fields.length > 5 ? Long.parseLong(fields[5]) : 0);
      while (cores.size() <= core) {
        cores.add(new long[] {0, 0});
      }
      cores.set(core, new long[] {total - idle, total});
    }
    long[] busy = new long[cores.size()];
    long[] total = new long[cores.size()];
    for (int i = 0; i < cores.size(); i++) {
      busy[i] = cores.get(i)[0];
      total[i] = cores.get(i)[1];
    }
    return new Sample(now, busy, total);
  }

  /** Each cpufreq policy: the cores sharing a clock, and its current, limit, and maximum MHz. */
  List<CpuCluster> clusters() throws IOException {
    List<CpuCluster> clusters = new ArrayList<>();
    for (String policy : host.list(CPUFREQ)) {
      if (!policy.startsWith("policy")) {
        continue;
      }
      String folder = CPUFREQ + "/" + policy + "/";
      String related =
          host.line(folder + "related_cpus").or(() -> line(folder + "affected_cpus")).orElse("");
      List<Integer> cores = new ArrayList<>();
      for (String core : related.split("\\s+")) {
        if (!core.isEmpty()) {
          cores.add(Integer.parseInt(core));
        }
      }
      clusters.add(
          new CpuCluster(
              cores,
              mhz(folder + "scaling_cur_freq"),
              mhz(folder + "scaling_max_freq"),
              mhz(folder + "cpuinfo_max_freq")));
    }
    clusters.sort((a, b) -> Integer.compare(first(a), first(b)));
    return clusters;
  }

  private Optional<String> line(String path) {
    try {
      return host.line(path);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static int first(CpuCluster cluster) {
    return cluster.cores().isEmpty() ? Integer.MAX_VALUE : cluster.cores().get(0);
  }

  private int mhz(String file) throws IOException {
    return host.line(file).map(khz -> (int) (Long.parseLong(khz) / 1000)).orElse(0);
  }
}
