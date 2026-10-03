package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;

/**
 * Where what a board says can be logged, so a replay sees exactly what the robot saw: each {@code
 * Description} and {@code Values} as it's received, as QuickBuffers' own message (which WPILib's
 * protobuf logging takes as it is), under the board's name; and what changes a board: each run's
 * events, and each push. The manager writes no log itself.
 *
 * <p>Called on the manager's threads, as each happens. A message is reused once the call returns:
 * log it, or copy it, before then; and return quickly, as the board's stream waits. What a call
 * throws is ignored, so a logging fault never takes a board away.
 */
public interface Recorder {
  /** Records nothing. */
  Recorder NONE = new Recorder() {};

  /**
   * A board described itself: on every connect, and whenever its description changes.
   *
   * @param board the board, whose {@link Board#name} is now the description's hostname
   */
  default void described(Board board, Spotter.Description description) {}

  /** A board sent values: every value, or those that changed. */
  default void values(Board board, Spotter.Values values) {}

  /** A run on a board started, logged a line, or finished: robot code's, or anyone's. */
  default void run(Board board, Spotter.RunEvent event) {}

  /**
   * A board took the team's packs: it restarts with them.
   *
   * @param packs their hash
   * @param forced whether robot code forced it ({@link Manager#push}), rather than the manager
   *     pushing them automatically
   */
  default void pushed(Board board, String packs, boolean forced) {}
}
