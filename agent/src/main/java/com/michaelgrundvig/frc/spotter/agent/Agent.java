package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Boot;
import com.michaelgrundvig.frc.spotter.api.ClockSync;
import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.Disk;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.JournalSummary;
import com.michaelgrundvig.frc.spotter.api.Memory;
import com.michaelgrundvig.frc.spotter.api.NetworkLink;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.api.UsbDevice;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the agent knows, assembled from its sources: the answers to the API's requests. It knows the
 * computer (load, heat, memory, disks, the journal, the drive, what booted, its network link, its
 * USB devices, its clock) and nothing of the software on it: that's its packs' probes
 * (docs/agent.md, "Packs"). A source that fails costs only its part, which reads as unknown, with
 * the failure in the health's problems; nothing one source does stops the others.
 */
final class Agent implements AutoCloseable {
  /** Health asked for again within this long is answered from the last reading. */
  static final long HEALTH_MICROS = 500_000;

  /** The most problems a health answer carries. */
  static final int MAX_PROBLEMS = 10;

  /** An address on a robot's network, 10.TE.AM.x: its first three numbers. */
  private static final Pattern ROBOT_NETWORK =
      Pattern.compile("(10\\.[0-9]{1,3}\\.[0-9]{1,3})\\.[0-9]{1,3}");

  private final Host host;
  private final Configuration configuration;
  private final StampSource stamp;
  private final BootSource boot;
  private final CpuSource cpu;
  private final ThermalSource thermal;
  private final MemorySource memory;
  private final DiskSource disks;
  private final JournalSource journal;
  private final DriveSource drive;
  private final NetworkSource network;
  private final UsbSource usb;
  private final ClockSource clock;
  private final Probes probes;
  private final Shutdown shutdown;
  private final @Nullable String controllerOverride;
  private final Executor background;
  private final Object first = new Object();
  private final AtomicBoolean refreshing = new AtomicBoolean();
  private volatile @Nullable Health lastHealth;
  private volatile long lastHealthMicros;
  // Each part's last problem, so a problem that lasts is logged once, not every second.
  private final Map<String, String> logged = new HashMap<>();

  /**
   * An agent reporting on a host.
   *
   * @param host the computer
   * @param configuration its configuration, and the probes compiled from it
   * @param timeout how long any one of the agent's own commands may take
   * @param executor where work that outlasts a request runs: reading health afresh, and the
   *     shutdown once its request is answered
   * @param controllerOverride the one address a shutdown is taken from, given on the command line;
   *     null to take the configuration's, or else the robot controller's, 10.TE.AM.2, on the
   *     computer's own 10.TE.AM.x network
   */
  Agent(
      Host host,
      Configuration configuration,
      Duration timeout,
      Executor executor,
      @Nullable String controllerOverride) {
    this.host = host;
    this.configuration = configuration;
    this.controllerOverride = controllerOverride;
    this.stamp = new StampSource(host);
    this.journal = new JournalSource(host, timeout);
    this.boot = new BootSource(host, stamp, journal);
    this.cpu = new CpuSource(host);
    this.thermal = new ThermalSource(host);
    this.memory = new MemorySource(host);
    this.disks = new DiskSource(host);
    this.drive = new DriveSource(host);
    this.network = new NetworkSource(host);
    this.usb = new UsbSource(host);
    this.clock = new ClockSource(host, timeout);
    this.probes =
        new Probes(
            host, configuration.probes(), new ProbeRunner(host, () -> Measurements.of(health())));
    this.shutdown = new Shutdown(host, executor);
    this.background = executor;
  }

  /** Starts running the probes on their schedules. */
  void start() {
    probes.start();
  }

  /** The probes its packs define. */
  ProbeSet probeSet() {
    return configuration.probes();
  }

  /** The probes, for the server and tests. */
  Probes probes() {
    return probes;
  }

  /** Which computer this is: its hostname, addresses, MAC, boot, and os-release. */
  Stamp stamp() throws IOException {
    return stamp.read();
  }

  /** The configuration this agent read, and where from. */
  Configuration configuration() {
    return configuration;
  }

  /**
   * The computer's health, at once. Asked again within half a second, the same answer; older than
   * that, the last answer still, while a reading afresh starts in the background (which can take
   * seconds, if a command is slow), so no request waits for a reading. Only the very first request
   * waits, for the first reading.
   */
  Health health() {
    Health last = lastHealth;
    if (last == null) {
      synchronized (first) {
        Health read = lastHealth;
        return read != null ? read : read();
      }
    }
    if (host.monotonicMicros() - lastHealthMicros >= HEALTH_MICROS
        && refreshing.compareAndSet(false, true)) {
      try {
        background.execute(
            () -> {
              try {
                read();
              } finally {
                refreshing.set(false);
              }
            });
      } catch (RuntimeException e) {
        refreshing.set(false);
        throw e;
      }
    }
    // The last answer: the fresh one, if the reading ran here (as in tests).
    Health latest = lastHealth;
    return latest != null ? latest : last;
  }

