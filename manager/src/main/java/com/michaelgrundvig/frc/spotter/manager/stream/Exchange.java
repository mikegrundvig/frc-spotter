package com.michaelgrundvig.frc.spotter.manager.stream;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A board's three buffers, passed between its thread and the robot loop without a lock or an
 * allocation (a triple buffer): the board's thread writes the back one and publishes it, swapping
 * it with the middle one; the loop takes the middle one, if it's newer, swapping it with the front
 * one it reads. Neither ever waits for the other, and each has a buffer the other never touches.
 * One writer thread and one reader thread.
 */
public final class Exchange {
  /** Set beside the middle buffer's index when it's newer than the reader's. */
  private static final int FRESH = 4;

  private final Table[] tables = {new Table(), new Table(), new Table()};
  private final AtomicInteger middle = new AtomicInteger(1);
  private int back;
  private int front = 2;
  private volatile String name;

  /**
   * @param name the board's name until it describes itself: its address
   */
  public Exchange(String name) {
    this.name = name;
  }

  /** The board's name: its hostname once it's described itself. Read from any thread. */
  public String name() {
    return name;
  }

  /** The board's thread names it, from its description. */
  void named(String hostname) {
    name = hostname;
  }

  /** The writer's buffer. */
  public Table back() {
    return tables[back];
  }

  /** Publishes the writer's buffer: the reader takes it next; the writer gets another. */
  public void publish() {
    back = middle.getAndSet(back | FRESH) & 3;
  }

  /** The reader's buffer. */
  public Table front() {
    return tables[front];
  }

  /**
   * Takes the newest published buffer, if there's one newer than the reader's: whether there was.
   */
  public boolean take() {
    if ((middle.get() & FRESH) == 0) {
      return false;
    }
    front = middle.getAndSet(front) & 3;
    return true;
  }
}
