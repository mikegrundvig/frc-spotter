package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.manager.actions.Progress;
import com.michaelgrundvig.frc.spotter.manager.limits.Field;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.manager.stream.Exchange;
import com.michaelgrundvig.frc.spotter.manager.stream.Link;
import com.michaelgrundvig.frc.spotter.manager.stream.Table;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * One coprocessor, as the robot loop sees it after each {@link Manager#update}: whether it's
 * reached, its description, its values with their levels, and its alerts; and what robot code can
 * ask of it: a page of a log, an action run. The manager makes one for each {@link BoardDefinition}
 * it's given ({@link Manager#board}). Everything here is the state its thread last published, taken
 * whole at the update, so reads within a loop agree with each other, and none of them waits.
 *
 * <p>Read it from one thread, the robot loop's, between updates. Its name, which the board's thread
 * sets, can be read from any.
 */
public final class Board {
  private final BoardDefinition definition;
  private final String address;
  private final Exchange exchange;
  private final String silent;
  private volatile @Nullable Link link;

  // The loop's, as of its last update.
  private Connection connection = Connection.CONNECTING;
  private String why = "";
  private List<Alert> valueAlerts = List.of();
  private String packsAlert = "";
  private String problemsAlert = "";
  private String refusedAlert = "";
  private String alertsName;
  private List<Alert> alerts = List.of();

  // The loop's values: one handle for each id its description has had, or robot code asked for,
  // kept for good, and brought up to date by each update.
  private final Map<String, Value> handles = new HashMap<>();
  private Value[] shown = new Value[0];
  private List<Value> shownList = List.of();
  private Spotter.Description shownDescription = Table.NONE;

  /** A board of a manager's, as its definition says, with that manager's settings. */
  Board(BoardDefinition definition, Settings settings) {
    this.definition = definition;
    this.address = definition.address();
    this.exchange = new Exchange(address);
    this.alertsName = address;
    this.silent = "nothing heard from it for over " + settings.missing().toMillis() + " ms";
  }

  /** What robot code named it as: its address, and the team's packs it's to have. */
  public BoardDefinition definition() {
    return definition;
  }

  /** Its address, as robot code gave it: {@code vision-front}, {@code 10.12.34.11:5808}. */
  public String address() {
    return address;
  }

  /** Its hostname, once it's described itself; until then, its address. Read from any thread. */
  public String name() {
    return exchange.name();
  }

  /** Whether it's reached, missing, or on another protocol. */
  public Connection connection() {
    return connection;
  }

  /**
   * Why it isn't reached: the last attempt's failure ({@code "Connection refused"}), its silence,
   * or the other protocol it speaks; empty while its stream is open.
   */
  public String why() {
    return why;
  }

  /**
   * The protocol version it last answered with ({@code "2.0"}); empty before it answered, or when
   * it answered without one. A different minor version works as usual, and shows here.
   */
  public String protocol() {
    return front().protocol;
  }

  /**
   * Its description, as it last sent it: what it runs, its packs, its values', logs' and actions'
   * declarations, and its problems. Empty (revision 0) before it's described itself, or while it
   * speaks another protocol. Read it, never change it.
   */
  public Spotter.Description description() {
    return front().description;
  }

  /**
   * What its agent ignored, and why, as its description lists them: a pack that didn't load (each
   * mistake with its file and line), a program anyone but root could change, settings that can't be
   * read. Empty when there are none, or before it's described itself. The same list until its
   * description changes; while it has any, its alerts include a warning saying how many, and the
   * first.
   */
  public List<String> problems() {
    return front().problems;
  }

  /**
   * Its values, in its description's order: the same list until its description changes. Each is
   * kept up to date by every update, so it's safe to keep (see {@link Value}).
   */
  public List<Value> values() {
    return shownList;
  }

  /**
   * A value by its id ({@code pack.collector.field}): the same {@link Value} for the id every time,
   * kept up to date by every update, so look it up once and keep it. While the board has no value
   * by that id (it hasn't described itself, or its packs don't declare it), it's unavailable, and
   * says so: {@code "vision-front has no value detector.health.fps"}. The first lookup of an id the
   * board doesn't have allocates its handle; none after does.
   */
  public Value value(String id) {
    Value handle = handles.get(id);
    if (handle == null) {
      handle = new Value(Field.absent(id));
      handle.absent(name());
      handles.put(id, handle);
    }
    return handle;
  }

  /**
   * How many times its description or one of its values has changed: the change notice. A loop that
   * keeps the count it last saw knows something changed when this differs, and which values by
   * their {@link Value#changed}.
   */
  public long changes() {
    return front().changes;
  }

  /** Whether it's been heard from: an answer, or anything on its stream. */
  public boolean heard() {
    return front().heard;
  }

  /** When it was last heard from, on the robot's clock; 0 before it was. */
  public long heardNanos() {
    return front().heardNanos;
  }

  /**
   * Its alerts: one for it, when it's missing or on another protocol, or describes more than the
   * manager takes; otherwise one per value at warning or failing (at most 32, the last saying how
   * many more), one when its packs differ from the robot's and won't be pushed now, and one, a
   * warning, while its description lists problems ({@link #problems}).
   */
  public List<Alert> alerts() {
    return alerts;
  }

  /**
   * A page of one of its logs ({@code pack.log}, as its description declares them), fetched on the
   * board's request threads: completes with the page, or exceptionally, saying why there's none.
   * Refused at once, without asking the board, when it isn't connected (it's missing, or on another
   * protocol), so asking every loop while a board is away costs nothing.
   */
  public CompletableFuture<Spotter.LogPage> log(String id, LogQuery query) {
    Link through = link;
    if (through == null || connection != Connection.CONNECTED) {
      return CompletableFuture.failedFuture(
          new IllegalStateException(
              name()
                  + "'s logs can't be read: it isn't connected"
                  + (why.isEmpty() ? "" : ": " + why)));
    }
    return through.requests().log(id, query);
  }

  /** Runs an action that takes no input: see {@link #run(String, byte[])}. */
  public Run run(String actionId) {
    return run(actionId, new byte[0]);
  }

  /** Runs an action with text as its input (UTF-8): see {@link #run(String, byte[])}. */
  public Run run(String actionId, String input) {
    return run(actionId, input.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Runs an action ({@code pack.action}, or {@code core.power-off}, {@code core.reboot}) with its
   * input, on the manager's threads: the run follows from the board's stream. Refused at once,
   * without asking the board, when the board isn't connected or has no such action; and, unless
   * robot code says otherwise ({@link Settings#refuseWhileEnabled}), while the robot is enabled or
   * the field is attached, unless the action says {@code whileEnabled: true}.
   *
   * @param input its input, as raw bytes: empty for an action that takes none
   */
  public Run run(String actionId, byte[] input) {
    Spotter.ActionDeclaration action = null;
    for (Spotter.ActionDeclaration each : description().getActions()) {
      if (each.getId().equals(actionId)) {
        action = each;
        break;
      }
    }
    Link through = link;
    Map<String, Limits> overrides = through == null ? Map.of() : through.settings().limits();
    Progress run =
        new Progress(
            actionId,
            action != null ? action : Spotter.ActionDeclaration.newInstance().setId(actionId),
            overrides,
            through == null ? null : through.requests(),
            true);
    if (through == null || connection != Connection.CONNECTED) {
      run.refused(name() + " isn't connected" + (why.isEmpty() ? "" : ": " + why));
    } else if (action == null) {
      run.refused(name() + " has no action " + actionId);
    } else if (through.settings().refuseWhileEnabled() && !action.getWhileEnabled()) {
      RobotSignals robot = through.robot();
      if (robot.fieldAttached().getAsBoolean()) {
        run.refused("the field is attached, and " + actionId + " doesn't say whileEnabled");
      } else if (robot.enabled().getAsBoolean()) {
        run.refused("the robot is enabled, and " + actionId + " doesn't say whileEnabled");
      }
    }
    if (!run.done() && through != null) {
      through.runs().start(run, actionId, input);
    }
    return new Run(run);
  }

  @Override
  public String toString() {
    return name() + " (" + address + "): " + connection + (why.isEmpty() ? "" : ", " + why);
  }

  Exchange exchange() {
    return exchange;
  }

  /** Its link, which sends its requests: set as the manager makes it. */
  void link(Link made) {
    link = made;
  }

  /** Its link; null for a board without one (in tests). */
  @Nullable Link link() {
    return link;
  }

  private Table front() {
    return exchange.front();
  }

  /**
   * Takes a new description's values: each id's handle (made for one it hasn't had), and every
   * other handle unavailable, saying the board has no such value.
   */
  private void show(Table table) {
    Value[] values = new Value[table.values.length];
    for (int i = 0; i < values.length; i++) {
      Reading from = table.values[i];
      Value handle = handles.get(from.id());
      if (handle == null) {
        handle = new Value(from.field);
        handles.put(from.id(), handle);
      } else {
        handle.bind(from.field);
      }
      handle.copyFrom(from);
      values[i] = handle;
    }
    List<Value> list = List.of(values);
    Set<Value> described = new HashSet<>(list);
    for (Value handle : handles.values()) {
      if (!described.contains(handle)) {
        handle.absent(name());
      }
    }
    shown = values;
    shownList = list;
    shownDescription = table.description;
  }

  /**
   * Takes what its thread last published and judges, on the robot's clock, whether it's missing:
   * whether its alerts changed. Allocates only when they did.
   *
   * @param now the robot's clock now
   * @param started when the manager started, on the robot's clock
   * @param missing the missing threshold, in nanoseconds
   */
  boolean update(long now, long started, long missing) {
    return update(now, started, missing, true);
  }

  /** Why a board of a manager robot code never started is missing. */
  static final String NOT_STARTED =
      "the manager isn't started: call its start() once, as it's made";

  /**
   * As {@link #update(long, long, long)}, saying so when the manager was never started: robot code
   * that forgot {@code start()} sees why, not a network fault.
   *
   * @param running whether the manager was started
   */
  boolean update(long now, long started, long missing, boolean running) {
    boolean took = exchange.take();
    Table table = front();
    if (table.description != shownDescription) {
      show(table);
    }
    if (took) {
      // Every value, so each one's time is the latest sent, whatever changed.
      for (int i = 0; i < shown.length; i++) {
        shown[i].copyFrom(table.values[i]);
      }
    }
    Connection judged;
    if (table.otherProtocol) {
      judged = Connection.OTHER_PROTOCOL;
    } else if (table.heard) {
      judged = now - table.heardNanos > missing ? Connection.MISSING : Connection.CONNECTED;
    } else {
      judged = now - started > missing ? Connection.MISSING : Connection.CONNECTING;
    }
    String because =
        judged == Connection.MISSING && table.why.isEmpty()
            ? (running ? silent : NOT_STARTED)
            : table.why;
    String named = name();
    boolean changed =
        judged != connection
            || !because.equals(why)
            || table.alerts != valueAlerts
            || !table.packs.equals(packsAlert)
            || !table.problemsAlert.equals(problemsAlert)
            || !table.refused.equals(refusedAlert)
            || !named.equals(alertsName);
    connection = judged;
    why = because;
    if (!changed) {
      return false;
    }
    valueAlerts = table.alerts;
    packsAlert = table.packs;
    problemsAlert = table.problemsAlert;
    refusedAlert = table.refused;
    alertsName = named;
    if (judged == Connection.MISSING) {
      alerts =
          List.of(
              new Alert(
                  address + "/missing", Level.FAILING, named, named + " is missing: " + because));
    } else if (judged == Connection.OTHER_PROTOCOL) {
      alerts =
          List.of(new Alert(address + "/protocol", Level.FAILING, named, named + ": " + because));
    } else if (!refusedAlert.isEmpty()) {
      alerts = List.of(new Alert(address + "/described", Level.FAILING, named, refusedAlert));
    } else if (packsAlert.isEmpty() && problemsAlert.isEmpty()) {
      alerts = valueAlerts;
    } else {
      List<Alert> all = new ArrayList<>(valueAlerts);
      if (!packsAlert.isEmpty()) {
        all.add(new Alert(address + "/packs", Level.WARNING, named, packsAlert));
      }
      if (!problemsAlert.isEmpty()) {
        all.add(new Alert(address + "/problems", Level.WARNING, named, problemsAlert));
      }
      alerts = List.copyOf(all);
    }
    return true;
  }
}
