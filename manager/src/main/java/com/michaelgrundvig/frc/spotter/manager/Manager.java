package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.jspecify.annotations.Nullable;

/**
 * Spotter's manager, in the robot program: it keeps each coprocessor agent's stream open on a
 * thread of its own, judges each value against its limits, and gives the robot loop each board's
 * state, and the current alerts, without ever waiting on the network. Robot code can page a board's
 * logs, run its actions, and push it the team's packs; the manager pushes them itself, off the
 * field, to a board whose packs differ.
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
 * does a board's thread decoding its stream. Requests (a log page, a run, a push) go on each
 * board's own request threads, a few at most, each request within a deadline.
 */
public final class Manager implements AutoCloseable {
  private final Robot robot;
  private final Settings settings;
  private final Board[] boards;
  private final List<Board> boardList;
  private final Link[] links;
  private final long missing;
  private final Spotter.Description[] seen;
  private final ScheduledThreadPoolExecutor deadlines;
  private final @Nullable Signer signer;
  private final String keyProblem;
  private final String packsProblem;
  private long started;
  private boolean running;
  private boolean settled;
  private List<Alert> setup = List.of();
  private List<Alert> alerts = List.of();

  /** A manager of these agents, with the default settings and no recorder; {@link #start} it. */
  public Manager(Robot robot, List<String> agents) {
    this(robot, agents, Settings.DEFAULTS, Recorder.NONE);
  }

  /**
   * A manager of these agents; {@link #start} it. It reads its key, and hashes the team's packs, as
   * it's made: a key or packs it can't read are alerts, never a failure.
   *
   * @param robot what it needs of the robot: whether it's enabled, the field, its clock
   * @param agents each agent's address: {@code 10.12.34.11}, or {@code vision-front:5808}; on port
   *     5808 unless it says
   * @param settings its settings: {@link Settings#DEFAULTS}, or some changed
   * @param recorder where each board's description and values go as they're received, and its runs'
   *     events and pushes as they happen, to be logged
   * @throws IllegalArgumentException if an address isn't one, or is given twice
   */
  public Manager(Robot robot, List<String> agents, Settings settings, Recorder recorder) {
    this.robot = robot;
    this.settings = settings;
    this.missing = settings.missing().toNanos();
    Signer key = null;
    String problem = "";
    try {
      key = Signer.read(settings.key(), settings.publicKeyFile());
    } catch (NoSuchFileException e) {
      // No key pair, or half of one: reading works, and boards that require signatures say so.
    } catch (IOException e) {
      problem = Link.why(e);
    }
    signer = key;
    keyProblem = problem;
    TeamPacks packs = null;
    problem = "";
    Path folder = settings.packs().orElse(null);
    if (folder != null) {
      try {
        packs = TeamPacks.of(folder);
      } catch (IOException | RuntimeException e) {
        problem = Link.why(e);
      }
    }
    packsProblem = problem;
    // Each request's deadline: one thread for all of them, a cancelled one gone at once.
    deadlines =
        new ScheduledThreadPoolExecutor(
            1,
            work -> {
              Thread thread = new Thread(work, "Spotter deadlines");
              thread.setDaemon(true);
              return thread;
            });
    deadlines.setRemoveOnCancelPolicy(true);
    Set<String> given = new HashSet<>();
    boards = new Board[agents.size()];
    links = new Link[agents.size()];
    for (int i = 0; i < boards.length; i++) {
      String address = agents.get(i).strip();
      if (!given.add(address)) {
        throw new IllegalArgumentException("an agent's address is given twice: " + address);
      }
      boards[i] = new Board(address, settings);
      links[i] = new Link(boards[i], robot, settings, recorder, packs, signer, deadlines);
    }
    boardList = List.of(boards);
    seen = new Spotter.Description[boards.length];
    Arrays.fill(seen, Table.NONE);
    started = robot.nanos().getAsLong();
    setup = setupAlerts();
    alerts = setup;
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
    boolean described = false;
    boolean allSettled = true;
    for (int i = 0; i < boards.length; i++) {
      Board board = boards[i];
      changed |= board.update(now, started, missing);
      Spotter.Description description = board.description();
      if (description != seen[i]) {
        seen[i] = description;
        described = true;
      }
      allSettled &=
          description != Table.NONE
              || board.connection() == Connection.MISSING
              || board.connection() == Connection.OTHER_PROTOCOL;
    }
    if (described || allSettled != settled) {
      settled = allSettled;
      List<Alert> current = setupAlerts();
      if (!current.equals(setup)) {
        setup = current;
        changed = true;
      }
    }
    if (changed) {
      List<Alert> all = new ArrayList<>();
      for (Board board : boards) {
        all.addAll(board.alerts());
      }
      all.addAll(setup);
      alerts = List.copyOf(all);
    }
  }

