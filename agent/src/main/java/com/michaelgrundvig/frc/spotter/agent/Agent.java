package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The agent: a runner of what its packs declare, measuring nothing itself. It describes the board
 * (its identity, packs, values, logs and actions), keeps the values its collectors fill, runs its
 * actions, pages its logs, takes pushed packs, and knows who may change the board: the robot
 * controller, signing its writes if the board requires it.
 */
final class Agent implements AutoCloseable {
  /** How often the description is read afresh: the board's addresses may change as it runs. */
  static final long DESCRIPTION_NANOS = 10_000_000_000L;

  /** An address on a robot's network, 10.TE.AM.x: its first three numbers. */
  private static final Pattern ROBOT_NETWORK =
      Pattern.compile("(10\\.[0-9]{1,3}\\.[0-9]{1,3})\\.[0-9]{1,3}");

  private final Host host;
  private final Configuration configuration;
  private final IdentitySource identity;
  private final Describer describer;
  private final Events events = new Events();
  private final ValueStore store;
  private final Commands commands;
  private final Collectors collectors;
  private final Runs runs;
  private final Logs logs;
  private final Push push;
  private final Signatures signatures;
  private final Runnable exit;
  private final @Nullable String controllerOverride;
  private Spotter.@Nullable Description description;
  private long describedNanos;

  /**
   * An agent on a board.
   *
   * @param host the board
   * @param configuration its settings and packs
   * @param version the agent's version
   * @param controllerOverride the one address a write is taken from, given on the command line;
   *     null to take the settings', or else the robot controller's, 10.TE.AM.2, on the board's own
   *     10.TE.AM.x network
   * @param exit ends the agent, for systemd to start it again: after a push
   */
  Agent(
      Host host,
      Configuration configuration,
      String version,
      @Nullable String controllerOverride,
      Runnable exit) {
    this.host = host;
    this.configuration = configuration;
    this.controllerOverride = controllerOverride;
    this.exit = exit;
    this.identity = new IdentitySource(host);
    this.describer = new Describer(version, configuration);
    List<Pack> packs = configuration.packs().packs();
    this.store = new ValueStore(Collectors.values(packs), events::valuesChanged);
    this.commands = new Commands(host);
    this.collectors = new Collectors(host, packs, commands, store);
    this.runs = new Runs(host, commands, events, Runs.declared(packs));
    this.logs = new Logs(commands, packs);
    this.push = new Push(host);
    this.signatures = new Signatures(configuration.config().trustedKeys());
  }

  /**
   * Takes up the runs a previous agent kept, tidies pushed bundles, and starts the collectors on
   * their schedules.
   */
  void start() {
    runs.restore();
    push.tidy();
    collectors.start();
  }

  /** The settings and packs it read. */
  Configuration configuration() {
    return configuration;
  }

  /** The collectors, for tests to run at once. */
  Collectors collectors() {
    return collectors;
  }

  /** The values, as the collectors last filled them. */
  ValueStore store() {
    return store;
  }

  /** What happens, for streams to follow. */
  Events events() {
    return events;
  }

  /** The actions' runs. */
  Runs runs() {
    return runs;
  }

  /** The packs' logs. */
  Logs logs() {
    return logs;
  }

  /** Pushed packs. */
  Push push() {
    return push;
  }

  /** Signed writes. */
  Signatures signatures() {
    return signatures;
  }

  /** The monotonic clock, nanoseconds since boot: what values and events are timed by. */
  long nanos() {
    return host.monotonicNanos();
  }

  /** Ends the agent, for systemd to start it again: after a push. */
  void exit() {
    exit.run();
  }

  /**
   * The board's description: read afresh at most every {@link #DESCRIPTION_NANOS}, so an address
   * the board gains as it runs shows, with a new revision.
   */
  Spotter.Description description() {
    return current().clone();
  }

  /** The description's revision, as {@link #description} would answer it. */
  int revision() {
    return current().getRevision();
  }

  private synchronized Spotter.Description current() {
    long now = host.monotonicNanos();
    Spotter.Description last = description;
    if (last != null && now - describedNanos < DESCRIPTION_NANOS) {
      return last;
    }
    Spotter.Identity read;
    try {
      read = identity.read();
    } catch (IOException | RuntimeException e) {
      host.log("Couldn't read this board's identity: " + e);
      read = last != null ? last.getIdentity().clone() : Spotter.Identity.newInstance();
    }
    Spotter.Description fresh = describer.describe(read);
    description = fresh;
    describedNanos = now;
    return fresh;
  }

  /** Every value now, complete, with the revision of the description its indexes belong to. */
  Spotter.Values values() {
    return store.since(-1, revision(), host.monotonicNanos());
  }

  /** Writes a line to the agent's log. */
  void log(String message) {
    host.log(message);
  }

  /**
   * The one address a write is taken from, or why there's none.
   *
   * @param address the address; empty when there's none, and none is taken
   * @param why where the address came from, or why there's none
   */
  record Controller(String address, String why) {}

  /**
   * The one address a write is taken from: the command line's, else the settings', else the robot
   * controller's (10.TE.AM.2) on the network of the board's own 10.TE.AM.x address, as its
   * addresses are now. None when it has no 10.x address, or has them on more than one such network,
   * since then it can't tell which robot it's on.
   */
  Controller controller() throws IOException {
    if (controllerOverride != null) {
      return new Controller(controllerOverride, "named on the command line");
    }
    if (!configuration.config().controller().isEmpty()) {
      return new Controller(configuration.config().controller(), "named in " + AgentConfig.PATH);
    }
    TreeSet<String> networks = new TreeSet<>();
    for (String address : host.addresses()) {
      Matcher matcher = ROBOT_NETWORK.matcher(address);
      if (matcher.matches()) {
        networks.add(matcher.group(1));
      }
    }
    if (networks.isEmpty()) {
      return new Controller(
          "",
          "this computer has no 10.TE.AM.x address to find the robot controller (10.TE.AM."
              + Protocol.CONTROLLER
              + ") by; name it in "
              + AgentConfig.PATH
              + " if it's elsewhere");
    }
    if (networks.size() > 1) {
      return new Controller(
          "",
          "this computer has addresses on more than one 10.x network ("
              + String.join(", ", networks.stream().map(n -> n + ".x").toList())
              + "), so which robot controller it answers to isn't clear; name it in "
              + AgentConfig.PATH);
    }
    return new Controller(
        networks.first() + "." + Protocol.CONTROLLER, "the robot controller on its own network");
  }

  /** The names this computer answers to: its hostname, and its hostname in .local. */
  List<String> names() throws IOException {
    String name = identity.hostname().toLowerCase(Locale.ROOT);
    return name.isEmpty() ? List.of() : List.of(name, name + ".local");
  }

  @Override
  public void close() {
    collectors.close();
    runs.close();
    commands.close();
  }
}
