package com.michaelgrundvig.frc.spotter.client;

import java.io.IOException;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Asks one coprocessor how it is, once a period, on a thread of its own, so the robot loop never
 * waits on the network: the loop reads only the latest {@link Snapshot}, which this replaces whole
 * after every poll. A poll that fails (no answer within its timeouts, or an answer that can't be
 * read) keeps the last answer, says why, and the next poll comes a period after this one started.
 *
 * <p>Times are on the robot's clock, which the loop's age of a snapshot is measured against; the
 * period is on the computer's own, since the network keeps that time whatever the simulator does.
 *
 * @param <T> what an answer holds
 */
public final class Poller<T> implements AutoCloseable {
  /**
   * One poll's worth of work: asks the coprocessor, within its own timeouts.
   *
   * @param <T> what an answer holds
   */
  @FunctionalInterface
  public interface Fetch<T> {
    /**
     * @throws IOException saying why there's no answer: no connection, no reply in time, or a reply
     *     that can't be read
     */
    T fetch() throws IOException;
  }

  /**
   * What the poller last heard.
   *
   * @param polls how many polls have finished, answered or not: 0 before the first
   * @param polledNanos when the latest poll finished, on the robot's clock (0 before the first)
   * @param answered whether the latest poll was answered
   * @param error why it wasn't, or empty
   * @param answer the latest answer, however old: null before the first
   * @param answeredNanos when that answer was given, on the robot's clock: halfway between asking
   *     and hearing, as a clock sync estimates the far end's moment (0 before the first)
   * @param <T> what an answer holds
   */
  public record Snapshot<T>(
      long polls,
      long polledNanos,
      boolean answered,
      String error,
      @Nullable T answer,
      long answeredNanos) {
    /** Before the first poll. */
    static <T> Snapshot<T> none() {
      return new Snapshot<>(0, 0, false, "not asked yet", null, 0);
    }
  }

  private final Fetch<T> fetch;
  private final LongSupplier robotClock;
  private final long periodMillis;
  private final Thread thread;
  private volatile Snapshot<T> latest = Snapshot.none();

  /**
   * Ready to poll; {@link #start} starts it.
   *
   * @param name the coprocessor's name, for the thread's
   * @param fetch one poll, within its own timeouts
   * @param periodSeconds how often to poll: a poll starts this long after the last one started, or
   *     at once if that one took longer
   * @param robotClock the robot's time in nanoseconds
   */
  public Poller(String name, Fetch<T> fetch, double periodSeconds, LongSupplier robotClock) {
    if (!(periodSeconds > 0)) {
      throw new IllegalArgumentException("a poll's period must be positive: " + periodSeconds);
    }
    this.fetch = fetch;
    this.robotClock = robotClock;
    periodMillis = Math.round(periodSeconds * 1000);
    thread = new Thread(this::run, "Coprocessor " + name);
    thread.setDaemon(true);
  }

  /** Starts polling, on the poller's own thread. */
  public void start() {
    thread.start();
  }

  /** The latest poll's result: safe from any thread, and never waits. */
  public Snapshot<T> latest() {
    return latest;
  }

  /** Stops polling; a poll in progress is abandoned at its timeouts. */
  @Override
  public void close() {
    thread.interrupt();
  }

  private void run() {
    while (!Thread.currentThread().isInterrupted()) {
      long started = System.nanoTime();
      pollOnce();
      long waitMillis = periodMillis - (System.nanoTime() - started) / 1_000_000;
      if (waitMillis > 0) {
        try {
          Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }

  /**
   * Polls once, on the caller's thread, and publishes the result. Nothing a poll throws stops the
   * poller: it's the reason the poll failed.
   */
  public void pollOnce() {
    long asked = robotClock.getAsLong();
    Snapshot<T> was = latest;
    Snapshot<T> now;
    try {
      T answer = fetch.fetch();
      long heard = robotClock.getAsLong();
      now = new Snapshot<>(was.polls() + 1, heard, true, "", answer, asked + (heard - asked) / 2);
    } catch (IOException | RuntimeException e) {
      now =
          new Snapshot<>(
              was.polls() + 1,
              robotClock.getAsLong(),
              false,
              why(e),
              was.answer(),
              was.answeredNanos());
    }
    latest = now;
  }

  /** Why a poll failed, in a few words: the exception's message, or its kind without one. */
  public static String why(Exception e) {
    String message = e.getMessage();
    String kind = e.getClass().getSimpleName();
    return message == null || message.isBlank() ? kind : message.strip();
  }
}
