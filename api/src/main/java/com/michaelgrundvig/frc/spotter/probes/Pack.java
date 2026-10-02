package com.michaelgrundvig.frc.spotter.probes;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A pack: one YAML file of probes on one piece of software or one board, and the journal units it
 * serves (docs/agent.md, "Packs"). It holds no code and needs no build step or privileges. A team
 * copies the packs it wants from Spotter's catalog ({@code packs/} in its repository) into {@link
 * #DIRECTORY}, or writes its own; the agent reads every {@code *.yaml} there as it starts.
 *
 * <pre>
 * pack: vision
 * journalUnits: [vision.service]
 * probes:
 *   - id: vision.unit
 *     kind: unit
 *     unit: vision.service
 *     every: 2
 * </pre>
 *
 * @param name its name: lowercase letters, digits, and hyphens
 * @param journalUnits the systemd units whose journal the agent serves and counts for it
 * @param probes its probes, in the order it writes them, each checked
 */
public record Pack(String name, List<String> journalUnits, List<Probe> probes) {
  /** What a pack's name may be. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** Where the agent reads packs: every {@code *.yaml} in it, in the order of their names. */
  public static final String DIRECTORY = "/etc/frc-spotter/packs";

  /** The ending of a pack's file name. */
  public static final String SUFFIX = ".yaml";

  public Pack {
    journalUnits = List.copyOf(journalUnits);
    probes = List.copyOf(probes);
  }

  /**
   * Reads and checks a pack from its YAML, as the agent does.
   *
   * @param source names the file in problems: {@code photonvision.yaml}
   * @throws PackException listing every problem, each with its file and line
   */
  public static Pack parseYaml(String yaml, String source) {
    return PackYaml.pack(yaml, source);
  }
}
