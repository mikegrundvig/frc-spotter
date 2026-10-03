package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One coprocessor, as the robot loop sees it after each {@link Manager#update}: whether it's
 * reached, its description, its values with their levels, and its alerts. Everything here is the
 * state its thread last published, taken whole at the update, so reads within a loop agree with
 * each other, and none of them waits.
 *
 * <p>Read it from one thread, the robot loop's, between updates. Its name, which the board's thread
 * sets, can be read from any.
 */
public final class Board {
  private final String address;
  private final Exchange exchange = new Exchange();
  private final String silent;
  private volatile String name;

  // The loop's, as of its last update.
  private Connection connection = Connection.CONNECTING;
  private String why = "";
  private List<Alert> valueAlerts = List.of();
  private String alertsName;
  private List<Alert> alerts = List.of();

  Board(String address, Settings settings) {
    this.address = address;
    this.name = address;
    this.alertsName = address;
    this.silent = "nothing heard from it for over " + settings.missing().toMillis() + " ms";
  }

  /** Its address, as the manager was given it: {@code 10.12.34.11}, {@code vision-front:5808}. */
  public String address() {
    return address;
  }

  /** Its hostname, once it's described itself; until then, its address. Read from any thread. */
  public String name() {
    return name;
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

  /** Its values, in its description's order. Each is reused: see {@link Value}. */
  public List<Value> values() {
    return front().list;
  }

  /** A value by its id ({@code pack.collector.field}); null when it has none by that id. */
  public @Nullable Value value(String id) {
    return front().byId.get(id);
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
   * Its alerts: one for it, when it's missing or on another protocol; otherwise one per value at
   * warning or failing.
   */
  public List<Alert> alerts() {
    return alerts;
  }

  @Override
  public String toString() {
    return name + " (" + address + "): " + connection + (why.isEmpty() ? "" : ", " + why);
  }

  Exchange exchange() {
    return exchange;
  }

  /** Its thread names it, from its description. */
  void named(String hostname) {
    name = hostname;
  }

  private Table front() {
    return exchange.front();
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
    exchange.take();
    Table table = front();
    Connection judged;
    if (table.otherProtocol) {
      judged = Connection.OTHER_PROTOCOL;
    } else if (table.heard) {
      judged = now - table.heardNanos > missing ? Connection.MISSING : Connection.CONNECTED;
    } else {
      judged = now - started > missing ? Connection.MISSING : Connection.CONNECTING;
    }
    String because = judged == Connection.MISSING && table.why.isEmpty() ? silent : table.why;
    String named = name;
    boolean changed =
        judged != connection
            || !because.equals(why)
            || table.alerts != valueAlerts
            || !named.equals(alertsName);
    connection = judged;
    why = because;
    if (!changed) {
      return false;
    }
    valueAlerts = table.alerts;
    alertsName = named;
    if (judged == Connection.MISSING) {
      alerts = List.of(new Alert(Level.FAILING, named, named + " is missing: " + because));
    } else if (judged == Connection.OTHER_PROTOCOL) {
      alerts = List.of(new Alert(Level.FAILING, named, named + ": " + because));
    } else {
      alerts = table.alerts;
    }
    return true;
  }
}
