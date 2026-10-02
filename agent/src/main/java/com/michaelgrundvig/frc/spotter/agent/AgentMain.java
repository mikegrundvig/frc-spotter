package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsException;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The coprocessor agent: {@code java -jar coprocessor-agent.jar [serve] [--port=N] [--bind=ADDRESS]
 * [--database=PATH] [--unit=NAME]} serves the API (coprocessor/README.md), on the port the stamp
 * file says unless {@code --port} does. {@code settings-db ROWS_DIR EMPTY_DB OUT_DB} builds
 * PhotonVision's database from committed settings, for stamping an image, and prints their hash.
 */
public final class AgentMain {
  /** Where PhotonVision keeps its settings on the image. */
  static final String DATABASE = "/opt/photonvision/photonvision_config/photon.sqlite";

  /** PhotonVision's systemd unit. */
  static final String UNIT = "photonvision.service";

  /** How long any one command may take. */
  static final Duration TIMEOUT = Duration.ofSeconds(2);

  private AgentMain() {}

  /** Runs the agent, or a tool; see the class. */
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("settings-db")) {
      System.exit(settingsDb(List.of(args).subList(1, args.length), System.out, System.err));
    }
    // Bound every request and answer, before the web server reads its settings: 4 s to send a
    // request, 60 to take an answer, 32 connections at once.
    System.setProperty("sun.net.httpserver.maxReqTime", "4");
    System.setProperty("sun.net.httpserver.maxRspTime", "60");
    System.setProperty("sun.net.httpserver.maxIdleConnections", "8");
    System.setProperty("jdk.httpserver.maxConnections", "32");
    ProcessCommands commands = new ProcessCommands();
    Host host = Host.system(commands, message -> System.err.println(message));
    try (AgentServer server =
        serve(host, List.of(args), runnable -> Thread.ofVirtual().start(runnable))) {
      System.err.println("Coprocessor agent serving on port " + server.port());
      new CountDownLatch(1).await();
    }
  }

  /** Starts serving a host, with the command line's options. */
  static AgentServer serve(Host host, List<String> args, java.util.concurrent.Executor executor)
      throws IOException {
    Map<String, String> options = options(args);
    Agent agent =
        new Agent(
            host,
            options.getOrDefault("database", DATABASE),
            options.getOrDefault("unit", UNIT),
            TIMEOUT,
            executor,
            Agent::controllerOf);
    int port =
        options.containsKey("port")
            ? Integer.parseInt(options.getOrDefault("port", "0"))
            : agent.stampFile().agentPort();
    return new AgentServer(
        agent,
        new InetSocketAddress(options.getOrDefault("bind", "0.0.0.0"), port),
        new RateLimitedLog(host::log, host::monotonicMicros, AgentServer.REFUSAL_LOG_MICROS));
  }

  /** {@code --name=value} options, after an optional {@code serve}. */
  static Map<String, String> options(List<String> args) {
    Map<String, String> options = new HashMap<>();
    for (String arg : args) {
      if (arg.equals("serve")) {
        continue;
      }
      int equals = arg.indexOf('=');
      if (!arg.startsWith("--") || equals < 0) {
        throw new IllegalArgumentException(
            "Usage: coprocessor-agent [serve] [--port=N] [--bind=ADDRESS] [--database=PATH]"
                + " [--unit=NAME] | settings-db ROWS_DIR EMPTY_DB OUT_DB");
      }
      options.put(arg.substring(2, equals), arg.substring(equals + 1));
    }
    return options;
  }

  /**
   * {@code settings-db ROWS_DIR EMPTY_DB OUT_DB}: builds {@code OUT_DB} from a copy of {@code
   * EMPTY_DB} (the database the pinned PhotonVision made, empty) and the committed settings in
   * {@code ROWS_DIR}, reads it back to check nothing was lost, and prints the settings' hash. The
   * exit status.
   */
  static int settingsDb(List<String> args, PrintStream out, PrintStream err) {
    if (args.size() != 3) {
      err.println("Usage: coprocessor-agent settings-db ROWS_DIR EMPTY_DB OUT_DB");
      return 2;
    }
    Path rows = Path.of(args.get(0));
    Path empty = Path.of(args.get(1));
    Path target = Path.of(args.get(2));
    try {
      Settings settings =
          SettingsFiles.read(rows)
              .orElseThrow(() -> new SettingsException("there are no settings in " + rows));
      if (!Files.isRegularFile(empty)) {
        throw new SettingsException("there's no database at " + empty);
      }
      Files.copy(empty, target, StandardCopyOption.REPLACE_EXISTING);
      Settings written;
      try (Connection connection = Sqlite.open(target)) {
        SettingsDatabase.write(connection, settings);
        written = SettingsDatabase.read(connection);
      }
      if (!written.equals(settings)) {
        throw new SettingsException(target + " doesn't hold the settings it was given");
      }
      out.println(settings.hash());
      return 0;
    } catch (IOException | SQLException | RuntimeException e) {
      err.println("settings-db: " + e.getMessage());
      return 1;
    }
  }
}
