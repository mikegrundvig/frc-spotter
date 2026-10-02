package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The computer the agent reports on: its files, from a root ({@code /} on a coprocessor, a fixture
 * tree in tests), who owns them, its network addresses, the commands it may run, its monotonic
 * clock, and the journal the agent logs to. Every path the agent reads is written as on the
 * coprocessor ({@code /proc/stat}) and found under the root, so tests run anywhere, against any
 * tree.
 */
final class Host {
  /** The most addresses read: a coprocessor has one or two. */
  static final int MAX_ADDRESSES = 16;

  private final Path root;
  private final Limits limits;
  private final boolean escapeColons;
  private final Commands commands;
  private final Links links;
  private final Owners owners;
  private final Addresses addresses;
  private final LongSupplier monotonicMicros;
  private final Consumer<String> log;

  /**
   * How a path's links resolve: in sysfs, a device's class entry links to where it really is. On a
   * coprocessor that's the filesystem's own; in tests, a table of links, since a fixture tree in
   * Git can't hold them on every system.
   */
  interface Links {
    /** Where an absolute path (as on the coprocessor) really is, as an absolute path. */
    String resolve(String path) throws IOException;
  }

  /**
   * Who owns a file, and who may write it: a pack's file is trusted only when it's root's and
   * nobody else may write it. On a coprocessor, the filesystem's own; in tests, a table, as a
   * fixture tree in Git is the tester's.
   */
  interface Owners {
    /** The owner and mode of an absolute path (as on the coprocessor), its links followed. */
    Owner of(String path) throws IOException;
  }

  /**
   * A file's owner and permissions.
   *
   * @param uid its owner's user ID: 0 is root
   * @param mode its permission bits, such as {@code 0644}
   */
  record Owner(int uid, int mode) {
    /** Whether its group or anyone else may write it. */
    boolean writableByOthers() {
      return (mode & 0022) != 0;
    }
  }

  /** The computer's network addresses: the system's on a coprocessor, a list in tests. */
  interface Addresses {
    /**
     * Every interface's addresses but loopback's, IPv4 first, IPv6 link-local ones left out, at
     * most {@link #MAX_ADDRESSES}.
     */
    List<String> read() throws IOException;
  }

  /**
   * A computer at {@code root}. {@code escapeColons} is for fixture trees: sysfs names hold colons
   * ({@code 7-1:1.0}), which no file in Git may hold if Windows is to check it out, so a fixture
   * writes them {@code %3A}.
   */
  Host(
      Path root,
      Limits limits,
      boolean escapeColons,
      Commands commands,
      Links links,
      Owners owners,
      Addresses addresses,
      LongSupplier monotonicMicros,
      Consumer<String> log) {
    this.root = root;
    this.limits = limits;
    this.escapeColons = escapeColons;
    this.commands = commands;
    this.links = links;
    this.owners = owners;
    this.addresses = addresses;
    this.monotonicMicros = monotonicMicros;
    this.log = log;
  }

  /** The coprocessor itself: the root filesystem, real links, the process clock. */
  static Host system(Commands commands, Consumer<String> log) {
    return system(Path.of("/"), commands, log);
  }

  /**
   * The computer's files from {@code root} rather than {@code /} (a fixture tree with real links,
   * as a container test writes one), its commands, addresses, and clock its own. A link is followed
   * within the tree: one that leads out of it is as if it led nowhere.
   */
  static Host system(Path root, Commands commands, Consumer<String> log) {
    return new Host(
        root,
        Limits.DEFAULT,
        false,
        commands,
        path -> {
          Path top = root.toRealPath();
          Path real = root.resolve(path.substring(1)).toRealPath();
          if (!real.startsWith(top)) {
            throw new IOException(path + " leads out of " + root);
          }
          return "/" + top.relativize(real).toString().replace(java.io.File.separatorChar, '/');
        },
        path -> {
          Path file = root.resolve(path.substring(1));
          return new Owner(
              (Integer) Files.getAttribute(file, "unix:uid"),
              (Integer) Files.getAttribute(file, "unix:mode") & 07777);
        },
        Host::systemAddresses,
        () -> System.nanoTime() / 1000,
        log);
  }

  /**
   * The system's addresses, interface by interface in the kernel's order: IPv4 first, then IPv6,
   * without loopback's or IPv6 link-local ones, and without a scope.
   */
  static List<String> systemAddresses() throws IOException {
    List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
    interfaces.sort(Comparator.comparingInt(NetworkInterface::getIndex));
    List<String> v4 = new ArrayList<>();
    List<String> v6 = new ArrayList<>();
    for (NetworkInterface each : interfaces) {
      if (each.isLoopback()) {
        continue;
      }
      for (InetAddress address : Collections.list(each.getInetAddresses())) {
        if (address.isLoopbackAddress()
            || (address instanceof Inet6Address && address.isLinkLocalAddress())) {
          continue;
        }
        String text = address.getHostAddress();
        int scope = text.indexOf('%');
        (address instanceof Inet4Address ? v4 : v6)
            .add(scope < 0 ? text : text.substring(0, scope));
      }
    }
    List<String> all = new ArrayList<>(v4);
    all.addAll(v6);
    return all.subList(0, Math.min(all.size(), MAX_ADDRESSES));
  }

  /** A path as on the coprocessor ({@code /proc/stat}), under the root. */
  Path path(String absolute) {
    String relative = absolute.startsWith("/") ? absolute.substring(1) : absolute;
    return root.resolve(escapeColons ? relative.replace(":", "%3A") : relative);
  }

  /** How much the agent reads and sends. */
  Limits limits() {
    return limits;
  }

  /**
   * A file's text, or empty when it doesn't exist; at most {@link Limits#maxFile} bytes of it (a
   * longer file is cut short, which a JSON file won't survive).
   */
  Optional<String> read(String absolute) throws IOException {
    try (InputStream in = Files.newInputStream(path(absolute))) {
      return Optional.of(new String(in.readNBytes(limits.maxFile()), StandardCharsets.UTF_8));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  /** A file's first line, trimmed, or empty when it doesn't exist. */
  Optional<String> line(String absolute) throws IOException {
    return read(absolute).map(text -> text.lines().findFirst().orElse("").strip());
  }

  /** The names in a folder, sorted; none when it doesn't exist. */
  List<String> list(String absolute) throws IOException {
    Path folder = path(absolute);
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(folder)) {
      List<String> names = new ArrayList<>();
      entries.forEach(
          entry -> {
            String name = entry.getFileName().toString();
            names.add(escapeColons ? name.replace("%3A", ":") : name);
          });
      names.sort(null);
      return names;
    }
  }

  /** Whether a path exists. */
  boolean exists(String absolute) {
    return Files.exists(path(absolute));
  }

  /** Where a path really is, its links resolved. */
  String resolve(String absolute) throws IOException {
    return links.resolve(absolute);
  }

  /** Who owns a path, and who may write it, its links followed. */
  Owner owner(String absolute) throws IOException {
    return owners.of(absolute);
  }

  /** The computer's network addresses, as they are now. */
  List<String> addresses() throws IOException {
    return addresses.read();
  }

  Commands commands() {
    return commands;
  }

  /** The monotonic clock (CLOCK_MONOTONIC on Linux, the journal's), microseconds since boot. */
  long monotonicMicros() {
    return monotonicMicros.getAsLong();
  }

  /** Writes a line to the agent's log: the journal, on a coprocessor. */
  void log(String message) {
    log.accept(message);
  }
}