  private Health read() {
    long now = host.monotonicMicros();
    List<String> problems = new ArrayList<>();
    Stamp stamped = part("stamp", this::stamp, Stamp.NONE, problems);
    Boot booted =
        part(
            "boot",
            boot::read,
            new Boot(stamped.bootId(), Double.NaN, now, null, "", false),
            problems);
    Cpu load = part("cpu", cpu::read, Cpu.UNKNOWN, problems);
    List<ThermalZone> zones = part("thermal", thermal::read, List.of(), problems);
    Memory memoryNow = part("memory", memory::read, Memory.UNKNOWN, problems);
    List<Disk> space = part("disks", disks::read, List.of(), problems);
    JournalSummary trouble =
        part("journal", () -> journal.summary(stamped.bootId()), JournalSummary.EMPTY, problems);
    Optional<Drive> nvme = part("drive", drive::read, Optional.empty(), problems);
    List<NetworkLink> links = part("network", network::read, List.of(), problems);
    List<UsbDevice> devices =
        part("usb", () -> usb.read(journal.disconnects()), List.of(), problems);
    ClockSync time = part("clock", clock::read, ClockSync.UNKNOWN, problems);
    problems.addAll(configuration.problems());
    Controller controller = part("controller", this::controller, Controller.NONE, problems);
    if (controller.address().isEmpty() && !controller.why().isEmpty()) {
      problems.add("shutdown refused: " + controller.why());
    }
    if (shutdown.requested()) {
      problems.add(0, "shutting down: asked for by the robot");
    } else if (!shutdown.failure().isEmpty()) {
      problems.add(0, "shutdown failed: " + shutdown.failure() + "; ask again to retry");
    }
    List<ProbeResult> results = probes.results();
    Health health =
        new Health(
            stamped,
            booted,
            load,
            zones,
            trouble,
            nvme.orElse(null),
            problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS)),
            memoryNow,
            space,
            results,
            links,
            devices,
            time);
    lastHealthMicros = now;
    lastHealth = health;
    return health;
  }

  /** The units whose journal it serves: the kernel's and its own, and its packs'. */
  List<String> journalUnits() {
    List<String> units = new ArrayList<>(AgentApi.JOURNAL_UNITS);
    for (String unit : configuration.probes().journalUnits()) {
      if (!units.contains(unit)) {
        units.add(unit);
      }
    }
    return units;
  }

  /** A page of the journal, of some of the units it's served for. */
  JournalPage journal(JournalSource.Position position, int priority, List<String> units, int limit)
      throws IOException {
    return journal.page(position, priority, units, limit);
  }

  /** Writes a line to the agent's log. */
  void log(String message) {
    host.log(message);
  }

  /** How much the agent reads and sends. */
  Limits limits() {
    return host.limits();
  }

  /**
   * Asks for a shutdown on behalf of {@code from}: whether this request started it. Only the
   * controller may ask; the caller checks with {@link #controller}.
   */
  boolean shutdown(String from) {
    return shutdown.request(from);
  }

  /**
   * The one address a shutdown is taken from, or why there's none.
   *
   * @param address the address; empty when there's none, and none is taken
   * @param why where the address came from, or why there's none
   */
  record Controller(String address, String why) {
    static final Controller NONE = new Controller("", "");
  }

  /**
   * The one address a shutdown is taken from: the command line's, else the configuration's, else
   * the robot controller's (10.TE.AM.2) on the network of the computer's own 10.TE.AM.x address, as
   * its addresses are now. None when it has no 10.x address, or has them on more than one such
   * network, since then it can't tell which robot it's on.
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
              + AgentApi.CONTROLLER
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
        networks.first() + "." + AgentApi.CONTROLLER, "the robot controller on its own network");
  }

  /** The names this computer answers to: its hostname, and its hostname in .local. */
  List<String> names() throws IOException {
    String name = stamp.hostname().toLowerCase(Locale.ROOT);
    return name.isEmpty() ? List.of() : List.of(name, name + ".local");
  }

  private interface Reading<T> {
    T read() throws IOException;
  }

  private <T> T part(String name, Reading<T> reading, T unknown, List<String> problems) {
    try {
      T read = reading.read();
      synchronized (logged) {
        logged.remove(name);
      }
      return read;
    } catch (IOException | RuntimeException e) {
      String problem = name + ": " + e.getMessage();
      problems.add(problem);
      boolean fresh;
      synchronized (logged) {
        fresh = !problem.equals(logged.put(name, problem));
      }
      if (fresh) {
        host.log("Couldn't read " + name + ": " + e);
      }
      return unknown;
    }
  }

  @Override
  public void close() {
    probes.close();
  }
}