  /**
   * The alerts about robot code's own setup: its key, when a board requires signatures and there's
   * none to sign with; its packs, when they can't be read; and one per limit override whose id
   * matches no value or response field on any board, once every board has described itself, or is
   * missing or on another protocol (so a board that's away doesn't hold the check back for ever,
   * and one still connecting does).
   */
  private List<Alert> setupAlerts() {
    List<Alert> found = new ArrayList<>();
    if (!packsProblem.isEmpty()) {
      found.add(
          new Alert(
              Level.WARNING,
              "",
              "the team's Spotter packs can't be read (" + packsProblem + "): nothing is pushed"));
    }
    boolean signatures = false;
    for (Spotter.Description description : seen) {
      signatures |= description.getRequiresSignatures();
    }
    if (signer == null && signatures) {
      found.add(
          new Alert(
              Level.WARNING,
              "",
              (keyProblem.isEmpty()
                      ? "no Spotter key pair on this controller ("
                          + settings.key()
                          + ", "
                          + settings.publicKeyFile()
                          + ")"
                      : "the Spotter key pair can't be read (" + keyProblem + ")")
                  + ": boards that require signatures will refuse its actions and pushes"));
    }
    if (settled && !settings.limits().isEmpty()) {
      Set<String> ids = new HashSet<>();
      for (Spotter.Description description : seen) {
        for (Spotter.FieldDeclaration value : description.getValues()) {
          ids.add(value.getId());
        }
        for (Spotter.ActionDeclaration action : description.getActions()) {
          for (Spotter.FieldDeclaration field : action.getResponse()) {
            ids.add(action.getId() + "." + field.getId());
          }
        }
      }
      for (String id : new TreeSet<>(settings.limits().keySet())) {
        if (!ids.contains(id)) {
          found.add(
              new Alert(
                  Level.WARNING,
                  "",
                  "Spotter's limits for " + id + " match no value or response field on any board"));
        }
      }
    }
    return List.copyOf(found);
  }

  /** The boards, in the order their addresses were given. */
  public List<Board> boards() {
    return boardList;
  }

  /**
   * The current alerts, as of the last {@link #update}, board by board in the order given: one per
   * missing board, or board on another protocol; otherwise one per value at warning or failing, one
   * for packs that differ from the robot's and won't be pushed now, and one while it reports
   * problems. Then robot code's own: its key, its packs, and limit overrides that match nothing.
   * The same list until one changes.
   */
  public List<Alert> alerts() {
    return alerts;
  }

  /** Its settings. */
  public Settings settings() {
    return settings;
  }

  /**
   * Pushes the team's packs to a board now, whatever the robot is doing: it's robot code's call.
   * The board must accept pushes, and, if it requires signatures, the manager needs its key.
   * Completes once the board has taken them (it then restarts with them, and its stream
   * reconnects), or exceptionally, saying why not.
   */
  public CompletableFuture<Void> push(Board board) {
    Link link = board.link();
    if (link == null || !boardList.contains(board)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException(board.name() + " isn't one of this manager's boards"));
    }
    return link.push(true);
  }

  /** A board's link: for tests, to drive it without a network. */
  Link link(int board) {
    return links[board];
  }

  /** The key that signs writes; null when there's none: for tests. */
  @Nullable Signer signer() {
    return signer;
  }

  /** Stops each board's thread, drops its connection, and its request threads. */
  @Override
  public void close() {
    for (Link link : links) {
      link.close();
    }
    deadlines.shutdownNow();
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
