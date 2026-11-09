package com.michaelgrundvig.frc.spotter.manager;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;

/**
 * What a test needs of the computer it runs on (openssl, a user that isn't root): without it, the
 * test is skipped, except in CI ({@code CI=true}), where it fails, so a check that never runs can't
 * pass unseen.
 */
final class Needs {
  private static final boolean CI = "true".equalsIgnoreCase(System.getenv("CI"));

  private Needs() {}

  /** Skips the test unless this holds; in CI, fails it. */
  static void that(boolean holds, String what) {
    if (CI) {
      assertThat(holds).as("CI must have " + what).isTrue();
      return;
    }
    Assumptions.assumeTrue(holds, "skipped without " + what);
  }
}
