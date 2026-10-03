package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The coprocessor agent: {@code java -jar frc-spotter.jar [serve] [--port=N] [--bind=ADDRESS]
 * [--controller=ADDRESS] [--root=DIR]} speaks protocol 2 (docs/agent.md) on 5808, with nothing to
 * configure. {@code /etc/frc-spotter/agent.json} may set the port, the controller's address, and
 * the address it listens on, and the command line overrides that: {@code --controller} names the
 * one address a write is taken from (a test's, where the robot is a test process behind the
 * container runtime's port forwarding). {@code --root} reads the board's files from a folder rather
 * than {@code /}: a test's.
 */
public final class AgentMain {
  /** The agent's version when its jar doesn't say: run from a build's classes. */
  static final String UNRELEASED = "unreleased";

  private AgentMain() {}

  /** Runs the agent; see the class. */
  public static void main(String[] args) throws Exception {
    // Bound every request and answer, before the web server reads its settings: 4 s to send a
    // request, 32 connections at once.
    System.setProperty("sun.net.httpserver.maxReqTime", "4");
    System.setProperty("sun.net.httpserver.maxIdleConnections", "8");
    System.setProperty("jdk.httpserver.maxConnections", "32");
    Map<String, String> options = options(List.of(args));
    Host host =
        Host.system(
            Path.of(options.getOrDefault("root", "/")), message -> System.err.println(message));
    // After a push the agent ends, and systemd starts it again to read the new packs.
    try (AgentServer server = serve(host, List.of(args), version(), () -> System.exit(0))) {
      System.err.println("Coprocessor agent serving protocol 2 on port " + server.port());
      new CountDownLatch(1).await();
    }
  }

  /** The agent's version, from its jar. */
  static String version() {
    String version = AgentMain.class.getPackage().getImplementationVersion();
    return version == null ? UNRELEASED : version;
  }

  /**
   * Starts serving a board, with the command line's options, and starts its collectors.
   *
   * @param exit ends the agent, for systemd to start it again: after a push
   */
  static AgentServer serve(Host host, List<String> args, String version, Runnable exit)
      throws IOException {
    Map<String, String> options = options(args);
    String controller = options.get("controller");
    if (controller != null) {
      checkAddress(controller, "--controller");
    }
    String bind = options.get("bind");
    if (bind != null) {
      checkAddress(bind, "--bind");
    }
    Configuration configuration = Configuration.read(host);
    for (String problem : configuration.problems()) {
      host.log(problem);
    }
    Agent agent = new Agent(host, configuration, version, controller, exit);
    AgentServer server =
        new AgentServer(
            agent,
            new InetSocketAddress(bind(options, configuration), port(options, configuration)));
    agent.start();
    return server;
  }

  /** The port to serve on: {@code --port}'s, else the settings' (5808 unless they say). */
  static int port(Map<String, String> options, Configuration configuration) {
    if (options.containsKey("port")) {
      return Integer.parseInt(options.getOrDefault("port", "0"));
    }
    return configuration.config().port();
  }

  /** The address to listen on: {@code --bind}'s, else the settings', else every one. */
  static String bind(Map<String, String> options, Configuration configuration) {
    String bind = options.getOrDefault("bind", configuration.config().bind());
    return bind.isEmpty() ? "0.0.0.0" : bind;
  }

  /**
   * Checks that an address given on the command line is an IPv4 address written out, as a request's
   * address is compared with it: never a name, which would be looked up.
   */
  static void checkAddress(String address, String option) {
    if (!AgentConfig.IPV4.matcher(address).matches()) {
      throw new IllegalArgumentException(
          option + " must be an IPv4 address, such as 10.12.34.2: " + address);
    }
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
