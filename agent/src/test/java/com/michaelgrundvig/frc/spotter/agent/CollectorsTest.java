package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Collectors on their own schedules: at most four at once, and a slow one delays nobody. */
class CollectorsTest {
  @TempDir Path dir;

  /** A pack of collectors, each running {@code [<id>]} on its interval, filling one number. */
  private static Pack pack(String name, Map<String, Duration> every) {
    List<Pack.Collector> collectors = new ArrayList<>();
    every.forEach(
        (id, interval) ->
            collectors.add(
                new Pack.Collector(
                    id,
                    new Command.Run(List.of(id)),
                    interval,
                    Pack.Collector.TIMEOUT,
                    List.of(Field.of(id, Spotter.FieldType.FIELD_TYPE_NUMBER)))));
    return new Pack(
        name, "1", Packs.INSTALLED + "/" + name, false, collectors, List.of(), List.of());
  }

  private static Commands.Result printed(String output) {
    return new Commands.Result(
        Commands.Kind.RUN,
        Spotter.Outcome.OUTCOME_COMPLETED,
        "",
        0,
        output.getBytes(StandardCharsets.UTF_8),
        false,
        "");
  }

  @Test
  void valuesFollowTheirPacksAndCollectorsInOrder() throws Exception {
    Fixture fixture = new Fixture(dir);
    Pack a = pack("a", Map.of("one", Duration.ofSeconds(1)));
    Pack b =
        new Pack(
            "b",
            "1",
            "/b",
            false,
            List.of(
                new Pack.Collector(
                    "two",
                    new Command.Run(List.of("two")),
                    Duration.ofSeconds(1),
                    Pack.Collector.TIMEOUT,
                    List.of(
                        Field.of("x", Spotter.FieldType.FIELD_TYPE_NUMBER),
                        Field.of("y", Spotter.FieldType.FIELD_TYPE_TEXT)))),
            List.of(),
            List.of());
    assertThat(Collectors.slots(List.of(a, b)))
        .extracting(Collectors.Slot::first)
        .containsExactly(0, 1);
    assertThat(Collectors.values(List.of(a, b))).isEqualTo(3);
    ValueStore store = new ValueStore(3);
    assertThat(store.get(2).getUnavailable()).isEqualTo(ValueStore.NOT_YET);
    Runner runner =
        (folder, command, timeout, max) ->
            printed(
                ((Command.Run) command).program().equals("one")
                    ? "7"
                    : "{\"x\": 1.5, \"y\": \"up\"}");
    try (Collectors collectors = new Collectors(fixture.host, List.of(a, b), runner, store)) {
      collectors.runAll();
    }
    assertThat(store.get(0).getNumber()).isEqualTo(7);
    assertThat(store.get(0).getIndex()).isZero();
    assertThat(store.get(1).getNumber()).isEqualTo(1.5);
    assertThat(store.get(2).getText()).isEqualTo("up");
    assertThat(store.get(2).getIndex()).isEqualTo(2);
  }

