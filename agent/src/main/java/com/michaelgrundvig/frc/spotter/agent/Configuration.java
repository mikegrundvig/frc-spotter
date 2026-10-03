package com.michaelgrundvig.frc.spotter.agent;

import io.avaje.json.JsonException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What this board's agent runs, read once as it starts: its settings, {@link AgentConfig#PATH}, if
 * it has any, and its packs. It needs neither: with no packs, it's still a valid agent, reporting
 * its identity, sending heartbeats, and offering power-off and reboot.
 *
 * <p>Settings that are there but can't be read fail closed: a board whose owner meant it to refuse
 * pushes, or to require signatures, mustn't take writes because a typo dropped the file. So every
 * write is refused (and pushed packs ignored) until the file is fixed; reading works as ever.
 *
 * @param config its settings; {@link AgentConfig#DEFAULT} when it has none, or they can't be read
 * @param packs its packs
 * @param problems what it ignored, and why: its settings', then its packs'
 * @param unreadable why its settings are there and can't be read, which refuses every write; empty
 *     when they're fine, or not there
 * @param controllerNamed whether the robot controller is named (on the command line, or by its
 *     address or its team in the settings), not only worked out from the board's own address: only
 *     a named one may push packs, or run more than the built-in actions
 */
record Configuration(
    AgentConfig config,
    Packs.Loaded packs,
    List<String> problems,
    String unreadable,
    boolean controllerNamed) {
  Configuration {
    problems = List.copyOf(problems);
  }

  /** Settings that name the robot controller in themselves, if any do. */
  Configuration(AgentConfig config, Packs.Loaded packs, List<String> problems, String unreadable) {
    this(config, packs, problems, unreadable, !config.namedController().isEmpty());
  }

  /** Reads this board's settings and packs, its controller not named on the command line. */
  static Configuration read(Host host) {
    return read(host, false);
  }

  /**
   * Reads this board's settings and packs.
   *
   * @param commandLine whether the command line names the controller
   */
  static Configuration read(Host host, boolean commandLine) {
    List<String> problems = new ArrayList<>();
    AgentConfig config = AgentConfig.DEFAULT;
    String unreadable = "";
    try {
      Optional<String> text = host.read(AgentConfig.PATH);
      if (text.isPresent()) {
        config = AgentConfig.parse(text.get());
      }
    } catch (IOException | IllegalArgumentException | JsonException e) {
      unreadable = AgentConfig.PATH + " can't be read: " + e.getMessage();
      problems.add(
          AgentConfig.PATH + ": can't be read, so every write is refused: " + e.getMessage());
    }
    boolean named = commandLine || !config.namedController().isEmpty();
    String refused = refused(unreadable, config, named);
    // The last good start (LastGood): pushed packs are loaded behind a marker, and set aside for
    // a start that finds the last one with them didn't stay up.
    boolean setAside = false;
    try {
      if (refused.isEmpty() && !host.list(Packs.PUSHED).isEmpty()) {
        setAside = !LastGood.begin(host);
      }
    } catch (IOException e) {
      // The folder can't be read: Packs.load says so.
    }
    Packs.Loaded packs = Packs.load(host, refused, setAside);
    problems.addAll(packs.problems());
    return new Configuration(config, packs, problems, unreadable, named);
  }

  /** Why a board refuses pushes, as its problems and its 403 say; empty when it takes them. */
  private static String refused(String unreadable, AgentConfig config, boolean named) {
    if (!unreadable.isEmpty()) {
      return "its agent.json can't be read";
    }
    if (!config.acceptPushes()) {
      return "its agent.json says acceptPushes: false";
    }
    if (!named) {
      return "its agent.json names no controller or team, and pushes are taken from a named one"
          + " only";
    }
    return "";
  }

  /**
   * Whether the board takes pushes: its settings say so, can be read, and name the robot
   * controller.
   */
  boolean acceptsPushes() {
    return pushesRefused().isEmpty();
  }

  /** Why the board refuses pushes; empty when it takes them. */
  String pushesRefused() {
    return refused(unreadable, config, controllerNamed);
  }
}
