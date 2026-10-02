package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The computer the agent reports on: its files, from a root ({@code /} on a coprocessor, a fixture
 * tree in tests), the commands it may run, its monotonic clock, and the journal the agent logs to.
 * Every path the agent reads is written as on the coprocessor ({@code /proc/stat}) and found under
 * the root, so tests run anywhere, against any tree.
 */
final class Host {
  private final Path root;
  private final Limits limits;
  private final boolean escapeColons;
  private final Commands commands;
  private final Links links;
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
      LongSupplier monotonicMicros,
      Consumer<String> log) {
    this.root = root;
    this.limits = limits;
    this.escapeColons = escapeColons;
    this.commands = commands;
    this.links = links;
    this.monotonicMicros = monotonicMicros;
    this.log = log;
  }

  /** The coprocessor itself: the root filesystem, real links, the process clock. */
  static Host system(Commands commands, Consumer<String> log) {
    return system(Path.of("/"), commands, log);
  }

  /**
   * The computer's files from {@code root} rather than {@code /} (a fixture tree with real links,
   * as a container test writes one), its commands and clock its own. A link is followed within the
   * tree: one that leads out of it is as if it led nowhere.
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
        () -> System.nanoTime() / 1000,
        log);
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
