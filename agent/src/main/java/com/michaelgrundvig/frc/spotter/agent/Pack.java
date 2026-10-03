package com.michaelgrundvig.frc.spotter.agent;

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
record Pack(
    String name,
    String version,
    String folder,
    boolean pushed,
    List<Collector> collectors,
    List<Log> logs,
    List<Action> actions) {
  /** The file in a pack's folder that declares it. */
  static final String FILE = "pack.yaml";

  /** The name the built-in actions go by, which no pack may take. */
  static final String CORE = "core";

  Pack {
    collectors = List.copyOf(collectors);
    logs = List.copyOf(logs);
    actions = List.copyOf(actions);
  }

  /** The pack as the description lists it. */
  Spotter.Pack description() {
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
  record Collector(
      String id, Command command, Duration every, Duration timeout, List<Field> fields) {
    /** A collector's timeout unless its pack says. */
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    Collector {
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
  record Log(String id, String label, Command.Run run, LogMap map) {}

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
  record LogMap(
      String time, String timeUnit, String level, String source, String message, String cursor) {
    /** An entry's parts under their own names. */
    static final LogMap DEFAULT = new LogMap("time", "", "level", "source", "message", "cursor");
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
  record Action(
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
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** The longest an action's timeout may be. */
    static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    Action {
      response = List.copyOf(response);
    }
  }
}
