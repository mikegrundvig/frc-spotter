package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.manager.packs.TeamPacks;
import com.michaelgrundvig.frc.spotter.manager.requests.AgentClient;
import com.michaelgrundvig.frc.spotter.manager.requests.Signer;
import com.michaelgrundvig.frc.spotter.manager.stream.Link;
import com.michaelgrundvig.frc.spotter.manager.stream.Table;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.nio.file.Files;
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
 * BoardDefinition front = new BoardDefinition("vision-front", "debian", "detector");
 * Manager spotter = new Manager(robot, List.of(front, new BoardDefinition("10.12.34.12")));
 * spotter.start();
 * Board vision = spotter.board("vision-front");      // once: the robot loop reads it from then on
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
  private final RobotSignals robot;
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
  private final @Nullable TeamPacks teamPacks;
  private final List<Alert> tooLarge = new ArrayList<>();
  private long started;
  private volatile boolean running;
  private boolean settled;
  private List<Alert> setup = List.of();
  private List<Alert> alerts = List.of();

  /** A manager of these boards, with the default settings and no recorder; {@link #start} it. */
  public Manager(RobotSignals robot, List<BoardDefinition> boards) {
    this(robot, boards, Settings.DEFAULTS, Recorder.NONE);
  }

  /**
   * A manager of these boards; {@link #start} it. It reads its key, and the team's packs, as it's
   * made: a key or packs it can't read are alerts, never a failure.
   *
   * @param robot what it needs of the robot: whether it's enabled, the field, its clock
   * @param boards the boards, each its address and the team's packs it's to have
   * @param settings its settings: {@link Settings#DEFAULTS}, or some changed
   * @param recorder where each board's description and values go as they're received, and its runs'
   *     events and pushes as they happen, to be logged
   * @throws IllegalArgumentException if a board's address isn't one, or two boards have the same
   *     one
   */
  public Manager(
      RobotSignals robot, List<BoardDefinition> boards, Settings settings, Recorder recorder) {
    Set<String> given = new HashSet<>();
    for (BoardDefinition board : boards) {
      AgentClient.address(board.address());
      if (!given.add(board.address())) {
        throw new IllegalArgumentException("two boards have the address " + board.address());
      }
    }
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
      problem = AgentClient.why(e);
    }
    signer = key;
    keyProblem = problem;
    TeamPacks packs = null;
    problem = "";
    Path folder = settings.packs().orElse(null);
    if (folder != null) {
      try {
        if (!Files.isDirectory(folder)) {
          throw new NoSuchFileException(folder + " isn't a folder");
        }
        packs = TeamPacks.of(folder);
      } catch (IOException | RuntimeException e) {
        problem = AgentClient.why(e);
      }
    }
    packsProblem = problem;
    teamPacks = packs;
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
    this.boards = new Board[boards.size()];
    links = new Link[this.boards.length];
    for (int i = 0; i < this.boards.length; i++) {
      Board board = new Board(boards.get(i), settings);
      this.boards[i] = board;
      // Its packs: exactly those it names.
      TeamPacks its = packs == null ? null : packs.select(board.definition().packs());
      if (its != null && !its.tooLarge().isEmpty()) {
        tooLarge.add(
            new Alert(
                "setup/packs/" + board.address() + "/too-large",
                Level.WARNING,
                "",
                "the team's Spotter packs for "
                    + board.address()
                    + " are "
                    + its.tooLarge()
                    + ": nothing is pushed to it"));
        its = null;
      }
      links[i] =
          new Link(board, board.exchange(), robot, settings, recorder, its, signer, deadlines);
      board.link(links[i]);
    }
    boardList = List.of(this.boards);
    seen = new Spotter.Description[this.boards.length];
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
      changed |= board.update(now, started, missing, running);
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
              "setup/packs",
              Level.WARNING,
              "",
              "the team's Spotter packs can't be read (" + packsProblem + "): nothing is pushed"));
    }
    found.addAll(tooLarge);
    for (Board board : boards) {
      List<String> named = board.definition().packs();
      if (named.isEmpty()) {
        continue;
      }
      if (settings.packs().isEmpty()) {
        found.add(
            new Alert(
                "setup/packs/" + board.address() + "/no-folder",
                Level.WARNING,
                "",
                board.address()
                    + " is to have the packs "
                    + String.join(", ", named)
                    + ", but the manager has no packs folder: nothing is pushed"));
        continue;
      }
      for (String pack : named) {
        if (teamPacks != null && !teamPacks.names().contains(pack)) {
          found.add(
              new Alert(
                  "setup/packs/" + board.address() + "/pack/" + pack,
                  Level.WARNING,
                  "",
                  board.address()
                      + " is to have the pack "
                      + pack
                      + ", which isn't among the team's packs ("
                      + settings.packs().orElseThrow()
                      + "): a pack is a folder there with a pack.yaml"));
        }
      }
    }
    boolean signatures = false;
    for (Spotter.Description description : seen) {
      signatures |= description.getRequiresSignatures();
    }
    if (signer == null && signatures) {
      found.add(
          new Alert(
              "setup/key",
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
                  "setup/limits/" + id,
                  Level.WARNING,
                  "",
                  "Spotter's limits for " + id + " match no value or response field on any board"));
        }
      }
    }
    return List.copyOf(found);
  }

  /** The boards, one for each definition, in the order they were given. */
  public List<Board> boards() {
    return boardList;
  }

  /**
   * The board with this address, as robot code gave it: look it up once, and keep it.
   *
   * @throws IllegalArgumentException if none of its boards has it
   */
  public Board board(String address) {
    for (Board board : boards) {
      if (board.address().equals(address.strip())) {
        return board;
      }
    }
    throw new IllegalArgumentException(
        "the manager has no board " + address.strip() + ", only " + addresses());
  }

  private String addresses() {
    List<String> all = new ArrayList<>();
    for (Board board : boards) {
      all.add(board.address());
    }
    return String.join(", ", all);
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
    return link.push();
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
