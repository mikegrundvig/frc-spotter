package com.michaelgrundvig.frc.spotter.manager;

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

  /** The most log lines kept: the newest. */
  static final int MAX_LOG = 10_000;

  private final String action;
  private final Spotter.ActionDeclaration declaration;
  private final Map<String, Limits> overrides;
  private final @Nullable Link link;
  private final boolean ours;
  private final ArrayDeque<Spotter.LogEntry> log = new ArrayDeque<>();
  private final CompletableFuture<Run> done = new CompletableFuture<>();
  private String id = "";
  private State state = State.STARTING;
  private String why = "";
  private Spotter.Outcome outcome = Spotter.Outcome.OUTCOME_UNSPECIFIED;
  private List<Value> response = List.of();
  private boolean cancelAsked;
  private @Nullable CompletableFuture<Void> cancelling;
  private long changes;
  private Spotter.@Nullable RunResult result;
  private Level level = Level.UNAVAILABLE;
  private String reason = NOT_DONE;

  /**
   * @param declaration its action's declaration, from the board's description
   * @param link the board's, to cancel and fetch through; null for one that's refused at once
   * @param ours whether robot code asked for it, rather than another client of the board
   */
  Run(
      String action,
      Spotter.ActionDeclaration declaration,
      Map<String, Limits> overrides,
      @Nullable Link link,
      boolean ours) {
    this.action = action;
    this.declaration = declaration;
    this.overrides = overrides;
    this.link = link;
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
  public synchronized State state() {
    return state;
  }

  /** Whether it's done: finished, or refused. */
  public synchronized boolean done() {
    return state == State.FINISHED || state == State.REFUSED;
  }

  /** Completes with it once it's done: for code that waits off the loop, such as a test. */
  public CompletableFuture<Run> whenDone() {
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
   * whatever its pack says; once completed, the worst level among its {@link #response} fields, by
   * their limits (a field with no value and no {@code missing} rule counts as ok, as it raises no
   * alert). {@link Level#UNAVAILABLE} until it's done.
   */
  public synchronized Level level() {
    return level;
  }

  /**
   * Why it's at its level: why it was refused or didn't complete ({@code "timed out after 60s"}),
   * or its first response field at that level, in its pack's words ({@code "exit isn't 0"}); empty
   * when it's ok, and {@value #NOT_DONE} until it's done.
   */
  public synchronized String reason() {
    return reason;
  }

  /**
   * Its response, once it's finished: each field its action declares, in order, judged by its
   * limits (robot code's override, by {@code pack.action.field}, else its pack's). Empty before.
   * These values are the run's own: they're never reused.
   */
  public synchronized List<Value> response() {
    return response;
  }

  /** A field of its response, by name; null when there's none by that name (yet). */
  public synchronized @Nullable Value response(String name) {
    for (Value value : response) {
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
  public synchronized long changes() {
    return changes;
  }

  /**
   * Cancels it: at once if it started, or as soon as it does. The board stops it politely, then
   * firmly; its outcome says cancelled. Completes once the board has taken the cancel, or
   * exceptionally, saying why not.
   */
  public CompletableFuture<Void> cancel() {
    Link through;
    String run;
    synchronized (this) {
      if (done()) {
        return CompletableFuture.completedFuture(null);
      }
      cancelAsked = true;
      if (id.isEmpty() || link == null) {
        CompletableFuture<Void> later = cancelling;
        if (later == null) {
          later = new CompletableFuture<>();
          cancelling = later;
        }
        return later;
      }
      through = link;
      run = id;
    }
    return through.cancel(run);
  }

  /**
   * A {@code file} field of its response, as its bytes, fetched from the board: while the board
   * keeps the run.
   */
  public CompletableFuture<byte[]> file(String field) {
    Link through = link;
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
  void refused(String reason) {
    CompletableFuture<Void> later;
    synchronized (this) {
      if (done()) {
        return;
      }
      state = State.REFUSED;
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
    if (state == State.STARTING) {
      state = State.RUNNING;
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
      if (event.hasStarted() && state == State.STARTING) {
        state = State.RUNNING;
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
      state = State.FINISHED;
      result = sent.clone();
      outcome = sent.getOutcome();
      why = sent.getOutcomeMessage();
      response = judge(declaration, sent, overrides);
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
  synchronized void log(List<Spotter.LogEntry> kept) {
    if (kept.size() >= log.size()) {
      log.clear();
      log.addAll(kept);
      changes++;
    }
  }

  /** Takes what the stream said of its run before robot code had its id. */
  void takeFrom(Run earlier) {
    List<Spotter.LogEntry> lines;
    Spotter.RunResult finished;
    boolean began;
    synchronized (earlier) {
      lines = new ArrayList<>(earlier.log);
      finished = earlier.result;
      began = earlier.state != State.STARTING;
    }
    synchronized (this) {
      log.addAll(lines);
      if (began && state == State.STARTING) {
        state = State.RUNNING;
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
  static List<Value> judge(
      Spotter.ActionDeclaration declaration,
      Spotter.RunResult result,
      Map<String, Limits> overrides) {
    List<Value> values = new ArrayList<>();
    for (Spotter.FieldDeclaration field : declaration.getResponse()) {
      Value value =
          new Value(new Field(field, overrides.get(declaration.getId() + "." + field.getId())));
      Spotter.FieldValue sent = null;
      for (Spotter.FieldValue each : result.getResponse()) {
        if (each.getName().equals(field.getId())) {
          sent = each;
          break;
        }
      }
      if (sent != null) {
        Link.take(value, Utf8String.newEmptyInstance(), sent);
      } else {
        value.kind = Value.Kind.UNAVAILABLE;
        value.text = NOT_IN_RESPONSE;
      }
      Judge.judge(value);
      values.add(value);
    }
    return List.copyOf(values);
  }

  /** Why a declared response field has no value: the result didn't have it. */
  static final String NOT_IN_RESPONSE = "not in its response";

  /** Why a run that isn't done has no verdict. */
  static final String NOT_DONE = "not done yet";

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
    for (Value value : response) {
      if (value.level() == Level.FAILING && level != Level.FAILING
          || value.level() == Level.WARNING && level == Level.OK) {
        level = value.level();
        reason = Link.text(value);
      }
    }
  }
}
