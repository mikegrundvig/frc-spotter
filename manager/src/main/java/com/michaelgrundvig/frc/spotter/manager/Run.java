package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.manager.actions.Progress;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * An action's run, as robot code follows it ({@link Board#run}): whether it started, the lines it
 * logs as it goes, and once it's finished, its outcome and its response, each field judged by its
 * limits. It can be cancelled. The manager keeps it up to date from the board's stream, on the
 * manager's threads; every call here is safe from any thread and never waits on the network.
 *
 * <p>A run whose result came while the board was away (the agent restarted, the network dropped)
 * gets it when the board is back: the agent keeps finished runs until the board reboots, and lists
 * them on every connect. One the board no longer keeps when it's back is lost.
 */
public final class Run {
  /** Where a run is. */
  public enum State {
    /** Asked for: the board hasn't said it started yet. */
    STARTING,
    /** It started, and hasn't finished. */
    RUNNING,
    /** It finished: {@link #outcome} says how. */
    FINISHED,
    /** It never started: refused by the manager or the board, or the board couldn't be asked. */
    REFUSED
  }

  /** Why a run that isn't done has no verdict. */
  public static final String NOT_DONE = "not done yet";

  private final Progress progress;
  private final CompletableFuture<Run> whenDone;
  private @Nullable List<Value> response;

  /** Robot code's view of a run the manager follows. */
  Run(Progress progress) {
    this.progress = progress;
    this.whenDone = progress.whenDone().thenApply(done -> this);
  }

  /** Its action's id: {@code pack.action}, or {@code core.power-off}, {@code core.reboot}. */
  public String action() {
    return progress.action();
  }

  /** Its run's id on the board, once it started; empty before. */
  public String id() {
    return progress.id();
  }

  /** Where it is. */
  public State state() {
    return progress.state();
  }

  /** Whether it's done: finished, or refused. */
  public boolean done() {
    return progress.done();
  }

  /** Completes with it once it's done: for code that waits off the loop, such as a test. */
  public CompletableFuture<Run> whenDone() {
    return whenDone;
  }

  /** Why it was refused, or how it finished in words; empty while it runs. */
  public String why() {
    return progress.why();
  }

  /**
   * How it finished: completed, or why not (timed out, cancelled, lost...); unspecified until it
   * has.
   */
  public Spotter.Outcome outcome() {
    return progress.outcome();
  }

  /** The lines it's logged so far (its standard error, line by line), oldest first. */
  public List<Spotter.LogEntry> log() {
    return progress.log();
  }

  /**
   * Its verdict, as the design judges a run: {@link Level#FAILING} when it was refused, or finished
   * without completing (it timed out, was cancelled or lost, couldn't start or reach its URL),
   * whatever its pack says; once completed, the worst level among its {@link #response} fields, by
   * their limits (a field with no value and no {@code missing} rule counts as ok, as it raises no
   * alert). {@link Level#UNAVAILABLE} until it's done.
   */
  public Level level() {
    return progress.level();
  }

  /**
   * Why it's at its level: why it was refused or didn't complete ({@code "timed out after 60s"}),
   * or its first response field at that level, in its pack's words ({@code "exit isn't 0"}); empty
   * when it's ok, and {@value #NOT_DONE} until it's done.
   */
  public String reason() {
    return progress.reason();
  }

  /**
   * Its response, once it's finished: each field its action declares, in order, judged by its
   * limits (robot code's override, by {@code pack.action.field}, else its pack's). Empty before.
   * These values are the run's own, and don't change once it's done.
   */
  public synchronized List<Value> response() {
    List<Value> made = response;
    if (made != null) {
      return made;
    }
    if (!progress.done()) {
      return List.of();
    }
    List<Value> values = new ArrayList<>();
    for (Reading reading : progress.readings()) {
      Value value = new Value(reading.field);
      value.copyFrom(reading);
      values.add(value);
    }
    made = List.copyOf(values);
    response = made;
    return made;
  }

  /** A field of its response, by name; null when there's none by that name (yet). */
  public @Nullable Value response(String name) {
    for (Value value : response()) {
      if (value.id().equals(name)) {
        return value;
      }
    }
    return null;
  }

  /**
   * How many times it's changed: started, logged a line, finished. A loop that keeps the count it
   * last saw knows something changed when this differs.
   */
  public long changes() {
    return progress.changes();
  }

  /**
   * Cancels it: at once if it started, or as soon as it does. The board stops it politely, then
   * firmly; its outcome says cancelled. Completes once the board has taken the cancel, or
   * exceptionally, saying why not.
   */
  public CompletableFuture<Void> cancel() {
    return progress.cancel();
  }

  /**
   * A {@code file} field of its response, as its bytes, fetched from the board: while the board
   * keeps the run.
   */
  public CompletableFuture<byte[]> file(String field) {
    return progress.file(field);
  }

  @Override
  public String toString() {
    return progress.toString();
  }
}
