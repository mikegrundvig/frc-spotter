package com.michaelgrundvig.frc.spotter.agent.packs;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.time.Duration;
import java.util.List;

/**
 * A pack: a folder, {@code <name>/pack.yaml} beside whatever its commands run, that declares
 * collectors, logs and actions. Spotter never installs one: it's in place or it isn't, installed
 * with the board or pushed by the robot.
 *
 * @param name its name, its folder's
 * @param version its version, as it writes it; empty when it gives none
 * @param folder its folder on the coprocessor, absolute
 * @param pushed whether the robot pushed it, rather than the board's installer
 * @param collectors its collectors, in its order
 * @param logs its logs, in its order
 * @param actions its actions, in its order
 */
public record Pack(
    String name,
    String version,
    String folder,
    boolean pushed,
    List<Collector> collectors,
    List<Log> logs,
    List<Action> actions) {
  /** The file in a pack's folder that declares it. */
  public static final String FILE = "pack.yaml";

  /** The name the built-in actions go by, which no pack may take. */
  public static final String CORE = "core";

  public Pack {
    collectors = List.copyOf(collectors);
    logs = List.copyOf(logs);
    actions = List.copyOf(actions);
  }

  /** The pack as the description lists it. */
  public Spotter.Pack description() {
    return Spotter.Pack.newInstance()
        .setName(name)
        .setVersion(version)
        .setFolder(folder)
        .setPushed(pushed);
  }

  /**
   * A collector: a command run on its own schedule, whose output fills its fields.
   *
   * @param id its name in the pack
   * @param command what it runs or reads
   * @param every how often
   * @param timeout how long a run may take before its fields are unavailable
   * @param fields the fields it fills, in its order
   */
  public record Collector(
      String id, Command command, Duration every, Duration timeout, List<Field> fields) {
    /** A collector's timeout unless its pack says. */
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    public Collector {
      fields = List.copyOf(fields);
    }
  }

  /**
   * A log: a command that prints a page of entries as JSON lines, asked what for by the
   * environment.
   *
   * @param id its name in the pack: its id is {@code pack.log}
   * @param label what a display calls it
   * @param run what it runs
   * @param map which of each line's keys are an entry's parts
   */
  public record Log(String id, String label, Command.Run run, LogMap map) {}

  /**
   * Which JSON key of a log's line holds each part of an entry.
   *
   * @param time the key of its time
   * @param timeUnit the unit of a numeric time: {@code ns}, {@code us}, {@code ms} or {@code s};
   *     empty when the pack gives none
   * @param level the key of its level
   * @param source the key of who wrote it
   * @param message the key of its message
   * @param cursor the key of its cursor
   */
  public record LogMap(
      String time, String timeUnit, String level, String source, String message, String cursor) {
    /** An entry's parts under their own names. */
    public static final LogMap DEFAULT =
        new LogMap("time", "", "level", "source", "message", "cursor");
  }

  /**
   * An action: a command run on request, optionally with input, whose output fills a response.
   *
   * @param id its name in the pack: its id is {@code pack.action}
   * @param label what a display calls it
   * @param description what it does
   * @param command what it runs
   * @param input what it takes
   * @param confirm the question a display asks first; empty for none
   * @param timeout how long it may run
   * @param whileEnabled whether it may run while the robot is enabled
   * @param response the fields of its response: the built-in ones for its kind, then the pack's
   */
  public record Action(
      String id,
      String label,
      String description,
      Command command,
      Spotter.Input input,
      String confirm,
      Duration timeout,
      boolean whileEnabled,
      List<Field> response) {
    /** An action's timeout unless its pack says. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** The longest an action's timeout may be. */
    static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    public Action {
      response = List.copyOf(response);
    }

    /** As a description declares it, under its pack's name: {@code pack.action}. */
    public Spotter.ActionDeclaration declaration(String pack) {
      Spotter.ActionDeclaration declaration =
          Spotter.ActionDeclaration.newInstance()
              .setId(pack + "." + id)
              .setLabel(label)
              .setDescription(description)
              .setInput(input)
              .setConfirm(confirm)
              .setTimeoutSeconds((int) Math.max(1, Math.round(timeout.toMillis() / 1000.0)))
              .setWhileEnabled(whileEnabled);
      for (Field field : response) {
        declaration.addResponse(field.declaration(field.name()));
      }
      return declaration;
    }
  }

  /**
   * The two built-in actions, {@code core.power-off} and {@code core.reboot}: robot code can power
   * off or restart any board, whatever packs it has. Each runs systemctl, which a polkit rule of
   * the package's lets the agent's user do.
   */
  public static final List<Action> BUILT_IN =
      List.of(
          builtIn(
              "power-off",
              "Power off",
              "Stops every service in order, then powers the board off",
              "Power this computer off?",
              "poweroff"),
          builtIn("reboot", "Reboot", "Restarts the board", "Restart this computer?", "reboot"));

  private static Action builtIn(
      String id, String label, String description, String confirm, String verb) {
    return new Action(
        id,
        label,
        description,
        new Command.Run(List.of("systemctl", verb)),
        Spotter.Input.INPUT_NONE,
        confirm,
        Action.TIMEOUT,
        false,
        PackReader.builtInResponse(Command.Kind.RUN));
  }

  /** How many values these packs' collectors fill. */
  public static int values(List<Pack> packs) {
    int count = 0;
    for (Pack pack : packs) {
      for (Collector collector : pack.collectors()) {
        count += collector.fields().size();
      }
    }
    return count;
  }

  /** How many bytes its declarations take in a description: its entry, values, logs, actions. */
  long declaredSize() {
    Spotter.Description part = Spotter.Description.newInstance().addPacks(description());
    for (Collector collector : collectors()) {
      for (Field field : collector.fields()) {
        part.addValues(field.declaration(name() + "." + collector.id() + "." + field.name()));
      }
    }
    for (Log log : logs()) {
      part.addLogs(
          Spotter.LogDeclaration.newInstance()
              .setId(name() + "." + log.id())
              .setLabel(log.label()));
    }
    for (Action action : actions()) {
      part.addActions(action.declaration(name()));
    }
    return part.getSerializedSize();
  }
}
