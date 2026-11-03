package com.michaelgrundvig.frc.spotter.manager;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A board, as robot code names it: its agent's address, and the team's packs it's to have. Robot
 * code gives the manager one for each board ({@link Manager#Manager}), and the manager makes the
 * {@link Board} the robot loop reads. It holds no state, so it's safe as a constant, and any number
 * of managers can take it.
 *
 * <pre>{@code
 * static final BoardDefinition VISION_FRONT =
 *     new BoardDefinition("vision-front", "debian", "detector");
 * }</pre>
 *
 * <p>The manager pushes a board exactly the packs it names, from the team's packs ({@link
 * Settings#withPacks}), whatever other boards name. One that names none is pushed none, so packs
 * pushed to it before are taken away; its installed packs are its own either way.
 *
 * @param address its agent's address: {@code vision-front}, {@code 10.12.34.11}, or {@code
 *     vision-front:5808}; on port 5808 unless it says
 * @param packs the names of the team's packs it's to have, each a folder in the team's packs
 */
public record BoardDefinition(String address, List<String> packs) {
  /**
   * @throws IllegalArgumentException if a pack's name is empty or more than a folder's name, or
   *     given twice
   */
  public BoardDefinition {
    address = address.strip();
    Set<String> named = new LinkedHashSet<>();
    for (String pack : packs) {
      if (pack.isEmpty() || pack.contains("/") || pack.contains("\\") || pack.startsWith(".")) {
        throw new IllegalArgumentException(
            "a pack's name is its folder's, such as debian: \"" + pack + "\" for " + address);
      }
      if (!named.add(pack)) {
        throw new IllegalArgumentException("the pack " + pack + " is named twice for " + address);
      }
    }
    packs = List.copyOf(named);
  }

  /** A board at this address, to have these of the team's packs; none when none are given. */
  public BoardDefinition(String address, String... packs) {
    this(address, List.of(packs));
  }
}
