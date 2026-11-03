package com.michaelgrundvig.frc.spotter.manager.actions;

import com.michaelgrundvig.frc.spotter.manager.Level;
import com.michaelgrundvig.frc.spotter.manager.Limits;
import com.michaelgrundvig.frc.spotter.manager.Run;
import com.michaelgrundvig.frc.spotter.manager.limits.Field;
import com.michaelgrundvig.frc.spotter.manager.limits.Judge;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.manager.requests.Requests;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.Utf8String;

/**
 * An action's run as the manager follows it, from the board's stream, on the manager's threads:
 * whether it started, the lines it logs, and once it's finished, its outcome and its response,
 * judged. Robot code reads it through its {@link Run}; a run robot code didn't start is followed
 * the same way, with no {@code Run} at all. Every call is safe from any thread.
 *
 * <p>A run whose result came while the board was away (the agent restarted, the network dropped)
 * gets it when the board is back: the agent keeps finished runs until the board reboots, and lists
 * them on every connect. One the board no longer keeps when it's back is lost.
 */
public final class Progress {
  /** The most log lines kept: the newest. */
  static final int MAX_LOG = 10_000;

  private final String action;
  private final Spotter.ActionDeclaration declaration;
  private final Map<String, Limits> overrides;
  private final @Nullable Requests requests;
  private final boolean ours;
  private final ArrayDeque<Spotter.LogEntry> log = new ArrayDeque<>();
  private final CompletableFuture<Progress> done = new CompletableFuture<>();
  private String id = "";
  private Run.State state = Run.State.STARTING;
  private String why = "";
  private Spotter.Outcome outcome = Spotter.Outcome.OUTCOME_UNSPECIFIED;
  private List<Reading> readings = List.of();
  private boolean cancelAsked;
  private @Nullable CompletableFuture<Void> cancelling;
  private long changes;
  private Spotter.@Nullable RunResult result;
  private Level level = Level.UNAVAILABLE;
  private String reason = Run.NOT_DONE;

  /**
   * @param declaration its action's declaration, from the board's description
   * @param requests the board's, to cancel and fetch through; null for one refused at once
   * @param ours whether robot code asked for it, rather than another client of the board
   */
  public Progress(
      String action,
      Spotter.ActionDeclaration declaration,
      Map<String, Limits> overrides,
      @Nullable Requests requests,
      boolean ours) {
    this.action = action;
    this.declaration = declaration;
    this.overrides = overrides;
    this.requests = requests;
    this.ours = ours;
  }

  /** Its action's id: {@code pack.action}, or {@code core.power-off}, {@code core.reboot}. */
  public String action() {
    return action;
  }

  /** Its run's id on the board, once it started; empty before. */
  public synchronized String id() {
    return id;
  }

  /** Where it is. */
  public synchronized Run.State state() {
    return state;
  }

  /** Whether it's done: finished, or refused. */
  public synchronized boolean done() {
    return state == Run.State.FINISHED || state == Run.State.REFUSED;
  }

  /** Completes with it once it's done. */
  public CompletableFuture<Progress> whenDone() {
    return done;
  }

  /** Why it was refused, or how it finished in words; empty while it runs. */
  public synchronized String why() {
    return why;
  }

  /**
   * How it finished: completed, or why not (timed out, cancelled, lost...); unspecified until it
   * has.
   */
  public synchronized Spotter.Outcome outcome() {
    return outcome;
  }

  /** The lines it's logged so far (its standard error, line by line), oldest first. */
  public synchronized List<Spotter.LogEntry> log() {
    return List.copyOf(log);
  }

  /**
   * Its verdict, as the design judges a run: {@link Level#FAILING} when it was refused, or finished
   * without completing (it timed out, was cancelled or lost, couldn't start or reach its URL),
   * whatever its pack says; once completed, the worst level among its {@link #readings}, by their
   * limits (a field with no value and no {@code missing} rule counts as ok, as it raises no alert).
   * {@link Level#UNAVAILABLE} until it's done.
   */
  public synchronized Level level() {
    return level;
  }

  /**
   * Why it's at its level: why it was refused or didn't complete ({@code "timed out after 60s"}),
   * or its first response field at that level, in its pack's words ({@code "exit isn't 0"}); empty
   * when it's ok, and {@value Run#NOT_DONE} until it's done.
   */
  public synchronized String reason() {
    return reason;
  }

  /**
   * Its response, once it's finished: each field its action declares, in order, judged by its
   * limits. Empty before. Never changed once finished.
   */
  public synchronized List<Reading> readings() {
    return readings;
  }

  /**
   * How many times it's changed: started, logged a line, finished. A loop that keeps the count it
   * last saw knows something changed when this differs.
   */
  public synchronized long changes() {
    return changes;
  }

  /**
   * Cancels it: at once if it started, or as soon as it does. The board stops it politely, then
   * firmly; its outcome says cancelled. Completes once the board has taken the cancel, or
   * exceptionally, saying why not.
   */
  public CompletableFuture<Void> cancel() {
    Requests through;
    String run;
    synchronized (this) {
      if (done()) {
        return CompletableFuture.completedFuture(null);
      }
      cancelAsked = true;
      if (id.isEmpty() || requests == null) {
        CompletableFuture<Void> later = cancelling;
        if (later == null) {
          later = new CompletableFuture<>();
          cancelling = later;
        }
        return later;
      }
      through = requests;
      run = id;
    }
    return through.cancel(run);
  }

