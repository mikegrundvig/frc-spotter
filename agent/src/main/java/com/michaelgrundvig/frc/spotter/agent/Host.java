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
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The computer the agent runs on: its files, from a root ({@code /} on a coprocessor, a folder in
 * tests), who owns them, its network addresses, its monotonic clock, and the journal the agent logs
 * to. Every path the agent uses is written as on the coprocessor ({@code /etc/frc-spotter/packs})
 * and found under the root, so tests run anywhere, against any tree.
 */
final class Host {
  /** The most addresses read: a coprocessor has one or two. */
  static final int MAX_ADDRESSES = 16;

  /** The most of any one file {@link #read} reads, in bytes. */
  static final int MAX_FILE = 256 * 1024;

  private final Path root;
  private final Owners owners;
  private final Addresses addresses;
  private final LongSupplier monotonicNanos;
  private final Consumer<String> log;

  /**
   * Who owns a file, and who may write it: an installed pack, and a program it names, is trusted
   * only when it's root's and nobody else may write it. On a coprocessor, the filesystem's own; in
   * tests, a table, as a test's files are the tester's.
   */
  interface Owners {
    /** The owner and mode of an absolute path (as on the coprocessor), its links followed. */
    Owner of(String path) throws IOException;
  }

  /**
   * A file's owner and permissions.
   *
   * @param user its owner's name, such as {@code root}
   * @param mode its permission bits, such as {@code 0644}
   */
  record Owner(String user, int mode) {
    /** The account an installed pack, and a program it names, must belong to. */
    static final String ROOT = "root";

    /** A file of root's, with these permission bits. */
    static Owner root(int mode) {
      return new Owner(ROOT, mode);
    }

    /** Whether it's root's. */
    boolean root() {
      return user.equals(ROOT);
    }

    /** Whether its group or anyone else may write it. */
    boolean writableByOthers() {
      return (mode & 0022) != 0;
    }

    /** A file's owner and permission bits, as the filesystem's POSIX attributes give them. */
    static Owner of(PosixFileAttributes attributes) {
      int mode = 0;
      for (PosixFilePermission permission : attributes.permissions()) {
        mode |= bit(permission);
      }
      return new Owner(attributes.owner().getName(), mode);
    }

    private static int bit(PosixFilePermission permission) {
      switch (permission) {
        case OWNER_READ:
          return 0400;
        case OWNER_WRITE:
          return 0200;
        case OWNER_EXECUTE:
          return 0100;
        case GROUP_READ:
          return 0040;
        case GROUP_WRITE:
          return 0020;
        case GROUP_EXECUTE:
          return 0010;
        case OTHERS_READ:
          return 0004;
        case OTHERS_WRITE:
          return 0002;
        default:
          return 0001;
      }
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

  Host(
      Path root,
      Owners owners,
      Addresses addresses,
      LongSupplier monotonicNanos,
      Consumer<String> log) {
    this.root = root;
    this.owners = owners;
    this.addresses = addresses;
    this.monotonicNanos = monotonicNanos;
    this.log = log;
  }

  /**
   * The computer's files from {@code root} ({@code /}, or a folder a test writes), its addresses
   * and clock its own: {@link System#nanoTime} is CLOCK_MONOTONIC on Linux.
   */
  static Host system(Path root, Consumer<String> log) {
    return new Host(
        root,
        path ->
            Owner.of(
                Files.readAttributes(root.resolve(path.substring(1)), PosixFileAttributes.class)),
        Host::systemAddresses,
        System::nanoTime,
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

  /** A path as on the coprocessor ({@code /etc/os-release}), under the root. */
  Path path(String absolute) {
    return root.resolve(absolute.startsWith("/") ? absolute.substring(1) : absolute);
  }

  /** A file's text, or empty when it doesn't exist; at most {@link #MAX_FILE} bytes of it. */
  Optional<String> read(String absolute) throws IOException {
    try (InputStream in = Files.newInputStream(path(absolute))) {
      return Optional.of(new String(in.readNBytes(MAX_FILE), StandardCharsets.UTF_8));
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
      entries.forEach(entry -> names.add(entry.getFileName().toString()));
      names.sort(null);
      return names;
    }
  }

  /** Whether a path exists. */
  boolean exists(String absolute) {
    return Files.exists(path(absolute));
  }

  /** Whether a path is a folder. */
  boolean isFolder(String absolute) {
    return Files.isDirectory(path(absolute));
  }

  /** Who owns a path, and who may write it, its links followed. */
  Owner owner(String absolute) throws IOException {
    return owners.of(absolute);
  }

  /** The computer's network addresses, as they are now. */
  List<String> addresses() throws IOException {
    return addresses.read();
  }

  /** The monotonic clock (CLOCK_MONOTONIC on Linux), nanoseconds since boot. */
  long monotonicNanos() {
    return monotonicNanos.getAsLong();
  }

  /** Writes a line to the agent's log: the journal, on a coprocessor. */
  void log(String message) {
    log.accept(message);
  }
}
