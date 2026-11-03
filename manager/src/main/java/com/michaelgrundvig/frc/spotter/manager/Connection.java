package com.michaelgrundvig.frc.spotter.manager;

/** How a board's agent is reached, as of the last {@link Manager#update}. */
public enum Connection {
  /** Not heard from yet, and not for as long as the missing threshold. */
  CONNECTING,
  /** Heard from within the missing threshold. */
  CONNECTED,
  /** Nothing from it for longer than the missing threshold. */
  MISSING,
  /**
   * It answers with another major version of the protocol, or with none: its values aren't used,
   * and its actions aren't offered.
   */
  OTHER_PROTOCOL
}
