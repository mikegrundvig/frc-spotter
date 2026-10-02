package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.probes.Pack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * An RK3588 coprocessor for tests: a copy of the fixture tree in {@code src/test/resources/rk3588}
 * (eight cores in three clusters, seven thermal zones, two USB cameras, an NVMe drive, its wired
 * link and a bridge, its hostname and os-release), at 10.12.34.11, its clock kept by
 * systemd-timesyncd, with no packs and nothing configured; its files root's and written by root
 * alone unless a test says otherwise; canned command output, a clock the test moves, and the log
 * the agent writes.
 */
final class Fixture {
  /** Spotter's own repository, whose catalog of packs the tests read. */
  static final Path PROJECT = Path.of(System.getProperty("frc.projectDir", "../.."));

  /** Its address on team 1234's robot network. */
  static final String ADDRESS = "10.12.34.11";

  /** front-left's port: the camera on the USB 3 port, 7-1. */
  static final String FRONT_LEFT =
      "/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0";

  /** front-right's port: the camera on the USB 2 port, 3-1. */
  static final String FRONT_RIGHT =
      "/dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0";

  /** The sysfs links the cameras' paths resolve through, as on a coprocessor. */
  static final Map<String, String> LINKS =
      Map.of(
          "/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0",
          "/dev/video0",
          "/dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0",
          "/dev/video2",
          "/sys/class/video4linux/video0/device",
          "/sys/devices/platform/usbdrd3_0/fc000000.usb/xhci-hcd.0.auto/usb7/7-1/7-1:1.0",
          "/sys/class/video4linux/video2/device",
          "/sys/devices/platform/fc880000.usb/usb3/3-1/3-1:1.0");

  final Path root;
  final FakeCommands commands = new FakeCommands();

  /** Who owns each file, and its mode, where it isn't root's alone (0644). */
  final Map<String, Host.Owner> owners = new HashMap<>();

  /** The computer's addresses, as it has them now. */
  final List<String> addresses = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(ADDRESS));

  final AtomicLong micros = new AtomicLong(1_234_560_000L);
  final List<String> log = new ArrayList<>();
  final Host host;

  final Limits limits;

  Fixture(Path dir) throws IOException {
    this(dir, Limits.DEFAULT);
  }

  /** The coprocessor, with the agent's limits set (small, to test them). */
  Fixture(Path dir, Limits limits) throws IOException {
    this.limits = limits;
    root = dir.resolve("root");
    copy(resource("rk3588"), root);
    host = host(root);
    commands.answer(List.of("systemctl", "show"), Fixture.lines("systemctl-show.txt"));
    commands.answer(List.of("timedatectl", "show"), List.of("yes"));
    commands.answer(
        List.of("timedatectl", "timesync-status"),
        Fixture.lines("timedatectl-timesync-status.txt"));
  }

  /** A pack of Spotter's catalog, as written there. */
  static String catalogPack(String file) throws IOException {
    return Files.readString(PROJECT.resolve("packs").resolve(file), StandardCharsets.UTF_8);
  }

  /** Copies a pack into the computer's packs, as a team does: {@code <name>.yaml}, root's. */
  void pack(String name, String yaml) throws IOException {
    write(Pack.DIRECTORY + "/" + name + Pack.SUFFIX, yaml);
  }

  /** Writes the computer's overrides, {@code /etc/frc-spotter/agent.json}. */
  void config(String json) throws IOException {
    write(AgentConfig.PATH, json);
  }

  private Host host(Path at) {
    return host(at, path -> LINKS.getOrDefault(path, path));
  }

  /** A host on this computer, its links resolved by {@code links}. */
  Host host(Path at, Host.Links links) {
    return new Host(
        at,
        limits,
        true,
        commands,
        links,
        path -> owners.getOrDefault(path, new Host.Owner(Configuration.ROOT, 0644)),
        () -> List.copyOf(addresses),
        micros::get,
        message -> {
          synchronized (log) {
            log.add(message);
          }
        });
  }

  /** The agent's log, as it stands. */
  List<String> log() {
    synchronized (log) {
      return List.copyOf(log);
    }
  }

  /** An agent on this computer, as configured now; the robot controller at {@code controller}. */
  Agent agent(String controller) {
    return new Agent(
        host, Configuration.read(host), Duration.ofSeconds(2), Runnable::run, controller);
  }

  /**
   * An agent on this computer, as configured now, whose controller is its configuration's, else the
   * robot controller on its own network's.
   */
  Agent agent() {
    return new Agent(host, Configuration.read(host), Duration.ofSeconds(2), Runnable::run, null);
  }

  /** Writes a file of the computer, as a path on it. */
  void write(String path, String text) throws IOException {
    Path file = host.path(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  /** Deletes a file of the computer. */
  void delete(String path) throws IOException {
    Files.delete(host.path(path));
  }

  static Path resource(String name) {
    try {
      return Path.of(
          Objects.requireNonNull(Fixture.class.getClassLoader().getResource(name), name).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A resource's lines: canned command output. */
  static List<String> lines(String name) {
    try {
      return Files.readAllLines(resource(name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path source : walk.toList()) {
        Path target = to.resolve(from.relativize(source).toString());
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target);
        }
      }
    }
  }

  /** Canned answers to commands, by how they start; every command run, recorded. */
  static final class FakeCommands implements Commands {
    private final Map<List<String>, Output> answers = new HashMap<>();
    private final Map<List<String>, CountDownLatch> holds = new HashMap<>();
    final List<List<String>> ran = new ArrayList<>();
    private final AtomicInteger waiting = new AtomicInteger();

    /** How many commands are waiting on a hold now. */
    int waiting() {
      return waiting.get();
    }

    /** Commands starting so wait until the latch opens, as a slow command would. */
    synchronized void hold(List<String> start, CountDownLatch latch) {
      holds.put(start, latch);
    }

    void answer(List<String> start, List<String> lines) {
      answers.put(start, new Output(0, lines, false, false));
    }

    void answer(List<String> start, Output output) {
      answers.put(start, output);
    }

    @Override
    public Output run(List<String> command, Duration timeout, int maxLines, int maxBytes)
        throws IOException {
      CountDownLatch hold = null;
      synchronized (this) {
        for (Map.Entry<List<String>, CountDownLatch> held : holds.entrySet()) {
          if (startsWith(command, held.getKey())) {
            hold = held.getValue();
          }
        }
      }
      if (hold != null) {
        waiting.incrementAndGet();
        try {
          hold.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          waiting.decrementAndGet();
        }
      }
      return answer(command, maxLines, maxBytes);
    }

    private static boolean startsWith(List<String> command, List<String> start) {
      return command.size() >= start.size() && command.subList(0, start.size()).equals(start);
    }

    private synchronized Output answer(List<String> command, int maxLines, int maxBytes)
        throws IOException {
      ran.add(command);
      Output best = null;
      int length = -1;
      for (Map.Entry<List<String>, Output> answer : answers.entrySet()) {
        List<String> start = answer.getKey();
        if (start.size() > length
            && command.size() >= start.size()
            && command.subList(0, start.size()).equals(start)) {
          best = answer.getValue();
          length = start.size();
        }
      }
      if (best == null) {
        throw new IOException("no such command: " + command.get(0));
      }
      List<String> lines = new ArrayList<>();
      int bytes = 0;
      for (String line : best.lines()) {
        bytes += line.length() + 1;
        if (lines.size() == maxLines || bytes > maxBytes) {
          return new Output(-1, lines, true, false);
        }
        lines.add(line);
      }
      return best;
    }

    synchronized List<List<String>> ran() {
      return List.copyOf(ran);
    }
  }
}
