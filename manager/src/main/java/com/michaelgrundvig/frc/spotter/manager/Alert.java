package com.michaelgrundvig.frc.spotter.manager;

/**
 * An alert, as data: robot code maps each onto its own (WPILib 2027's {@code Alert}: {@code
 * Level.HIGH} for failing, {@code Level.MEDIUM} for warning). The manager keeps the current set
 * ({@link Manager#alerts}):
 *
 * <ul>
 *   <li>one per value at warning or failing, in its pack's words;
 *   <li>one, failing, per missing board, and per board on another major version of the protocol;
 *   <li>one, a warning, per board whose packs differ from the robot's and won't be pushed now (it
 *       refuses pushes, the robot is on the field, a push failed);
 *   <li>one, a warning, per board whose description lists problems (a pack it ignored, a program it
 *       won't run): how many, and the first;
 *   <li>and robot code's own, warnings: no key to sign with while a board requires signatures,
 *       packs that can't be read, and each limit override that matches nothing.
 * </ul>
 *
 * <p>Each has a key, what it's about, which stays the same while it stands, though its text and its
 * level may change as it does: a mirror keeps one of its own per key.
 *
 * <ul>
 *   <li>a board's, by its address as robot code gave it: {@code vision-front/missing}, {@code
 *       /protocol}, {@code /described} (more than the manager takes), {@code /packs}, {@code
 *       /problems}, {@code /value/<id>} for each value's, and {@code /values/more};
 *   <li>robot code's setup: {@code setup/key}, {@code setup/packs}, {@code
 *       setup/packs/<address>/no-folder}, {@code setup/packs/<address>/too-large}, {@code
 *       setup/packs/<address>/pack/<name>}, and {@code setup/limits/<id>}.
 * </ul>
 *
 * @param key what it's about, the same while it stands
 * @param level {@link Level#WARNING} or {@link Level#FAILING}
 * @param board the board it's about: its hostname once it's described itself, else its address;
 *     empty for one about robot code's own setup
 * @param text what to show, the board's name first: {@code "vision-front: CPU temperature above 80
 *     °C"}
 */
public record Alert(String key, Level level, String board, String text) {}
