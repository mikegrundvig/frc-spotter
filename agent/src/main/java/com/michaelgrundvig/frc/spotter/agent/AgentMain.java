package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/**
 * The coprocessor agent: {@code java -jar frc-spotter.jar [serve] [--port=N] [--bind=ADDRESS]
 * [--controller=ADDRESS] [--root=DIR]} serves the API (docs/agent.md) on the port its configuration
 * names ({@code /etc/frc-spotter/agent.json}), else the image's stamp file's, else 5808, unless
 * {@code --port} says. {@code --controller} names the one address a shutdown is taken from, over
 * the configuration's (a test's, where the robot is a test process behind the container runtime's
 * port forwarding). {@code --root} reads the computer's files from a folder rather than {@code /}:
 * a fixture tree, for tests.
 */
public final class AgentMain {
  /** How long any one of the agent's own commands may take. */
  static final Duration TIMEOUT = Duration.ofSeconds(2);

  private AgentMain() {}

  /** Runs the agent; see the class. */
  public static void main(String[] args) throws Exception {
    // Bound every request and answer, before the web server reads its settings: 4 s to send a
    // request, 60 to take an answer, 32 connections at once.
    System.setProperty("sun.net.httpserver.maxReqTime", "4");
    System.setProperty("sun.net.httpserver.maxRspTime", "60");
    System.setProperty("sun.net.httpserver.maxIdleConnections", "8");
    System.setProperty("jdk.httpserver.maxConnections", "32");
    ProcessCommands commands = new ProcessCommands();
    Map<String, String> options = options(List.of(args));
    Host host =
        Host.system(
            Path.of(options.getOrDefault("root", "/")),
            commands,
            message -> System.err.println(message));
    try (AgentServer server = serve(host, List.of(args), AgentMain::background)) {
      System.err.println("Coprocessor agent serving on port " + server.port());
      new CountDownLatch(1).await();
    }
  }

  /** Starts serving a host, with the command line's options, and starts its probes. */
  static AgentServer serve(Host host, List<String> args, java.util.concurrent.Executor executor)
      throws IOException {
    Map<String, String> options = options(args);
    String controller = options.get("controller");
    if (controller != null) {
      checkAddress(controller);
    }
    Configuration configuration = Configuration.read(host);
    for (String problem : configuration.problems()) {
      host.log(problem);
    }
    Agent agent = new Agent(host, configuration, TIMEOUT, executor, controller);
    AgentServer server =
        new AgentServer(
            agent,
            new InetSocketAddress(
                options.getOrDefault("bind", "0.0.0.0"),
                port(options, configuration, agent.stampFile().agentPort())),
            new RateLimitedLog(host::log, host::monotonicMicros, AgentServer.REFUSAL_LOG_MICROS));
    agent.start();
    return server;
  }

  /**
   * The port to serve on: {@code --port}'s, else the configuration's (when it could be read), else
   * the stamp file's.
   */
  static int port(Map<String, String> options, Configuration configuration, int stampPort) {
    if (options.containsKey("port")) {
      return Integer.parseInt(options.getOrDefault("port", "0"));
    }
    boolean configured =
        !configuration.source().isEmpty() && configuration.config() != AgentConfig.NONE;
    return configured ? configuration.config().port() : stampPort;
  }

  /** An IPv4 address written out: four numbers from 0 to 255, without leading zeros. */
  private static final Pattern IPV4 =
      Pattern.compile(
          "((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");

  /**
   * Checks that {@code --controller} is an IPv4 address written out, as a request's address is
   * compared with it: never a name, which would be looked up.
   */
  static void checkAddress(String address) {
    if (!IPV4.matcher(address).matches()) {
      throw new IllegalArgumentException(
          "--controller must be an IPv4 address, such as 10.12.34.2: " + address);
    }
  }

  /**
   * Runs work that outlasts a request (reading health afresh, a shutdown once its request is
   * answered) on a thread of its own, which doesn't keep the agent from exiting.
   */
  static void background(Runnable work) {
    Thread thread = new Thread(work, "spotter-work");
    thread.setDaemon(true);
    thread.start();
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
            "Usage: frc-spotter [serve] [--port=N] [--bind=ADDRESS]"
                + " [--controller=ADDRESS] [--root=DIR]");
      }
      options.put(arg.substring(2, equals), arg.substring(equals + 1));
    }
    return options;
  }
}
