package com.michaelgrundvig.frc.spotter.agent.server;

import com.michaelgrundvig.frc.spotter.agent.Configuration;
import com.michaelgrundvig.frc.spotter.agent.packs.Field;
import com.michaelgrundvig.frc.spotter.agent.packs.Pack;
import com.michaelgrundvig.frc.spotter.agent.packs.Packs;
import com.michaelgrundvig.frc.spotter.agent.values.Collectors;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.List;
import java.util.zip.CRC32;

/**
 * What a board has, as its description says it: its identity, its packs, the values they report,
 * the logs they serve, the actions they offer (after the two built in), and what the agent ignored.
 * What runs stays on the board: no command, URL or path a pack names is in it.
 *
 * <p>Its revision is a hash of everything else in it, so it changes whenever anything does, and an
 * agent restarted with the same packs on the same board describes itself with the same revision.
 */
public final class Describer {
  private final String version;
  private final Configuration configuration;
  private final Packs.Loaded packs;

  /**
   * @param version the agent's version
   * @param configuration the board's settings and packs, and what it ignored
   */
  public Describer(String version, Configuration configuration) {
    this.version = version;
    this.configuration = configuration;
    this.packs = configuration.packs();
  }

  /** The description, with this identity, and its revision. */
  public Spotter.Description describe(Spotter.Identity identity) {
    Spotter.Description description =
        Spotter.Description.newInstance()
            .setAgentVersion(version)
            .setIdentity(identity)
            .setPushedPacks(packs.pushedHash())
            .setRefusesPushes(!configuration.acceptsPushes())
            .setRequiresSignatures(!configuration.config().trustedKeys().isEmpty());
    for (Pack pack : packs.packs()) {
      description.addPacks(pack.description());
    }
    for (Collectors.Slot slot : Collectors.slots(packs.packs())) {
      for (Field field : slot.collector().fields()) {
        description.addValues(field.declaration(slot.id(field)));
      }
    }
    for (Pack pack : packs.packs()) {
      for (Pack.Log log : pack.logs()) {
        description.addLogs(
            Spotter.LogDeclaration.newInstance()
                .setId(pack.name() + "." + log.id())
                .setLabel(log.label()));
      }
    }
    for (Pack.Action action : Pack.BUILT_IN) {
      description.addActions(action.declaration(Pack.CORE));
    }
    for (Pack pack : packs.packs()) {
      for (Pack.Action action : pack.actions()) {
        description.addActions(action.declaration(pack.name()));
      }
    }
    List<String> problems = configuration.problems();
    for (int i = 0; i < problems.size() && i < Protocol.MAX_PROBLEMS; i++) {
      description.addProblems(
          i == Protocol.MAX_PROBLEMS - 1 && problems.size() > Protocol.MAX_PROBLEMS
              ? (problems.size() - i) + " more problems: the agent's journal lists them all"
              : cut(problems.get(i)));
    }
    CRC32 crc = new CRC32();
    crc.update(description.toByteArray());
    return description.setRevision((int) crc.getValue());
  }

  /** The longest a problem is in the description: the agent's journal has it whole. */
  public static final int MAX_PROBLEM = 1024;

  private static String cut(String problem) {
    return problem.length() <= MAX_PROBLEM ? problem : problem.substring(0, MAX_PROBLEM) + "...";
  }
}
