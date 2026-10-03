package com.michaelgrundvig.frc.spotter.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spotter's manager, in the robot program: it keeps each coprocessor agent's stream open on a
 * thread of its own, judges each value against its limits, and gives the robot loop each board's
 * state, and the current alerts, without ever waiting on the network.
 *
 * <pre>{@code
 * Manager spotter = new Manager(robot, List.of("10.12.34.11", "vision-back:5808"));
 * spotter.start();
 * // Each loop, first:
 * spotter.update();
 * for (Alert alert : spotter.alerts()) { ... }
 * }</pre>
 *
 * <p>{@link #update}, and everything it updates (each {@link Board}, its {@link Value}s, the
 * alerts), belong to one thread, the robot loop's. In steady state an update allocates nothing, nor
 * does a board's thread decoding its stream.
 */
public final class Manager implements AutoCloseable {
  private final Robot robot;
  private final Settings settings;
  private final Board[] boards;
  private final List<Board> boardList;
  private final Link[] links;
  private final long missing;
  private long started;
  private boolean running;
  private List<Alert> alerts = List.of();

  /** A manager of these agents, with the default settings and no recorder; {@link #start} it. */
  public Manager(Robot robot, List<String> agents) {
    this(robot, agents, Settings.DEFAULTS, Recorder.NONE);
  }

  /**
   * A manager of these agents; {@link #start} it.
   *
   * @param robot what it needs of the robot: whether it's enabled, the field, its clock
   * @param agents each agent's address: {@code 10.12.34.11}, or {@code vision-front:5808}; on port
   *     5808 unless it says
   * @param settings its settings: {@link Settings#DEFAULTS}, or some changed
   * @param recorder where each board's description and values go as they're received, to be logged
   * @throws IllegalArgumentException if an address isn't one, or is given twice
   */
  public Manager(Robot robot, List<String> agents, Settings settings, Recorder recorder) {
    this.robot = robot;
    this.settings = settings;
    this.missing = settings.missing().toNanos();
    Set<String> seen = new HashSet<>();
    boards = new Board[agents.size()];
    links = new Link[agents.size()];
    for (int i = 0; i < boards.length; i++) {
      String address = agents.get(i).strip();
      if (!seen.add(address)) {
        throw new IllegalArgumentException("an agent's address is given twice: " + address);
      }
      boards[i] = new Board(address, settings);
      links[i] = new Link(boards[i], robot, settings, recorder);
    }
    boardList = List.of(boards);
    started = robot.nanos().getAsLong();
  }

  /** Starts each board's thread, which connects to its agent and keeps its stream open. */
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    started = robot.nanos().getAsLong();
    for (Link link : links) {
      link.start();
    }
  }

  /**
   * Brings everything up to date, without waiting: takes each board's state as its thread last
   * published it, judges on the robot's clock which boards are missing, and updates the alerts.
   * Call it once each loop, before reading anything.
   */
  public void update() {
    long now = robot.nanos().getAsLong();
    boolean changed = false;
    for (Board board : boards) {
      changed |= board.update(now, started, missing);
    }
    if (changed) {
      List<Alert> all = new ArrayList<>();
      for (Board board : boards) {
        all.addAll(board.alerts());
      }
      alerts = List.copyOf(all);
    }
  }

  /** The boards, in the order their addresses were given. */
  public List<Board> boards() {
    return boardList;
  }

  /**
   * The current alerts, as of the last {@link #update}, board by board in the order given: one per
   * missing board, or board on another protocol; otherwise one per value at warning or failing. The
   * same list until one changes.
   */
  public List<Alert> alerts() {
    return alerts;
  }

  /** Its settings. */
  public Settings settings() {
    return settings;
  }

  /** A board's link: for tests, to drive it without a network. */
  Link link(int board) {
    return links[board];
  }

  /** Stops each board's thread and drops its connection. */
  @Override
  public void close() {
    for (Link link : links) {
      link.close();
    }
    for (Link link : links) {
      try {
        link.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
