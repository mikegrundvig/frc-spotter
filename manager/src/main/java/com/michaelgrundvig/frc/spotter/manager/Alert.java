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
 *   <li>one, a warning, per board whose description lists problems (a pack it ignored, a program
 *       it won't run): how many, and the first;
 *   <li>and robot code's own, warnings: no key to sign with while a board requires signatures,
 *       packs that can't be read, and each limit override that matches nothing.
 * </ul>
 *
 * @param level {@link Level#WARNING} or {@link Level#FAILING}
 * @param board the board it's about: its hostname once it's described itself, else its address;
 *     empty for one about robot code's own setup
 * @param text what to show, the board's name first: {@code "vision-front: CPU temperature above 80
 *     °C"}
 */
public record Alert(Level level, String board, String text) {}
