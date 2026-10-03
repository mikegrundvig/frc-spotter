package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The packs a board has, read once as the agent starts (a change takes a restart): those installed
 * with it, in {@link #INSTALLED}, and those the robot pushed, in {@link #PUSHED}. A pushed pack
 * wins over an installed one of the same name, as the design has it, so that's no problem: the
 * robot's repository is the source of truth, and the description's packs say which was pushed.
 *
 * <p>An installed pack is trusted by its file, as {@code sshd} trusts its configuration: one whose
 * {@code pack.yaml} isn't root's, or that its group or anyone else may write, is ignored. So is a
 * collector, log or action whose program anyone but root could change, when it names it by path:
 * its program (absolute, or the pack's own {@code ./name}), or an argument that's the pack's own
 * file ({@code [python3, ./check.py]}). Otherwise "root owns the YAML" would protect nothing. A
 * pushed pack is trusted because only the robot controller may push. Everything ignored is in the
 * description's problems, with why.
 *
 * <p>Bounded, so a board's description always fits a stream's event: at most {@link #MAX_PACKS}
 * packs, {@link Protocol#MAX_VALUES} values and {@link Protocol#MAX_ACTIONS} actions in all (the
 * built-in two included), and {@link #MAX_DECLARED} bytes of declarations. Packs are taken in the
 * order of their names, and one that would pass a bound is ignored whole, saying so.
 */
final class Packs {
  /** Where the packs installed with the board are, each a folder. */
  static final String INSTALLED = "/etc/frc-spotter/packs";

  /** Where the packs the robot pushed are, each a folder. */
  static final String PUSHED = "/var/lib/frc-spotter/packs";

  /** The most packs a board may have. */
  static final int MAX_PACKS = 32;

  /**
   * The most bytes a board's packs may declare (their values', logs' and actions' declarations, as
   * its description carries them): half a stream's event, the rest for its identity and problems.
   */
  static final int MAX_DECLARED = Protocol.MAX_EVENT / 2;

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
    return load(host, acceptPushes ? "" : "agent.json refuses pushes");
  }

  /**
   * Reads the board's packs: its installed ones, and its pushed ones unless it refuses them.
   *
   * @param refused why it refuses pushed packs; empty when it accepts them
   */
  static Loaded load(Host host, String refused) {
    List<String> problems = new ArrayList<>();
    Map<String, Optional<Pack>> installed = folder(host, INSTALLED, false, problems);
    Map<String, Optional<Pack>> pushed = Map.of();
    String hash = "";
    if (refused.isEmpty()) {
      pushed = folder(host, PUSHED, true, problems);
      try {
        hash = PackHash.of(host.path(PUSHED));
      } catch (IOException e) {
        problems.add(PUSHED + ": can't be read to hash it: " + e.getMessage());
      }
    } else {
      try {
        if (!host.list(PUSHED).isEmpty()) {
          problems.add(PUSHED + ": ignored, as " + refused);
        }
      } catch (IOException e) {
        // Ignored either way.
      }
    }
    TreeMap<String, Pack> all = new TreeMap<>();
    installed.forEach((name, pack) -> pack.ifPresent(loaded -> all.put(name, loaded)));
    // The documented rule, not a problem: the description's packs say which is pushed.
    pushed.forEach(
        (name, pack) -> {
          all.remove(name);
          pack.ifPresent(loaded -> all.put(name, loaded));
        });
    return new Loaded(bounded(all.values(), problems), problems, hash);
  }

  /** The packs, in order, as many as a board may have: one that would pass a bound is ignored. */
  private static List<Pack> bounded(Iterable<Pack> packs, List<String> problems) {
    List<Pack> kept = new ArrayList<>();
    int values = 0;
    int actions = Describer.BUILT_IN.size();
    long declared = 0;
    for (Pack pack : packs) {
      int its = Collectors.values(List.of(pack));
      long size = Describer.declared(pack);
      String past = "";
      if (kept.size() == MAX_PACKS) {
        past = "a board has at most " + MAX_PACKS + " packs";
      } else if (values + its > Protocol.MAX_VALUES) {
        past =
            "its "
                + its
                + " values would pass the "
                + Protocol.MAX_VALUES
                + " a board may have ("
                + values
                + " so far)";
      } else if (actions + pack.actions().size() > Protocol.MAX_ACTIONS) {
        past =
            "its "
                + pack.actions().size()
                + " actions would pass the "
                + Protocol.MAX_ACTIONS
                + " a board may have ("
                + actions
                + " so far)";
      } else if (declared + size > MAX_DECLARED) {
        past =
            "its declarations ("
                + size / 1024
                + " KiB) would pass the "
                + MAX_DECLARED / 1024
                + " KiB a board's description may hold";
      }
      if (!past.isEmpty()) {
        problems.add(pack.folder() + ": ignored, as " + past);
        continue;
      }
      kept.add(pack);
      values += its;
      actions += pack.actions().size();
      declared += size;
    }
    return kept;
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
    } catch (Throwable e) {
      // Whatever reading one pack meets, a stack overflow included, the others load, and the agent
      // serves: a pushed pack can't keep a board from taking the next push.
      problems.add(file + ": couldn't be read (" + e + "), ignored");
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
    if (!(command instanceof Command.Run)) {
      return true;
    }
    for (String program : ((Command.Run) command).programsByPath(pack.folder())) {
      if (!host.exists(program)) {
        continue;
      }
      String untrusted = untrusted(host.owner(program));
      if (!untrusted.isEmpty()) {
        problems.add(
            program + ": " + untrusted + ", so " + pack.name() + "'s " + what + " isn't run");
        return false;
      }
    }
    return true;
  }

  /** Why a file isn't to be trusted; empty when root alone may change it. */
  private static String untrusted(Host.Owner owner) {
    if (!owner.root()) {
      return "isn't root's (its owner is " + owner.user() + ")";
    }
    if (owner.writableByOthers()) {
      return "may be written by its group or others (mode "
          + Integer.toOctalString(owner.mode())
          + ")";
    }
    return "";
  }
}
