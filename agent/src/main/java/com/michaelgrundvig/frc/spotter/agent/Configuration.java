package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import com.michaelgrundvig.frc.spotter.table.Pack;
import com.michaelgrundvig.frc.spotter.table.Packs;
import com.michaelgrundvig.frc.spotter.table.TableException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What makes this computer's agent its own: its configuration ({@link AgentConfig}, the one file a
 * team writes per computer) and the probe set compiled from it and the packs installed, read once
 * as the agent starts. The agent package is the same on every computer; this is the difference.
 *
 * <p>A configuration or pack that can't be read doesn't stop the agent: it runs with the built-in
 * pack alone (or nothing), says why in every health answer's problems, and takes no actions from a
 * controller it couldn't read. A computer that can't be configured should still say how it is.
 *
 * @param config the configuration; {@link AgentConfig#NONE} when there's none, or it can't be read
 * @param probes the probe set compiled from it and the packs it names
 * @param source where the configuration was read from; empty when there's none
 * @param problems what couldn't be read, each saying where and why
 */
record Configuration(AgentConfig config, ProbeSet probes, String source, List<String> problems) {
  Configuration {
    problems = List.copyOf(problems);
  }

  /**
   * Reads this computer's configuration (from {@link AgentConfig#PATH}, else {@link
   * AgentConfig#DATA_PATH}) and compiles its probes from the packs installed under {@link
   * Packs#PACKS_DIR}.
   */
  static Configuration read(Host host) {
    List<String> problems = new ArrayList<>();
    AgentConfig config = AgentConfig.NONE;
    String source = "";
    for (String path : List.of(AgentConfig.PATH, AgentConfig.DATA_PATH)) {
      try {
        Optional<String> text = host.read(path);
        if (text.isPresent()) {
          config = AgentConfig.parse(text.get(), path);
          source = path;
          break;
        }
      } catch (IOException | IllegalArgumentException | JsonException e) {
        problems.add("configuration: " + path + ": " + e.getMessage());
        source = path;
        break;
      }
    }
    List<Pack> packs = new ArrayList<>();
    List<String> names = new ArrayList<>();
    names.add(Pack.BUILTIN);
    names.addAll(config.packs());
    for (String name : names) {
      String path = Packs.PACKS_DIR + "/" + name + "/" + Pack.JSON_FILE;
      try {
        Optional<String> text = host.read(path);
        if (text.isEmpty()) {
          problems.add("pack " + name + ": not installed (no " + path + ")");
          continue;
        }
        packs.add(Pack.fromJson(Json.parse(text.get()), path));
      } catch (IOException | IllegalArgumentException | JsonException e) {
        problems.add("pack " + name + ": " + e.getMessage());
      }
    }
    ProbeSet probes = ProbeSet.EMPTY;
    try {
      probes = Packs.compile(config, packs);
    } catch (TableException e) {
      problems.addAll(e.problems().stream().map(problem -> "probes: " + problem).toList());
    }
    return new Configuration(config, probes, source, problems);
  }
}
