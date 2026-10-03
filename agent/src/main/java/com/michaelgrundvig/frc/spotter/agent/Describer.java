package com.michaelgrundvig.frc.spotter.agent;

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
final class Describer {
  /** The built-in actions' pack name: their ids are {@code core.<id>}. */
  static final String CORE = Pack.CORE;

  /**
   * The two built-in actions: the manager's power-down depends on them existing under known names.
   * Each runs systemctl, which a polkit rule of the package's lets the agent's user do.
   */
  static final List<Pack.Action> BUILT_IN =
      List.of(
          builtIn(
              "power-off",
              "Power off",
              "Stops every service in order, then powers the board off",
              "Power this computer off?",
              "poweroff"),
          builtIn("reboot", "Reboot", "Restarts the board", "Restart this computer?", "reboot"));

  private final String version;
  private final Configuration configuration;
  private final Packs.Loaded packs;

  /**
   * @param version the agent's version
   * @param configuration the board's settings and packs, and what it ignored
   */
  Describer(String version, Configuration configuration) {
    this.version = version;
    this.configuration = configuration;
    this.packs = configuration.packs();
  }

  private static Pack.Action builtIn(
      String id, String label, String description, String confirm, String verb) {
    return new Pack.Action(
        id,
        label,
        description,
        new Command.Run(List.of("systemctl", verb)),
        Spotter.Input.INPUT_NONE,
        confirm,
        Pack.Action.TIMEOUT,
        false,
        PackReader.builtInResponse(true));
  }

  /** The description, with this identity, and its revision. */
  Spotter.Description describe(Spotter.Identity identity) {
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
        description.addValues(field.declaration(slot.pack().name() + "." + field.name()));
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
    for (Pack.Action action : BUILT_IN) {
      description.addActions(declaration(CORE, action));
    }
    for (Pack pack : packs.packs()) {
      for (Pack.Action action : pack.actions()) {
        description.addActions(declaration(pack.name(), action));
      }
    }
    for (String problem : configuration.problems()) {
      description.addProblems(problem);
    }
    CRC32 crc = new CRC32();
    crc.update(description.toByteArray());
    return description.setRevision((int) crc.getValue());
  }

  private static Spotter.ActionDeclaration declaration(String pack, Pack.Action action) {
    Spotter.ActionDeclaration declaration =
        Spotter.ActionDeclaration.newInstance()
            .setId(pack + "." + action.id())
            .setLabel(action.label())
            .setDescription(action.description())
            .setInput(action.input())
            .setConfirm(action.confirm())
            .setTimeoutSeconds((int) Math.max(1, Math.round(action.timeout().toMillis() / 1000.0)))
            .setWhileEnabled(action.whileEnabled());
    for (Field field : action.response()) {
      declaration.addResponse(field.declaration(field.name()));
    }
    return declaration;
  }
}
