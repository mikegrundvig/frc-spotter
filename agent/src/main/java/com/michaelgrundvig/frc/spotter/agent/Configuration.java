package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.probes.Pack;
import com.michaelgrundvig.frc.spotter.probes.PackException;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What this computer's agent runs, read once as it starts: the overrides in {@link
 * AgentConfig#PATH}, if there are any, and the packs in {@link Pack#DIRECTORY}. The agent needs
 * neither: with no packs, it reports the computer alone.
 *
 * <p>A pack is trusted by its file, as {@code sshd} trusts its configuration: one that isn't
 * root's, or that its group or anyone else may write, is ignored. So is one that can't be read, or
 * whose name or probes another pack took first. An overrides file that can't be read is ignored
 * too. Each says why in every health answer's problems; the agent runs on, as a computer that can't
 * be checked should still say how it is.
 *
 * @param config the overrides; {@link AgentConfig#DEFAULT} when there are none, or they can't be
 *     read
 * @param probes the packs read, combined
 * @param source where the overrides were read from; empty when there's no file
 * @param problems what couldn't be read or was ignored, each saying where and why
 */
record Configuration(AgentConfig config, ProbeSet probes, String source, List<String> problems) {
  /** The user ID a pack's file must be owned by: root. */
  static final int ROOT = 0;

  Configuration {
    problems = List.copyOf(problems);
  }

  /** Reads this computer's overrides and packs. */
  static Configuration read(Host host) {
    List<String> problems = new ArrayList<>();
    AgentConfig config = AgentConfig.DEFAULT;
    String source = "";
    try {
      Optional<String> text = host.read(AgentConfig.PATH);
      if (text.isPresent()) {
        source = AgentConfig.PATH;
        config = AgentConfig.parse(text.get());
      }
    } catch (IOException | IllegalArgumentException | JsonException e) {
      problems.add("configuration ignored: " + AgentConfig.PATH + ": " + e.getMessage());
    }
    return new Configuration(config, packs(host, problems), source, problems);
  }

  /** Every pack in {@link Pack#DIRECTORY} that can be trusted and read, in its files' order. */
  static ProbeSet packs(Host host, List<String> problems) {
    ProbeSet set = ProbeSet.EMPTY;
    List<String> names;
    try {
      names = host.list(Pack.DIRECTORY);
    } catch (IOException e) {
      problems.add("packs: " + Pack.DIRECTORY + ": " + e.getMessage());
      return set;
    }
    for (String name : names) {
      if (!name.endsWith(Pack.SUFFIX)) {
        continue;
      }
      String path = Pack.DIRECTORY + "/" + name;
      try {
        Host.Owner owner = host.owner(path);
        if (owner.uid() != ROOT) {
          problems.add(
              "pack ignored: " + path + " isn't root's (its owner is user " + owner.uid() + ")");
          continue;
        }
        if (owner.writableByOthers()) {
          problems.add(
              "pack ignored: "
                  + path
                  + " may be written by its group or others (mode "
                  + Integer.toOctalString(owner.mode())
                  + "): chmod go-w it");
          continue;
        }
        Optional<String> text = host.read(path);
        if (text.isEmpty()) {
          continue; // gone as it was read
        }
        set = set.with(Pack.parseYaml(text.get(), path));
      } catch (PackException e) {
        for (String problem : e.problems()) {
          problems.add("pack ignored: " + problem);
        }
      } catch (IOException | IllegalArgumentException e) {
        problems.add("pack ignored: " + path + ": " + e.getMessage());
      }
    }
    return set;
  }
}