  /**
   * A {@code file} field of its response, as its bytes, fetched from the board: while the board
   * keeps the run.
   */
  public CompletableFuture<byte[]> file(String field) {
    Requests through = requests;
    String run = id();
    if (through == null || run.isEmpty()) {
      return CompletableFuture.failedFuture(
          new IllegalStateException(action + " hasn't started, so it has no files"));
    }
    return through.file(run, field);
  }

  @Override
  public synchronized String toString() {
    return action
        + (id.isEmpty() ? "" : " (" + id + ")")
        + ": "
        + state
        + (why.isEmpty() ? "" : ", " + why);
  }

  // ---- kept up to date by the manager ----

  boolean ours() {
    return ours;
  }

  /** It never started, and why. */
  public void refused(String reason) {
    CompletableFuture<Void> later;
    synchronized (this) {
      if (done()) {
        return;
      }
      state = Run.State.REFUSED;
      why = reason;
      level = Level.FAILING;
      this.reason = reason;
      changes++;
      later = cancelling;
    }
    if (later != null) {
      later.complete(null);
    }
    done.complete(this);
  }

  /** The board started it: its run's id. Whether a cancel was asked for meanwhile. */
  synchronized boolean started(String run) {
    id = run;
    if (state == Run.State.STARTING) {
      state = Run.State.RUNNING;
    }
    changes++;
    return cancelAsked;
  }

  /** The cancel asked for before it started: to complete once it's sent. */
  synchronized @Nullable CompletableFuture<Void> cancelling() {
    return cancelling;
  }

  /** An event from the stream: it started, logged a line, or finished. */
  void event(Spotter.RunEvent event) {
    if (event.hasFinished()) {
      finish(event.getFinished());
      return;
    }
    synchronized (this) {
      if (done()) {
        return;
      }
      if (event.hasStarted() && state == Run.State.STARTING) {
        state = Run.State.RUNNING;
      } else if (event.hasLog()) {
        log.addLast(event.getLog().clone());
        while (log.size() > MAX_LOG) {
          log.removeFirst();
        }
      }
      changes++;
    }
  }

  /** It finished: its outcome, and its response, judged. */
  void finish(Spotter.RunResult sent) {
    synchronized (this) {
      if (done()) {
        return;
      }
      state = Run.State.FINISHED;
      result = sent.clone();
      outcome = sent.getOutcome();
      why = sent.getOutcomeMessage();
      readings = judge(declaration, sent, overrides);
      verdict();
      changes++;
    }
    done.complete(this);
  }

  /** It was going, and the board no longer keeps it. */
  void lost(String reason) {
    finish(
        Spotter.RunResult.newInstance()
            .setOutcome(Spotter.Outcome.OUTCOME_LOST)
            .setOutcomeMessage(reason));
  }

  /** Its log as the board keeps it: what it logged while the board was away. */
  public synchronized void log(List<Spotter.LogEntry> kept) {
    if (kept.size() >= log.size()) {
      log.clear();
      log.addAll(kept);
      changes++;
    }
  }

  /** Takes what the stream said of its run before robot code had its id. */
  void takeFrom(Progress earlier) {
    List<Spotter.LogEntry> lines;
    Spotter.RunResult finished;
    boolean began;
    synchronized (earlier) {
      lines = new ArrayList<>(earlier.log);
      finished = earlier.result;
      began = earlier.state != Run.State.STARTING;
    }
    synchronized (this) {
      log.addAll(lines);
      if (began && state == Run.State.STARTING) {
        state = Run.State.RUNNING;
      }
      changes++;
    }
    if (finished != null) {
      finish(finished);
    }
  }

  /**
   * A result's response, judged: each field the action declares, in order, by name from the result;
   * one it lacks is unavailable.
   */
  static List<Reading> judge(
      Spotter.ActionDeclaration declaration,
      Spotter.RunResult result,
      Map<String, Limits> overrides) {
    List<Reading> values = new ArrayList<>();
    for (Spotter.FieldDeclaration field : declaration.getResponse()) {
      Reading value =
          new Reading(new Field(field, overrides.get(declaration.getId() + "." + field.getId())));
      Spotter.FieldValue sent = null;
      for (Spotter.FieldValue each : result.getResponse()) {
        if (each.getName().equals(field.getId())) {
          sent = each;
          break;
        }
      }
      if (sent != null) {
        value.take(Utf8String.newEmptyInstance(), sent);
      } else {
        value.kind = Reading.Kind.UNAVAILABLE;
        value.text = NOT_IN_RESPONSE;
      }
      Judge.judge(value);
      values.add(value);
    }
    return List.copyOf(values);
  }

  /** Why a declared response field has no value: the result didn't have it. */
  static final String NOT_IN_RESPONSE = "not in its response";

  /** Judges it, once finished: its outcome first, then its response's worst. */
  private void verdict() {
    if (outcome != Spotter.Outcome.OUTCOME_COMPLETED) {
      level = Level.FAILING;
      reason =
          why.isEmpty()
              ? outcome
                  .name()
                  .substring("OUTCOME_".length())
                  .toLowerCase(Locale.ROOT)
                  .replace('_', ' ')
              : why;
      return;
    }
    level = Level.OK;
    reason = "";
    for (Reading value : readings) {
      if (value.level == Level.FAILING && level != Level.FAILING
          || value.level == Level.WARNING && level == Level.OK) {
        level = value.level;
        reason = value.why();
      }
    }
  }
}
