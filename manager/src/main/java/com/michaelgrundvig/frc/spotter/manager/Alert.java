package com.michaelgrundvig.frc.spotter.manager;

/**
 * An alert, as data: robot code maps each onto its own (a WPILib {@code Alert}, {@code kError} for
 * failing and {@code kWarning} for warning). The manager keeps the current set ({@link
 * Manager#alerts}): one per value at warning or failing, in its pack's words; one per missing
 * board; one, failing, per board on another major version of the protocol.
 *
 * @param level {@link Level#WARNING} or {@link Level#FAILING}
 * @param board the board it's about: its hostname once it's described itself, else its address
 * @param text what to show, the board's name first: {@code "vision-front: CPU temperature above 80
 *     °C"}
 */
public record Alert(Level level, String board, String text) {}
