package com.michaelgrundvig.frc.spotter.manager.packs;

import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.Recorder;
import com.michaelgrundvig.frc.spotter.manager.RobotSignals;
import com.michaelgrundvig.frc.spotter.manager.Settings;
import com.michaelgrundvig.frc.spotter.manager.requests.Requests;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a board's pushed packs the robot's: compares the hash the board describes with that of its
 * packs from the robot, and when they differ, pushes them over its stream while the robot is
 * disabled and off the field, once for each set of packs the board has, so a push that fails or
 * doesn't take is never repeated in a loop. A board that took a push restarts to read it; one that
 * hasn't within {@link #RESTART_MISSINGS} missing thresholds has failed the push, which is tried
 * once more. Says where they stand as its alert's text, built only when that changes.
 */
public final class PackSync {
  /**
   * How many missing thresholds a board that took a push has to restart (its stream ends, and opens
   * again), before the push has failed: an agent ends a moment after it answers.
   */
  static final int RESTART_MISSINGS = 5;

  /** Where the board's packs are, against the robot's. */
  enum State {
    /** The same. */
    SAME,
    /** They differ, and the board refuses pushes. */
    REFUSES,
    /** A push is on its way, or was taken and the agent is restarting. */
    PUSHING,
    /** They differ, and pushing them failed. */
    FAILED,
    /** They were pushed, and still differ. */
    STILL_DIFFER,
    /** They differ, and automatic pushes are off. */
    AUTOMATIC_OFF,
    /** They differ, and the robot is enabled or on the field. */
    WAITING
  }

  private final Board board;
  private final RobotSignals robot;
  private final Settings settings;
  private final Recorder recorder;
  private final TeamPacks packs;
  private final Requests requests;
  private final AtomicLong pushes = new AtomicLong();
  private State state = State.SAME;
  private @Nullable String failureSaid;
  private volatile String theirs = "";
  private volatile boolean pushing;
  private volatile boolean restarting;
  private volatile @Nullable String triedFrom;
  private volatile @Nullable String pushFailure;
  private volatile long restartingSince;
  private final long restartNanos;

  /** The packs a push over which didn't restart the board, to be tried once more. */
  private volatile @Nullable String retryOver;

  /** The packs a push was tried once more over: never a third time. */
  private volatile @Nullable String retriedOver;

  /**
   * @param packs the board's packs from the robot
   * @param requests the board's, which pushes go through
   */
  public PackSync(
      Board board,
      RobotSignals robot,
      Settings settings,
      Recorder recorder,
      TeamPacks packs,
      Requests requests) {
    this.board = board;
    this.robot = robot;
    this.settings = settings;
    this.recorder = recorder;
    this.packs = packs;
    this.requests = requests;
    this.restartNanos = RESTART_MISSINGS * settings.missing().toNanos();
  }

  /** Whether the board's packs were the robot's when last checked. */
  public boolean same() {
    return state == State.SAME;
  }

  /** The board described itself: the hash of its pushed packs. */
  public void described(String hash) {
    theirs = hash;
  }

  /** The board's stream opened: a push it took has restarted it. */
  public void opened() {
    restarting = false;
  }

  /** The board speaks another protocol: nothing's compared. */
  public void reset() {
    state = State.SAME;
  }

  /** How many pushes it's sent: for tests, to see that a failed one isn't sent again in a loop. */
  public long pushes() {
    return pushes.get();
  }

  /**
   * Where the board's packs are against the robot's, and what's to be done: a push, when they
   * differ, the board accepts pushes, automatic pushes are on, the robot is disabled and off the
   * field, and no push was tried over the packs the board has now.
   *
   * @return its alert's text when that changed (empty for none), or null when it didn't
   */
  public @Nullable String check(Spotter.Description description) {
    String has = description.getPushedPacks();
    boolean overdue = restartOverdue();
    // A push was tried over the packs it has: that's said, unless it's to be tried once more (but
    // not on the check that found its restart overdue, which says so first).
    boolean tried = has.equals(triedFrom) && (overdue || !has.equals(retryOver));
    State now;
    if (has.equals(packs.hash())) {
      now = State.SAME;
    } else if (description.getRefusesPushes()) {
      now = State.REFUSES;
    } else if (pushing || restarting) {
      now = State.PUSHING;
    } else if (tried) {
      now = pushFailure != null ? State.FAILED : State.STILL_DIFFER;
    } else if (!settings.pushAutomatically()) {
      now = State.AUTOMATIC_OFF;
    } else if (robot.enabled().getAsBoolean() || robot.fieldAttached().getAsBoolean()) {
      now = State.WAITING;
    } else {
      if (has.equals(retryOver)) {
        retriedOver = has;
        retryOver = null;
      }
      push(false);
      now = State.PUSHING;
    }
    String failure = pushFailure;
    if (now == state && (now != State.FAILED || failure == failureSaid)) {
      return null;
    }
    state = now;
    failureSaid = failure;
    String name = board.name() + "'s packs ";
    switch (now) {
      case REFUSES:
        return name + "differ from the robot's, and it refuses pushes";
      case FAILED:
        return name + "differ from the robot's, and pushing them failed: " + failure;
      case STILL_DIFFER:
        return name + "still differ from the robot's after a push";
      case AUTOMATIC_OFF:
        return name + "differ from the robot's, and automatic pushes are off";
      case WAITING:
        return name + "differ from the robot's: they'll be pushed off the field";
      default:
        return "";
    }
  }

  /**
   * Whether a board that took a push has gone past its time to restart: if so, the push has failed,
   * saying so, and is to be tried once more over the packs it has, unless it was already.
   */
  private boolean restartOverdue() {
    if (!restarting) {
      return false;
    }
    if (robot.nanos().getAsLong() - restartingSince <= restartNanos) {
      return false;
    }
    restarting = false;
    pushFailure =
        "it took them, and didn't restart to read them within " + restartNanos / 1_000_000 + " ms";
    String over = triedFrom;
    if (over != null && !over.equals(retriedOver)) {
      retryOver = over;
    }
    return true;
  }

  /**
   * Pushes the team's packs to the board, signed over its stream's challenge: automatically, or
   * forced by robot code. Completes once the board has taken them (it then restarts, and the stream
   * reconnects, describing them), or exceptionally, saying why not.
   */
  public CompletableFuture<Void> push(boolean forced) {
    synchronized (this) {
      if (pushing) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("a push to " + board.name() + " is under way"));
      }
      pushing = true;
    }
    pushes.incrementAndGet();
    triedFrom = theirs;
    CompletableFuture<Void> pushed = new CompletableFuture<>();
    requests.execute(
        why -> {
          pushFailure = why;
          pushing = false;
          pushed.completeExceptionally(new IOException(why));
        },
        () -> {
          requests.client().write("POST", Protocol.PACKS, packs.bundle(), Protocol.PROTOBUF);
          pushFailure = null;
          restartingSince = robot.nanos().getAsLong();
          restarting = true;
          pushing = false;
          try {
            recorder.pushed(board, packs.hash(), forced);
          } catch (RuntimeException e) {
            // A logging fault never fails a push.
          }
          pushed.complete(null);
        });
    return pushed;
  }
}
