package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A board for tests, in a folder: vision-front at 10.12.34.11 on team 1234's network, Debian 13,
 * its wired link up, nothing configured and no packs; its files root's and written by root alone
 * unless a test says otherwise; a clock the test moves, and the log the agent writes. Its packs are
 * folders the test writes, with scripts the agent really runs.
 */
final class Fixture {
  /** Its address on team 1234's robot network. */
  static final String ADDRESS = "10.12.34.11";

  final Path root;

  /** Who owns each file, and its mode, where it isn't root's alone (0644, or 0755 to run). */
  final Map<String, Host.Owner> owners = new HashMap<>();

  /** The computer's addresses, as it has them now. */
  final List<String> addresses = new CopyOnWriteArrayList<>(List.of(ADDRESS));

  final AtomicLong nanos = new AtomicLong(1_234_560_000_000L);
  final List<String> log = new CopyOnWriteArrayList<>();
  final Host host;

  Fixture(Path dir) throws IOException {
    root = dir.resolve("root");
    write("/proc/sys/kernel/hostname", "vision-front\n");
    write("/proc/sys/kernel/random/boot_id", "3c1e6a2e-0000-4000-8000-000000000001\n");
    write(
        "/etc/os-release",
        "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\nVERSION_ID=\"13\"\n");
    write("/sys/class/net/end0/address", "C0:74:2B:FE:12:34\n");
    write("/sys/class/net/end0/operstate", "up\n");
    Files.createDirectories(path("/sys/class/net/end0/device"));
    host =
        new Host(
            root,
            path ->
                owners.getOrDefault(
                    path, new Host.Owner(0, Files.isExecutable(path(path)) ? 0755 : 0644)),
            () -> new ArrayList<>(addresses),
            nanos::get,
            log::add);
  }

  /** A path as on the board, in the fixture. */
  Path path(String absolute) {
    return root.resolve(absolute.substring(1));
  }

  /** Writes a file, making its folders. */
  void write(String absolute, String text) {
    try {
      Path file = path(absolute);
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Writes a program the agent may run: a shell script, executable. */
  void script(String absolute, String body) {
    write(absolute, "#!/bin/sh\n" + body + "\n");
    try {
      Files.setPosixFilePermissions(path(absolute), PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** An installed pack: its folder, with its pack.yaml. */
  String pack(String name, String yaml) {
    String folder = Packs.INSTALLED + "/" + name;
    write(folder + "/" + Pack.FILE, yaml);
    return folder;
  }

  /** A pushed pack: its folder, with its pack.yaml. */
  String pushed(String name, String yaml) {
    String folder = Packs.PUSHED + "/" + name;
    write(folder + "/" + Pack.FILE, yaml);
    return folder;
  }

  /** The board's settings. */
  void config(String json) {
    write(AgentConfig.PATH, json);
  }

  /** The board's settings and packs, as the agent reads them as it starts. */
  Configuration configuration() {
    return Configuration.read(host);
  }

  /** The agent on this board, its packs read; its collectors not started. */
  Agent agent() {
    return new Agent(host, configuration(), "0.4.0-test", null);
  }

  /** What the agent logged, one line each. */
  String log() {
    return String.join("\n", log);
  }
}
