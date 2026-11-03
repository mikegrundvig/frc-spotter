/**
 * Spotter's manager, as robot code uses it: make a {@link Manager} of the boards ({@link
 * BoardDefinition}), call {@link Manager#update} each loop, and read each {@link Board}, its {@link
 * Value}s and the {@link Alert}s; run actions ({@link Run}) and page logs ({@link LogQuery}).
 * Everything robot code uses is in this package; the packages under it are the manager's machinery:
 * {@code stream} (following each board), {@code limits} (judging values), {@code requests} (asking
 * a board), {@code actions} (following runs) and {@code packs} (pushing the team's packs).
 */
package com.michaelgrundvig.frc.spotter.manager;
