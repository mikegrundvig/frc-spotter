package com.michaelgrundvig.frc.spotter.client;

import java.io.IOException;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Asks one coprocessor to power down, and watches it go, on a thread of its own: asks until its
 * agent takes the request (or refuses it, or the timeout passes), then tries its agent's port and
 * its software's once a period until neither answers. Each request is a generation of its own: a
 * thread from an earlier request, however late it runs, never writes over a later one's state.
 */
public final class PowerDowner implements AutoCloseable {
  /** The coprocessor, as powering it down sees it. */
  public interface Target {
    /**
     * Asks its agent to power down.
     *
     * @return the agent's answer's status: 202 when it took the request
     * @throws IOException when there's no answer
     */
    int ask() throws IOException;

    /** Whether its agent's port accepts a connection. */
    boolean agentAnswers();

    /** Whether its software's port accepts a connection. */
    boolean softwareAnswers();
  }

  /**
   * Where powering it down stands.
   *
   * @param asked whether it was asked
   * @param accepted whether its agent took the request: only then can it be gone
   * @param answer how its agent answered: "accepted", "refused: answered 404", or why there was no
   *     answer; empty until it did
   * @param tried whether its ports have been tried since
   * @param agentOpen whether its agent's port still accepted a connection
   * @param softwareOpen whether its software's did
   */
  public record State(
      boolean asked,
      boolean accepted,
      String answer,
      boolean tried,
      boolean agentOpen,
      boolean softwareOpen) {
    static final State NONE = new State(false, false, "", false, false, false);

    /** Whether it's gone: it took the request, and neither port answers since. */
    public boolean gone() {
      return accepted && tried && !agentOpen && !softwareOpen;
    }
  }

  /** How long a board that took the request is watched for, at most, before giving up on it. */
  static final long WATCH_MILLIS = 10 * 60 * 1000;

  private final String name;
  private final Target target;
  private final long periodMillis;
  private final long timeoutMillis;
  private final LongSupplier millisClock;

  private long generation;
  private State state = State.NONE;
  private volatile @Nullable Thread thread;

  /**
   * @param name the coprocessor's name, for its thread
   * @param periodMillis how often to ask again, and to try its ports
   * @param timeoutMillis how long to keep asking one that hasn't taken the request
   */
  public PowerDowner(String name, Target target, long periodMillis, long timeoutMillis) {
    this(name, target, periodMillis, timeoutMillis, System::currentTimeMillis);
  }

  public PowerDowner(
      String name, Target target, long periodMillis, long timeoutMillis, LongSupplier millisClock) {
    this.name = name;
    this.target = target;
    this.periodMillis = periodMillis;
    this.timeoutMillis = timeoutMillis;
    this.millisClock = millisClock;
  }

  /** Where it stands: safe from any thread. */
  public synchronized State state() {
    return state;
  }

  /** Asks it to power down: a new request, which ends any earlier one's thread. */
  public void ask() {
    long mine;
    synchronized (this) {
      mine = ++generation;
      state = new State(true, false, "", false, true, true);
    }
    stopThread();
    Thread started = new Thread(() -> run(mine), "Coprocessor " + name + " powering down");
    started.setDaemon(true);
    thread = started;
    started.start();
  }

  /** Stops asking and watching; where it stood stays. */
  public void stop() {
    synchronized (this) {
      generation++;
    }
    stopThread();
  }

  private void stopThread() {
    Thread was = thread;
    if (was != null) {
      was.interrupt();
    }
  }

  /** Writes where it stands, if this is still the latest request's thread. */
  private synchronized boolean publish(long mine, State now) {
    if (mine != generation) {
      return false;
    }
    state = now;
    return true;
  }

  private void run(long mine) {
    long started = millisClock.getAsLong();
    boolean accepted = false;
    boolean refused = false;
    String answer = "";
    while (!Thread.currentThread().isInterrupted()) {
      if (!accepted && !refused) {
        try {
          int status = target.ask();
          accepted = status == 202;
          // A 4xx is the agent saying no (not the robot's address, no such request): asking again
          // won't change its mind. Anything else (it failed) is asked again.
          refused = status >= 400 && status < 500;
          answer =
              accepted
                  ? "accepted"
                  : refused ? "refused: answered " + status : "answered " + status;
        } catch (IOException e) {
          answer = Poller.why(e);
        }
      }
      boolean agentOpen = target.agentAnswers();
      boolean softwareOpen = target.softwareAnswers();
      State now = new State(true, accepted, answer, true, agentOpen, softwareOpen);
      if (!publish(mine, now) || now.gone() || refused) {
        return;
      }
      long elapsed = millisClock.getAsLong() - started;
      if ((!accepted && elapsed >= timeoutMillis) || elapsed >= WATCH_MILLIS) {
        return;
      }
      try {
        Thread.sleep(periodMillis);
      } catch (InterruptedException e) {
        return;
      }
    }
  }

  @Override
  public void close() {
    stop();
  }
}
