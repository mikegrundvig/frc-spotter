package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A real agent in a test's own process, serving protocol 2 on a local port: a board's files in a
 * folder (as {@code --root} reads them), packs the test writes, scripts the agent really runs. It
 * takes writes from 127.0.0.1, the test playing the robot controller. Its files count as root's,
 * written by root alone, as an installer leaves them, so its installed packs are trusted.
 *
 * <p>Stopping it and starting it again is an agent restart, on the same port. When the agent ends
 * itself (after a push), it's started again at once, as systemd starts it. For other projects'
 * tests (the manager's); the agent's own use {@code Fixture}.
 */
public final class LocalAgent implements AutoCloseable {
  /** The agent's version, as its description says it. */
  public static final String VERSION = "0.4.0-test";

  private final Path root;
  private final List<String> log = new CopyOnWriteArrayList<>();
  private final AtomicInteger exits = new AtomicInteger();
  private final Host host;
  private int port;
  private @Nullable Agent agent;
  private @Nullable AgentServer server;
  private boolean closed;

  /** A board named {@code hostname}, in a folder, with nothing configured and no packs. */
  public LocalAgent(Path dir, String hostname) {
    root = dir.resolve("root");
    write("/proc/sys/kernel/hostname", hostname + "\n");
    write("/proc/sys/kernel/random/boot_id", "3c1e6a2e-0000-4000-8000-000000000001\n");
    write("/etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n");
    host =
        new Host(
            root,
            path -> new Host.Owner(0, Files.isExecutable(path(path)) ? 0755 : 0644),
            () -> List.of("127.0.0.1"),
            System::nanoTime,
            log::add);
  }

  /** A path as on the board ({@code /etc/frc-spotter/agent.json}), in the folder. */
  public Path path(String absolute) {
    return root.resolve(absolute.substring(1));
  }

  /** Writes a file on the board, making its folders. */
  public LocalAgent write(String absolute, String text) {
    try {
      Path file = path(absolute);
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return this;
  }

  /** An installed pack, {@code /etc/frc-spotter/packs/<name>/pack.yaml}. */
  public LocalAgent pack(String name, String yaml) {
    return write(Packs.INSTALLED + "/" + name + "/" + Pack.FILE, yaml);
  }

  /** A shell script in an installed pack's folder, executable: what {@code run: [./name]} runs. */
  public LocalAgent script(String pack, String name, String body) {
    String absolute = Packs.INSTALLED + "/" + pack + "/" + name;
    write(absolute, "#!/bin/sh\n" + body + "\n");
    try {
      Files.setPosixFilePermissions(path(absolute), PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return this;
  }

  /** The board's {@code /etc/frc-spotter/agent.json}. */
  public LocalAgent config(String json) {
    return write(AgentConfig.PATH, json);
  }

  /**
   * Starts the agent, reading its settings and packs afresh: on a free port the first time, then on
   * the same one.
   */
  public synchronized LocalAgent start() throws IOException {
    if (server != null) {
      throw new IllegalStateException("the agent is already running");
    }
    Agent started = new Agent(host, Configuration.read(host), VERSION, "127.0.0.1", this::exited);
    server = new AgentServer(started, new InetSocketAddress("127.0.0.1", port));
    port = server.port();
    agent = started;
    started.start();
    return this;
  }

  /** The agent ended itself, after a push: systemd starts it again. */
  private void exited() {
    exits.incrementAndGet();
    Thread restart =
        new Thread(
            () -> {
              synchronized (this) {
                if (closed) {
                  return;
                }
                stop();
                try {
                  start();
                } catch (IOException e) {
                  log.add("Couldn't start again: " + e);
                }
              }
            },
            "local-agent-restart");
    restart.setDaemon(true);
    restart.start();
  }

  /** Stops the agent, its collectors, and every connection to it. */
  public synchronized void stop() {
    AgentServer running = server;
    if (running != null) {
      running.close();
    }
    server = null;
    agent = null;
  }

  /** The port it serves on, once started. */
  public synchronized int port() {
    return port;
  }

  /** Its address, as a manager is given it: {@code 127.0.0.1:<port>}. */
  public String address() {
    return "127.0.0.1:" + port();
  }

  /**
   * Sets a value as its collector would, by its id ({@code pack.collector.field}), and sends it to
   * every stream.
   */
  public void set(String id, Spotter.FieldValue value) {
    Agent running = running();
    Spotter.Description description = running.description();
    for (int i = 0; i < description.getValues().length(); i++) {
      if (description.getValues().get(i).getId().equals(id)) {
        running.store().set(i, List.of(value));
        return;
      }
    }
    throw new IllegalArgumentException("no value " + id);
  }

  /** Its description, as it is now. */
  public Spotter.Description description() {
    return running().description();
  }

  /** The folder its pushed packs are in, {@code /var/lib/frc-spotter/packs}. */
  public Path pushedPacks() {
    return path(Packs.PUSHED);
  }

  /** How often it ended for systemd to start it again: after each push. */
  public int exits() {
    return exits.get();
  }

  /** What it logged, one line each. */
  public List<String> log() {
    return List.copyOf(log);
  }

  private synchronized Agent running() {
    Agent running = agent;
    if (running == null) {
      throw new IllegalStateException("the agent isn't running");
    }
    return running;
  }

  /** Stops it for good: it isn't started again, even if it ended itself. */
  @Override
  public synchronized void close() {
    closed = true;
    stop();
  }
}
