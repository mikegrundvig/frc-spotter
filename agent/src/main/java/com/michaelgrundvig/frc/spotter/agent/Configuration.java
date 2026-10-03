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
 * @param config its settings; {@link AgentConfig#DEFAULT} when it has none, or they can't be read
 * @param packs its packs
 * @param problems what it ignored, and why: its settings', then its packs'
 */
record Configuration(AgentConfig config, Packs.Loaded packs, List<String> problems) {
  Configuration {
    problems = List.copyOf(problems);
  }

  /** Reads this board's settings and packs. */
  static Configuration read(Host host) {
    List<String> problems = new ArrayList<>();
    AgentConfig config = AgentConfig.DEFAULT;
    try {
      Optional<String> text = host.read(AgentConfig.PATH);
      if (text.isPresent()) {
        config = AgentConfig.parse(text.get());
      }
    } catch (IOException | IllegalArgumentException | JsonException e) {
      problems.add(AgentConfig.PATH + ": ignored: " + e.getMessage());
    }
    Packs.Loaded packs = Packs.load(host, config.acceptPushes());
    problems.addAll(packs.problems());
    return new Configuration(config, packs, problems);
  }
}
