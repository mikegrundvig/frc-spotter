package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A real agent for a test: Spotter's agent program, run as a process of its own, serving protocol 2
 * on a local port: a board's files in a folder (as {@code --root} reads them), packs the test
 * writes, scripts the agent really runs. It takes writes from 127.0.0.1, the test playing the robot
 * controller. It's the program built with its test feature, {@code --fixture}: its files count as
 * root's, written by root alone, as an installer leaves them, so its installed packs are trusted;
 * and a value can be set as its collector would set it.
 *
 * <p>Stopping it and starting it again is an agent restart, on the same port. When the agent ends
 * itself (after a push), it's started again at once, as systemd starts it. The program is the one
 * the build names in the system property {@code spotter.agentBinary}.
 */
public final class LocalAgent implements AutoCloseable {
  /** The agent's version, as its description says it. */
  public static final String VERSION = "0.4.0-test";

  /** What the agent says once it serves, with the port it bound. */
  private static final Pattern SERVING = Pattern.compile("serving protocol 2 on port (\\d+)");

  /** What a test's agent says where a board's would end itself, for systemd to start it again. */
  private static final String WOULD_END = "Would end now, for systemd to start it again";

  /** How long a start may take before the test fails, saying what the agent said. */
  private static final long START_MILLIS = 30_000;

  private final Path root;
  private final List<String> log = new CopyOnWriteArrayList<>();
  private final AtomicInteger exits = new AtomicInteger();
  private int port;
  private @Nullable Process process;
  private boolean closed;
  private volatile boolean endsItself = true;

  /** A board named {@code hostname}, in a folder, with nothing configured and no packs. */
  public LocalAgent(Path dir, String hostname) {
    root = dir.resolve("root");
    write("/proc/sys/kernel/hostname", hostname + "\n");
    write("/proc/sys/kernel/random/boot_id", "3c1e6a2e-0000-4000-8000-000000000001\n");
    write("/etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n");
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
    return write("/etc/frc-spotter/packs/" + name + "/pack.yaml", yaml);
  }

  /** A shell script in an installed pack's folder, executable: what {@code run: [./name]} runs. */
  public LocalAgent script(String pack, String name, String body) {
    String absolute = "/etc/frc-spotter/packs/" + pack + "/" + name;
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
    return write("/etc/frc-spotter/agent.json", json);
  }

  /** The agent program the build made for the tests. */
  private static String binary() {
    String binary = System.getProperty("spotter.agentBinary");
    if (binary == null || !Files.isExecutable(Path.of(binary))) {
      throw new IllegalStateException(
          "no agent program to test with: the build names it in spotter.agentBinary ("
              + binary
              + ")");
    }
    return binary;
  }

  /**
   * Starts the agent, reading its settings and packs afresh: on a free port the first time, then on
   * the same one. Returns once it serves.
   */
  public synchronized LocalAgent start() throws IOException {
    if (process != null) {
      throw new IllegalStateException("the agent is already running");
    }
    List<String> command = new ArrayList<>();
    command.add(binary());
    command.add("--fixture");
    command.add("--fixture-version=" + VERSION);
    if (!endsItself) {
      command.add("--fixture-stays");
    }
    command.add("--root=" + root);
    command.add("--controller=127.0.0.1");
    command.add("--bind=127.0.0.1");
    command.add("--port=" + port);
    Process started = new ProcessBuilder(command).redirectErrorStream(true).start();
    int[] served = {0};
    Object said = new Object();
    Thread reader =
        new Thread(
            () -> read(started.getInputStream(), served, said), "local-agent-log-" + started.pid());
    reader.setDaemon(true);
    reader.start();
    long until = System.currentTimeMillis() + START_MILLIS;
    synchronized (said) {
      while (served[0] == 0 && started.isAlive() && System.currentTimeMillis() < until) {
        try {
          said.wait(Math.max(1, until - System.currentTimeMillis()));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    if (served[0] == 0) {
      started.destroyForcibly();
      throw new IOException("the agent didn't start; it said: " + String.join("\n", log));
    }
    port = served[0];
    process = started;
    Thread watcher = new Thread(() -> watch(started), "local-agent-watch-" + started.pid());
    watcher.setDaemon(true);
    watcher.start();
    return this;
  }

  /** Reads what the agent says, a line at a time, into its log; tells the start its port. */
  private void read(InputStream said, int[] served, Object lock) {
    try (BufferedReader lines =
        new BufferedReader(new InputStreamReader(said, StandardCharsets.UTF_8))) {
      String line;
      while ((line = lines.readLine()) != null) {
        log.add(line);
        Matcher serving = SERVING.matcher(line);
        if (serving.find()) {
          synchronized (lock) {
            served[0] = Integer.parseInt(serving.group(1));
            lock.notifyAll();
          }
        }
        if (line.startsWith(WOULD_END)) {
          exits.incrementAndGet();
        }
      }
    } catch (IOException e) {
      // The agent is gone.
    }
    synchronized (lock) {
      lock.notifyAll();
    }
  }

  /** Waits for the agent to end: when it ends itself (after a push), systemd starts it again. */
  private void watch(Process started) {
    try {
      started.waitFor();
    } catch (InterruptedException e) {
      return;
    }
    synchronized (this) {
      if (process != started) {
        // Stopped, as asked.
        return;
      }
      process = null;
      exits.incrementAndGet();
      if (closed || !endsItself) {
        return;
      }
      try {
        start();
      } catch (IOException e) {
        log.add("Couldn't start again: " + e);
      }
    }
  }

  /**
   * From now on, the agent doesn't end itself after a push, as one that fails to would: it takes
   * the packs, answers, and runs on with the old ones. Its next start does so.
   */
  public LocalAgent wontEndItself() {
    endsItself = false;
    return this;
  }

  /**
   * Stops the agent, cleanly, as systemd does (SIGTERM): its collectors, its runs' processes, and
   * every connection to it.
   */
  public synchronized void stop() {
    Process running = process;
    process = null;
    if (running == null) {
      return;
    }
    running.destroy();
    try {
      if (!running.waitFor(10, TimeUnit.SECONDS)) {
        running.destroyForcibly().waitFor();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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
    try {
      HttpURLConnection connection = connect("/fixture/values/" + id);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      byte[] body = value.toByteArray();
      connection.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(body);
      }
      int status = connection.getResponseCode();
      if (status == 404) {
        throw new IllegalArgumentException("no value " + id);
      }
      if (status != 204) {
        throw new IllegalStateException("setting " + id + " answered " + status);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Its description, as it is now. */
  public Spotter.Description description() {
    try {
      HttpURLConnection connection = connect(Protocol.DESCRIBE);
      try (InputStream in = connection.getInputStream()) {
        return Spotter.Description.parseFrom(in.readAllBytes());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private HttpURLConnection connect(String path) throws IOException {
    if (process == null) {
      throw new IllegalStateException("the agent isn't running");
    }
    HttpURLConnection connection =
        (HttpURLConnection) URI.create("http://" + address() + path).toURL().openConnection();
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(5_000);
    return connection;
  }

  /** The folder its pushed packs are in, {@code /var/lib/frc-spotter/packs}. */
  public Path pushedPacks() {
    return path("/var/lib/frc-spotter/packs");
  }

  /** How often it ended for systemd to start it again: after each push. */
  public int exits() {
    return exits.get();
  }

  /** What it logged, one line each. */
  public List<String> log() {
    return List.copyOf(log);
  }

  /** Stops it for good: it isn't started again, even if it ended itself. */
  @Override
  public synchronized void close() {
    closed = true;
    stop();
  }
}
