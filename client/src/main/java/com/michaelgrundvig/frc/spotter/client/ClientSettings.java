package com.michaelgrundvig.frc.spotter.client;

/**
 * How a robot asks its coprocessors' agents, and when one counts as missing.
 *
 * @param pollPeriodSeconds how often each agent is asked how it is, each on its own thread
 * @param connectTimeoutSeconds how long a poll waits for a connection
 * @param answerTimeoutSeconds how long a poll waits for the whole answer, once connected
 * @param staleAfterSeconds an answer older than this, or none at all, is a coprocessor missing
 * @param requestTimeoutSeconds how long a request off the robot loop (a journal page, a probe run
 *     when asked) may take, once connected
 * @param powerDownTimeoutSeconds how long to keep asking a coprocessor that hasn't taken a request
 *     to power down
 */
public record ClientSettings(
    double pollPeriodSeconds,
    double connectTimeoutSeconds,
    double answerTimeoutSeconds,
    double staleAfterSeconds,
    double requestTimeoutSeconds,
    double powerDownTimeoutSeconds) {
  /**
   * Once a second, within 250 ms to connect and 500 ms to answer, so a poll that times out never
   * delays the next; missing after three polls without an answer; other requests within 10 s; asked
   * to power down for 30 s.
   */
  public static final ClientSettings DEFAULTS = new ClientSettings(1, 0.25, 0.5, 3, 10, 30);

  public ClientSettings {
    for (double value :
        new double[] {
          pollPeriodSeconds,
          connectTimeoutSeconds,
          answerTimeoutSeconds,
          staleAfterSeconds,
          requestTimeoutSeconds,
          powerDownTimeoutSeconds
        }) {
      if (!(value > 0) || value > 3600) {
        throw new IllegalArgumentException("every setting is a positive time, at most an hour");
      }
    }
    if (connectTimeoutSeconds + answerTimeoutSeconds > pollPeriodSeconds) {
      throw new IllegalArgumentException(
          "connectTimeoutSeconds and answerTimeoutSeconds together must fit in pollPeriodSeconds,"
              + " so a poll that times out never delays the next");
    }
    if (staleAfterSeconds <= pollPeriodSeconds) {
      throw new IllegalArgumentException(
          "staleAfterSeconds must be longer than pollPeriodSeconds, or every coprocessor reads"
              + " missing between its answers");
    }
  }
}
