package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs every pack's collectors, each on its own schedule, and keeps the values they fill. Bounded
 * so collectors never crowd out the software they watch: at most {@link #RUNNING} run at once (the
 * rest wait their turn, in order), each within its timeout, and a collector still running when it's
 * due again skips that turn rather than queueing behind itself, so a slow one never delays the
 * others.
 */
final class Collectors implements AutoCloseable {
  /** How many collectors run at once. */
  static final int RUNNING = 4;

  /** The most of a collector's output kept: a value or a small JSON object. */
  static final int MAX_OUTPUT = 64 * 1024;

  private final Host host;
  private final Runner commands;
  private final ValueStore store;
  private final List<Slot> slots;
  private final ScheduledExecutorService schedule =
      Executors.newSingleThreadScheduledExecutor(
          work -> {
            Thread thread = new Thread(work, "spotter-schedule");
            thread.setDaemon(true);
            return thread;
          });
  private final ThreadPoolExecutor running =
      new ThreadPoolExecutor(
          RUNNING,
          RUNNING,
          0,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<>(),
          work -> {
            Thread thread = new Thread(work, "spotter-collector");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * A collector and where its values are.
   *
   * @param pack its pack
   * @param collector the collector
   * @param first the index of its first field's value: its fields' values follow in its order
   */
  record Slot(Pack pack, Pack.Collector collector, int first) {
    /** Its name in the agent's log: {@code pack.collector}. */
    String name() {
      return pack.name() + "." + collector.id();
    }
  }

  /** A slot's state as it runs. */
  private static final class State {
    final AtomicBoolean busy = new AtomicBoolean();
    String failure = "";
  }

  private final List<State> states = new ArrayList<>();

  Collectors(Host host, List<Pack> packs, Runner commands, ValueStore store) {
    this.host = host;
    this.commands = commands;
    this.store = store;
    this.slots = slots(packs);
    for (int i = 0; i < slots.size(); i++) {
      states.add(new State());
    }
  }

  /**
   * Every collector of every pack, in order, with the index of its first value: packs in their
   * order, each pack's collectors in its order, each collector's fields in its order. The
   * description lists the values in the same order, so a value's index is its place there.
   */
  static List<Slot> slots(List<Pack> packs) {
    List<Slot> slots = new ArrayList<>();
    int index = 0;
    for (Pack pack : packs) {
      for (Pack.Collector collector : pack.collectors()) {
        slots.add(new Slot(pack, collector, index));
        index += collector.fields().size();
      }
    }
    return slots;
  }

  /** How many values the collectors fill. */
  static int values(List<Pack> packs) {
    int count = 0;
    for (Pack pack : packs) {
      for (Pack.Collector collector : pack.collectors()) {
        count += collector.fields().size();
      }
    }
    return count;
  }

  /** Starts each collector on its schedule, their first runs spread over the first second. */
  void start() {
    for (int i = 0; i < slots.size(); i++) {
      int at = i;
      long first = 1000L * i / Math.max(1, slots.size());
      schedule.scheduleAtFixedRate(
          () -> due(at), first, slots.get(i).collector().every().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** A collector's turn: run it, unless it's still running from its last. */
  private void due(int at) {
    State state = states.get(at);
    if (!state.busy.compareAndSet(false, true)) {
      return;
    }
    try {
      running.execute(
          () -> {
            try {
              run(at);
            } finally {
              state.busy.set(false);
            }
          });
    } catch (RuntimeException e) {
      state.busy.set(false);
    }
  }

  /** Runs every collector once, in order, on the caller's thread: as tests read a board. */
  void runAll() {
    for (int i = 0; i < slots.size(); i++) {
      run(i);
    }
  }

  /** Runs one collector once, and keeps what it filled. */
  void run(int at) {
    Slot slot = slots.get(at);
    Pack.Collector collector = slot.collector();
    List<Spotter.FieldValue> values;
    try {
      Commands.Result result =
          commands.run(slot.pack().folder(), collector.command(), collector.timeout(), MAX_OUTPUT);
      values = Fill.collector(collector.fields(), result, MAX_OUTPUT);
    } catch (RuntimeException | StackOverflowError e) {
      values = Fill.unavailable(collector.fields(), "the agent failed to run it: " + e);
    }
    store.set(slot.first(), values);
    note(at, values);
  }

  /** Logs a collector that stops filling its fields, once, and when it fills them again. */
  private void note(int at, List<Spotter.FieldValue> values) {
    String failure = "";
    if (!values.isEmpty() && values.stream().allMatch(Spotter.FieldValue::hasUnavailable)) {
      failure = values.get(0).getUnavailable();
    }
    State state = states.get(at);
    String was;
    synchronized (state) {
      was = state.failure;
      state.failure = failure;
    }
    if (!failure.equals(was)) {
      String name = slots.get(at).name();
      host.log(
          failure.isEmpty()
              ? "Collector " + name + " fills its fields again"
              : "Collector " + name + " fills nothing: " + failure);
    }
  }

  @Override
  public void close() {
    schedule.shutdownNow();
    running.shutdownNow();
  }
}