  @Test
  void atMostFourRunAtOnce() throws Exception {
    Fixture fixture = new Fixture(dir);
    Map<String, Duration> every = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 10; i++) {
      every.put("c" + i, Duration.ofMillis(100));
    }
    AtomicInteger now = new AtomicInteger();
    AtomicInteger most = new AtomicInteger();
    AtomicInteger runs = new AtomicInteger();
    Runner runner =
        (folder, command, timeout, max) -> {
          int running = now.incrementAndGet();
          most.accumulateAndGet(running, Math::max);
          try {
            Thread.sleep(150);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            now.decrementAndGet();
            runs.incrementAndGet();
          }
          return printed("1");
        };
    try (Collectors collectors =
        new Collectors(fixture.host, List.of(pack("p", every)), runner, new ValueStore(10))) {
      collectors.start();
      Thread.sleep(1500);
    }
    assertThat(most.get()).isEqualTo(Collectors.RUNNING);
    assertThat(runs.get()).isGreaterThan(10);
  }

  @Test
  void aSlowOneSkipsItsTurnsAndNeverDelaysTheOthers() throws Exception {
    Fixture fixture = new Fixture(dir);
    Map<String, AtomicInteger> runs = new ConcurrentHashMap<>();
    Runner runner =
        (folder, command, timeout, max) -> {
          String id = ((Command.Run) command).program();
          runs.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
          if (id.equals("slow")) {
            try {
              Thread.sleep(1200);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return printed("1");
        };
    Map<String, Duration> every = new java.util.LinkedHashMap<>();
    every.put("slow", Duration.ofMillis(100));
    every.put("fast", Duration.ofMillis(100));
    try (Collectors collectors =
        new Collectors(fixture.host, List.of(pack("p", every)), runner, new ValueStore(2))) {
      collectors.start();
      Thread.sleep(1000);
    }
    // The slow one ran once, skipping its turns; the fast one, its first turn at 500 ms (the
    // first runs are spread over a second), ran every 100 ms meanwhile.
    assertThat(Objects.requireNonNull(runs.get("slow")).get()).isEqualTo(1);
    assertThat(Objects.requireNonNull(runs.get("fast")).get()).isGreaterThanOrEqualTo(4);
  }

  @Test
  void aCollectorThatStopsFillingItsFieldsIsLoggedOnceAndAgainWhenItRecovers() throws Exception {
    Fixture fixture = new Fixture(dir);
    List<Commands.Result> results =
        new ArrayList<>(
            List.of(
                printed("1"),
                Commands.Result.failed(
                    Commands.Kind.RUN, Spotter.Outcome.OUTCOME_TIMED_OUT, "timed out after 5s"),
                Commands.Result.failed(
                    Commands.Kind.RUN, Spotter.Outcome.OUTCOME_TIMED_OUT, "timed out after 5s"),
                printed("2")));
    Runner runner = (folder, command, timeout, max) -> results.remove(0);
    ValueStore store = new ValueStore(1);
    try (Collectors collectors =
        new Collectors(
            fixture.host, List.of(pack("p", Map.of("c", Duration.ofSeconds(1)))), runner, store)) {
      collectors.run(0);
      collectors.run(0);
      assertThat(store.get(0).getUnavailable()).isEqualTo("timed out after 5s");
      collectors.run(0);
      collectors.run(0);
    }
    assertThat(fixture.log)
        .containsExactly(
            "Collector p.c fills nothing from its output: timed out after 5s",
            "Collector p.c fills its fields again");
  }

  @Test
  void aRunnerThatThrowsLeavesItsFieldsUnavailable() throws Exception {
    Fixture fixture = new Fixture(dir);
    Runner runner =
        (folder, command, timeout, max) -> {
          throw new IllegalStateException("broken");
        };
    ValueStore store = new ValueStore(1);
    try (Collectors collectors =
        new Collectors(
            fixture.host, List.of(pack("p", Map.of("c", Duration.ofSeconds(1)))), runner, store)) {
      collectors.runAll();
    }
    assertThat(store.get(0).getUnavailable())
        .isEqualTo("the agent failed to run it: java.lang.IllegalStateException: broken");
  }

  @Test
  void theStoreCountsChangesAndAnswersWhatChangedSince() {
    ValueStore store = new ValueStore(3);
    store.set(
        0,
        List.of(
            Spotter.FieldValue.newInstance().setNumber(1),
            Spotter.FieldValue.newInstance().setText("a")));
    long seen = store.changes();
    assertThat(seen).isEqualTo(2);
    store.set(1, List.of(Spotter.FieldValue.newInstance().setText("a")));
    assertThat(store.changes()).isEqualTo(seen);
    store.set(1, List.of(Spotter.FieldValue.newInstance().setText("b")));
    Spotter.Values delta = store.since(seen, 7, 99);
    assertThat(delta.getComplete()).isFalse();
    assertThat(delta.getRevision()).isEqualTo(7);
    assertThat(delta.getTimeNanos()).isEqualTo(99);
    assertThat(delta.getValues()).hasSize(1);
    assertThat(delta.getValues().get(0).getIndex()).isEqualTo(1);
    Spotter.Values all = store.since(-1, 7, 100);
    assertThat(all.getComplete()).isTrue();
    assertThat(all.getValues()).hasSize(3);
    assertThat(store.size()).isEqualTo(3);
  }
}
