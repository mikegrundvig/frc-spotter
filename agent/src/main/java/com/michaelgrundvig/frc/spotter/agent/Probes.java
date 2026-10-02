package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The computer's probes: each run on its own schedule, or when the robot asks, and its latest
 * result kept for every health answer. Bounded so probes never crowd out the software they watch:
 * at most {@link #RUNNING} run on schedule at once (the rest wait their turn), at most {@link
 * #ASKED} more when asked, and one asked again within {@link AgentApi#PROBE_RUN_SECONDS} answers
 * its last result instead of running.
 *
 * <p>A probe that watches files runs on schedule only when one of them changed since its last run,
 * or {@link Probe#WATCHED_EVERY_SECONDS} after it: a hash of a database costs nothing while the
 * database doesn't change.
 */
final class Probes implements AutoCloseable {
  /** How many probes run on schedule at once. */
  static final int RUNNING = 2;

  /** How many probes run at once when asked, besides those on schedule. */
  static final int ASKED = 2;

  private final Host host;
  private final ProbeSet set;
  private final ProbeRunner runner;
  private final Map<String, ProbeResult> results = new ConcurrentHashMap<>();
  private final Map<String, String> watched = new ConcurrentHashMap<>();
  private final Semaphore asked = new Semaphore(ASKED);
  private final ScheduledThreadPoolExecutor schedule;

  /** A probe asked for by name couldn't run now: as many are running as may. */
  static final class Busy extends Exception {
    private static final long serialVersionUID = 1L;

    Busy() {
      super("as many probes are running as may; ask again shortly");
    }
  }

  Probes(Host host, ProbeSet set, ProbeRunner runner) {
    this.host = host;
    this.set = set;
    this.runner = runner;
    for (Probe probe : set.probes()) {
      results.put(probe.id(), ProbeResult.pending(probe.id(), probe.kind().id()));
    }
    schedule =
        new ScheduledThreadPoolExecutor(
            RUNNING,
            work -> {
              Thread thread = new Thread(work, "spotter-probe");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Starts running each probe on its schedule, the first runs spread over its first second. */
  void start() {
    int i = 0;
    for (Probe probe : set.probes()) {
      if (probe.onDemand()) {
        continue;
      }
      long first = 50L + (1000L * i++ / Math.max(1, set.probes().size()));
      schedule.scheduleWithFixedDelay(
          () -> scheduled(probe),
          first,
          Math.round(probe.everySeconds() * 1000),
          TimeUnit.MILLISECONDS);
    }
  }

  /** The executor the probes run on: for a test to wait on. */
  ScheduledExecutorService executor() {
    return schedule;
  }

  /** Every probe's latest result, in the set's order. */
  List<ProbeResult> results() {
    List<ProbeResult> all = new ArrayList<>();
    for (Probe probe : set.probes()) {
      all.add(results.getOrDefault(probe.id(), ProbeResult.pending(probe.id(), probe.kind().id())));
    }
    return all;
  }

  /** One probe's latest result; empty when the computer has no probe of that name. */
  Optional<ProbeResult> result(String id) {
    return set.probe(id).map(probe -> results.get(probe.id()));
  }

  /**
   * Runs a probe now, on the caller's thread, and answers its result: its last one when it ran
   * within {@link AgentApi#PROBE_RUN_SECONDS}. Empty when there's no probe of that name.
   *
   * @throws Busy when as many are running when asked as may
   */
  Optional<ProbeResult> runNow(String id) throws Busy {
    Optional<Probe> found = set.probe(id);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Probe probe = found.get();
    ProbeResult last = results.get(id);
    if (last != null
        && last.ranMicros() > 0
        && host.monotonicMicros() - last.ranMicros() < AgentApi.PROBE_RUN_SECONDS * 1_000_000L) {
      return Optional.of(last);
    }
    if (!asked.tryAcquire()) {
      throw new Busy();
    }
    try {
      return Optional.of(record(probe));
    } finally {
      asked.release();
    }
  }

  /** Runs every probe once, in order, on the caller's thread: as tests read a computer. */
  void runAll() {
    for (Probe probe : set.probes()) {
      record(probe);
    }
  }

  /** A run on schedule: skipped while the files a probe watches haven't changed, for a while. */
  void scheduled(Probe probe) {
    try {
      if (!probe.watch().isEmpty()) {
        String now = signature(probe.watch());
        ProbeResult last = results.get(probe.id());
        boolean recent =
            last != null
                && last.ranMicros() > 0
                && host.monotonicMicros() - last.ranMicros()
                    < Math.round(Probe.WATCHED_EVERY_SECONDS * 1_000_000);
        if (recent && now.equals(watched.get(probe.id()))) {
          return;
        }
        watched.put(probe.id(), now);
      }
      record(probe);
    } catch (RuntimeException e) {
      // A probe that fails is said in its result; nothing it does stops the schedule.
      host.log("Probe " + probe.id() + " failed to run: " + e);
    }
  }

  private ProbeResult record(Probe probe) {
    ProbeResult result = runner.run(probe);
    ProbeResult was = results.put(probe.id(), result);
    if (was != null
        && !was.status().equals(result.status())
        && !was.status().equals(ProbeResult.PENDING)) {
      host.log(
          "Probe "
              + probe.id()
              + " is now "
              + result.status()
              + (result.detail().isEmpty() ? "" : ": " + result.detail()));
    }
    return result;
  }

  /** The watched files' sizes and times, as one text: a change in any changes it. */
  private String signature(List<String> files) {
    Map<String, String> each = new LinkedHashMap<>();
    for (String file : files) {
      Path path = host.path(file);
      try {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        each.put(file, attributes.size() + "@" + attributes.lastModifiedTime().toMillis());
      } catch (IOException e) {
        each.put(file, "none");
      }
    }
    return each.toString();
  }

  @Override
  public void close() {
    schedule.shutdownNow();
  }
}
