package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The packs a board has, read once as the agent starts (a change takes a restart): those installed
 * with it, in {@link #INSTALLED}, and those the robot pushed, in {@link #PUSHED}. A pushed pack
 * wins over an installed one of the same name: the robot's repository is the source of truth.
 *
 * <p>An installed pack is trusted by its file, as {@code sshd} trusts its configuration: one whose
 * {@code pack.yaml} isn't root's, or that its group or anyone else may write, is ignored. So is a
 * collector, log or action whose program, named by path, anyone but root could change: otherwise
 * "root owns the YAML" would protect nothing. A pushed pack is trusted because only the robot
 * controller may push. Everything ignored is in the description's problems, with why.
 */
final class Packs {
  /** Where the packs installed with the board are, each a folder. */
  static final String INSTALLED = "/etc/frc-spotter/packs";

  /** Where the packs the robot pushed are, each a folder. */
  static final String PUSHED = "/var/lib/frc-spotter/packs";

  /** The user ID an installed pack's files must be owned by: root. */
  static final int ROOT = 0;

  private Packs() {}

  /**
   * The packs read, and what was ignored.
   *
   * @param packs every pack that loaded, by name
   * @param problems what was ignored, and why
   * @param pushedHash the hash of {@link #PUSHED} as it is on disk; empty when there's nothing
   *     there, or the board refuses pushes
   */
  record Loaded(List<Pack> packs, List<String> problems, String pushedHash) {
    static final Loaded NONE = new Loaded(List.of(), List.of(), "");

    Loaded {
      packs = List.copyOf(packs);
      problems = List.copyOf(problems);
    }
  }

  /** Reads the board's packs: its installed ones, and its pushed ones when it accepts pushes. */
  static Loaded load(Host host, boolean acceptPushes) {
    List<String> problems = new ArrayList<>();
    Map<String, Optional<Pack>> installed = folder(host, INSTALLED, false, problems);
    Map<String, Optional<Pack>> pushed = Map.of();
    String hash = "";
    if (acceptPushes) {
      pushed = folder(host, PUSHED, true, problems);
      try {
        hash = PackHash.of(host.path(PUSHED));
      } catch (IOException e) {
        problems.add(PUSHED + ": can't be read to hash it: " + e.getMessage());
      }
    } else {
      try {
        if (!host.list(PUSHED).isEmpty()) {
          problems.add(PUSHED + ": ignored, as agent.json refuses pushes");
        }
      } catch (IOException e) {
        // Ignored either way.
      }
    }
    TreeMap<String, Pack> all = new TreeMap<>();
    installed.forEach((name, pack) -> pack.ifPresent(loaded -> all.put(name, loaded)));
    pushed.forEach(
        (name, pack) -> {
          if (installed.containsKey(name)) {
            all.remove(name);
            problems.add(
                INSTALLED + "/" + name + ": ignored, as the robot pushed a pack of that name");
          }
          pack.ifPresent(loaded -> all.put(name, loaded));
        });
    return new Loaded(new ArrayList<>(all.values()), problems, hash);
  }

  /**
   * Every pack folder in a folder, by name: each one loaded, or empty when it was ignored (and
   * {@code problems} says why). Anything there that isn't a pack's folder is ignored too.
   */
  private static Map<String, Optional<Pack>> folder(
      Host host, String folder, boolean pushed, List<String> problems) {
    Map<String, Optional<Pack>> packs = new TreeMap<>();
    List<String> names;
    try {
      names = host.list(folder);
    } catch (IOException e) {
      problems.add(folder + ": can't be read: " + e.getMessage());
      return packs;
    }
    for (String name : names) {
      String path = folder + "/" + name;
      if (!host.isFolder(path)) {
        problems.add(path + ": not a pack (a pack is a folder, <name>/pack.yaml), ignored");
        continue;
      }
      packs.put(name, pack(host, path, pushed, problems));
    }
    return packs;
  }

  private static Optional<Pack> pack(
      Host host, String folder, boolean pushed, List<String> problems) {
    String file = folder + "/" + Pack.FILE;
    try {
      if (!host.exists(file)) {
        problems.add(folder + ": has no " + Pack.FILE + ", ignored");
        return Optional.empty();
      }
      if (!pushed) {
        String untrusted = untrusted(host.owner(file));
        if (!untrusted.isEmpty()) {
          problems.add(file + ": " + untrusted + ", ignored");
          return Optional.empty();
        }
      }
      Optional<String> text = host.read(file);
      if (text.isEmpty()) {
        problems.add(file + ": gone as it was read, ignored");
        return Optional.empty();
      }
      Pack pack = PackReader.read(text.get(), folder, pushed);
      return Optional.of(pushed ? pack : trusted(host, pack, problems));
    } catch (PackException e) {
      for (String problem : e.problems()) {
        problems.add(problem + " (the pack is ignored)");
      }
    } catch (IOException e) {
      problems.add(file + ": can't be read: " + e.getMessage() + ", ignored");
    }
    return Optional.empty();
  }

  /**
   * An installed pack with only the collectors, logs and actions whose programs root alone may
   * change: any program it names by path, as it is now. A program that isn't there is left to fail
   * when it's run.
   */
  private static Pack trusted(Host host, Pack pack, List<String> problems) throws IOException {
    List<Pack.Collector> collectors = new ArrayList<>();
    for (Pack.Collector collector : pack.collectors()) {
      if (runs(host, pack, collector.command(), "collector " + collector.id(), problems)) {
        collectors.add(collector);
      }
    }
    List<Pack.Log> logs = new ArrayList<>();
    for (Pack.Log log : pack.logs()) {
      if (runs(host, pack, log.run(), "log " + log.id(), problems)) {
        logs.add(log);
      }
    }
    List<Pack.Action> actions = new ArrayList<>();
    for (Pack.Action action : pack.actions()) {
      if (runs(host, pack, action.command(), "action " + action.id(), problems)) {
        actions.add(action);
      }
    }
    return new Pack(pack.name(), pack.version(), pack.folder(), false, collectors, logs, actions);
  }

  private static boolean runs(
      Host host, Pack pack, Command command, String what, List<String> problems)
      throws IOException {
    if (!(command instanceof Command.Run) || !((Command.Run) command).byPath()) {
      return true;
    }
    String program = ((Command.Run) command).programPath(pack.folder());
    if (!host.exists(program)) {
      return true;
    }
    String untrusted = untrusted(host.owner(program));
    if (untrusted.isEmpty()) {
      return true;
    }
    problems.add(program + ": " + untrusted + ", so " + pack.name() + "'s " + what + " isn't run");
    return false;
  }

  /** Why a file isn't to be trusted; empty when root alone may change it. */
  private static String untrusted(Host.Owner owner) {
    if (owner.uid() != ROOT) {
      return "isn't root's (its owner is user " + owner.uid() + ")";
    }
    if (owner.writableByOthers()) {
      return "may be written by its group or others (mode "
          + Integer.toOctalString(owner.mode())
          + ")";
    }
    return "";
  }
}
