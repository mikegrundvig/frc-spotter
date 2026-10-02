package com.michaelgrundvig.frc.spotter.probes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything one computer's agent runs beyond its own report: the packs it read, their probes, and
 * the journal units they serve besides the kernel's and its own. Bounded, so packs can't make a
 * health answer or the journal's reach grow without end.
 *
 * @param packs the packs' names, in the order they were read
 * @param probes every probe, in order; no two with one id
 * @param journalUnits the systemd units whose journal entries the agent serves besides the kernel's
 *     and its own
 */
public record ProbeSet(List<String> packs, List<Probe> probes, List<String> journalUnits) {
  /** The most probes a computer may run: each costs a line of every health answer. */
  public static final int MAX_PROBES = 64;

  /** The most units served besides the agent's and the kernel's. */
  public static final int MAX_UNITS = 16;

  /** A systemd unit's name, as a pack's {@code journalUnits} gives it. */
  static final Pattern UNIT =
      Pattern.compile("[A-Za-z0-9:_.\\\\@-]{1,200}\\.(service|socket|timer|mount|scope)");

  /** No packs: the agent's own report alone. */
  public static final ProbeSet EMPTY = new ProbeSet(List.of(), List.of(), List.of());

  public ProbeSet {
    packs = List.copyOf(packs);
    probes = List.copyOf(probes);
    journalUnits = List.copyOf(journalUnits);
    if (probes.size() > MAX_PROBES) {
      throw new IllegalArgumentException(
          probes.size() + " probes, more than the " + MAX_PROBES + " a computer may run");
    }
    if (journalUnits.size() > MAX_UNITS) {
      throw new IllegalArgumentException(
          journalUnits.size() + " journal units, more than " + MAX_UNITS);
    }
    unique(packs, "pack");
    unique(probes.stream().map(Probe::id).toList(), "probe");
    unique(journalUnits, "journal unit");
    for (String unit : journalUnits) {
      if (!UNIT.matcher(unit).matches()) {
        throw new IllegalArgumentException("journal unit \"" + unit + "\" isn't a unit's name");
      }
    }
  }

  private static void unique(List<String> names, String what) {
    Set<String> seen = new HashSet<>();
    for (String name : names) {
      if (!seen.add(name)) {
        throw new IllegalArgumentException("two " + what + "s are named " + name);
      }
    }
  }

  /**
   * This set with a pack added: its probes after the others, its journal units with the others.
   *
   * @throws IllegalArgumentException when the pack's name or one of its probes' ids is taken, or
   *     the set would run past its bounds
   */
  public ProbeSet with(Pack pack) {
    if (packs.contains(pack.name())) {
      throw new IllegalArgumentException("a pack named " + pack.name() + " was read already");
    }
    for (Probe probe : pack.probes()) {
      if (probe(probe.id()).isPresent()) {
        throw new IllegalArgumentException(
            "probe "
                + probe.id()
                + " is defined already, by pack "
                + probe(probe.id()).get().pack());
      }
    }
    List<String> names = new ArrayList<>(packs);
    names.add(pack.name());
    List<Probe> all = new ArrayList<>(probes);
    all.addAll(pack.probes());
    List<String> units = new ArrayList<>(journalUnits);
    for (String unit : pack.journalUnits()) {
      if (!units.contains(unit)) {
        units.add(unit);
      }
    }
    return new ProbeSet(names, all, units);
  }

  /** A probe by its id. */
  public Optional<Probe> probe(String id) {
    return probes.stream().filter(probe -> probe.id().equals(id)).findFirst();
  }
}
