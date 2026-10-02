package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Boot;
import com.michaelgrundvig.frc.spotter.api.Cameras;
import com.michaelgrundvig.frc.spotter.api.Cpu;
import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.JournalSummary;
import com.michaelgrundvig.frc.spotter.api.Service;
import com.michaelgrundvig.frc.spotter.api.SettingsState;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.api.ThermalZone;
import com.michaelgrundvig.frc.spotter.table.Table;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * What the agent knows, assembled from its sources: the answers to the API's requests. A source
 * that fails costs only its part, which reads as unknown, with the failure in the health's
 * problems; nothing one source does stops the others.
 */
final class Agent {
  /** Health asked for again within this long is answered from the last reading. */
  static final long HEALTH_MICROS = 500_000;

  /** The most problems a health answer carries. */
  static final int MAX_PROBLEMS = 10;

  private final Host host;
  private final StampSource stamp;
  private final BootSource boot;
  private final CpuSource cpu;
  private final ThermalSource thermal;
  private final ServiceSource service;
  private final CameraSource cameras;
  private final JournalSource journal;
  private final DriveSource drive;
  private final SettingsSource settings;
  private final Shutdown shutdown;
  private final IntFunction<String> controller;
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
   * @param database PhotonVision's settings database, as a path on the computer
   * @param unit PhotonVision's systemd unit
   * @param timeout how long any one command may take
   * @param executor where work that outlasts a request runs: reading health afresh, and the
   *     shutdown once its request is answered
   * @param controller the robot controller's address, from the team number: 10.TE.AM.2
   */
  Agent(
      Host host,
      String database,
      String unit,
      Duration timeout,
      Executor executor,
      IntFunction<String> controller) {
    this.host = host;
    this.controller = controller;
    this.stamp = new StampSource(host);
    this.journal = new JournalSource(host, timeout);
    this.boot = new BootSource(host, stamp, journal);
    this.cpu = new CpuSource(host);
    this.thermal = new ThermalSource(host);
    this.service = new ServiceSource(host, unit, timeout);
    this.cameras = new CameraSource(host);
    this.drive = new DriveSource(host);
    this.settings = new SettingsSource(host, database, host.limits());
    this.shutdown = new Shutdown(host, unit, executor);
    this.background = executor;
  }

  /** The stamp, with this boot and the MAC address. */
  Stamp stamp() throws IOException {
    return stamp.stamp();
  }

  /** The stamp file as the image wrote it, with the port it says to serve on. */
  StampSource.StampFile stampFile() throws IOException {
    return stamp.file();
  }

  /**
   * The computer's health, at once. Asked again within half a second, the same answer; older than
   * that, the last answer still, while a reading afresh starts in the background (which can take
   * seconds, if a command is slow or the settings changed), so no request waits for a reading. Only
   * the very first request waits, for the first reading.
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
    Stamp stamped = part("stamp", stamp::stamp, Stamp.NONE, problems);
    Boot booted =
        part(
            "boot",
            boot::read,
            new Boot(stamped.bootId(), Double.NaN, now, null, "", false, ""),
            problems);
    Cpu load = part("cpu", cpu::read, Cpu.UNKNOWN, problems);
    List<ThermalZone> zones = part("thermal", thermal::read, List.of(), problems);
    Service photonvision =
        part("photonvision", service::read, new Service("", "", "", "", 0, 0), problems);
    Optional<SettingsSource.Summary> live =
        part("settings", settings::summary, Optional.empty(), problems);
    Map<String, String> usbPaths = live.map(SettingsSource.Summary::usbPaths).orElse(Map.of());
    Cameras plugged =
        part("cameras", () -> cameras.read(stamped.cameras(), usbPaths), Cameras.UNKNOWN, problems);
    JournalSummary trouble =
        part("journal", () -> journal.summary(stamped.bootId()), JournalSummary.EMPTY, problems);
    Optional<Drive> nvme = part("drive", drive::read, Optional.empty(), problems);
    if (shutdown.requested()) {
      problems.add(0, "shutting down: asked for by the robot");
    } else if (!shutdown.failure().isEmpty()) {
      problems.add(0, "shutdown failed: " + shutdown.failure() + "; ask again to retry");
    }
    Health health =
        new Health(
            stamped,
            booted,
            load,
            zones,
            photonvision,
            plugged,
            trouble,
            nvme.orElse(null),
            new SettingsState(
                stamped.settingsHash(), live.map(SettingsSource.Summary::hash).orElse("")),
            problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS)));
    lastHealthMicros = now;
    lastHealth = health;
    return health;
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
   * Reads PhotonVision's live settings and sends them; empty when it has no database yet.
   *
   * @throws SettingsSource.Busy when another send is under way
   */
  <T> Optional<T> sendSettings(SettingsSource.Sending<T> sending)
      throws IOException, SettingsSource.Busy {
    return settings.send(sending);
  }

  /**
   * Asks for a shutdown on behalf of {@code from}: whether this request started it. Only the robot
   * controller, 10.TE.AM.2, may ask; the caller checks with {@link #controller}.
   */
  boolean shutdown(String from) {
    return shutdown.request(from);
  }

  /**
   * The robot controller's address on this computer's team's network, 10.TE.AM.2; empty when the
   * computer has no stamp (no name or team), so no robot to take a shutdown from.
   */
  Optional<String> controller() throws IOException {
    Stamp stamped = stamp.file().stamp();
    if (stamped.name().isEmpty() || stamped.team() <= 0) {
      return Optional.empty();
    }
    return Optional.of(controller.apply(stamped.team()));
  }

  /** The names this computer answers to: its name, and its name in .local. */
  List<String> names() throws IOException {
    String name = stamp.file().stamp().name();
    return name.isEmpty() ? List.of() : List.of(name, name + ".local");
  }

  /** The robot controller's address on a team's network: 10.TE.AM.2. */
  static String controllerOf(int team) {
    return Table.ip(team, AgentApi.CONTROLLER);
  }

  private interface Reading<T> {
    T read() throws IOException;
  }

  private <T> T part(String name, Reading<T> reading, T unknown, List<String> problems) {
    try {
      T read = reading.read();
      logged.remove(name);
      return read;
    } catch (IOException | RuntimeException e) {
      String problem = name + ": " + e.getMessage();
      problems.add(problem);
      if (!problem.equals(logged.put(name, problem))) {
        host.log("Couldn't read " + name + ": " + e);
      }
      return unknown;
    }
  }
}
