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
 */
record Configuration(
    AgentConfig config, Packs.Loaded packs, List<String> problems, String unreadable) {
  Configuration {
    problems = List.copyOf(problems);
  }

  /** Reads this board's settings and packs. */
  static Configuration read(Host host) {
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
    String refused =
        !unreadable.isEmpty()
            ? "agent.json can't be read"
            : config.acceptPushes() ? "" : "agent.json refuses pushes";
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
    return new Configuration(config, packs, problems, unreadable);
  }

  /** Whether the board takes pushes: its settings say so, and can be read. */
  boolean acceptsPushes() {
    return unreadable.isEmpty() && config.acceptPushes();
  }
}
