package com.michaelgrundvig.frc.spotter.agent.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** When a stream sends, by the time alone: every case at exact times, without waiting. */
class PacingTest {
  static final long MS = 1_000_000L;
  static final long T0 = 1_234_560_000_000L;

  /** A heartbeat of 250 ms, every value again each 10 s, connected at T0. */
  private static Pacing pacing() {
    return new Pacing(250 * MS, Stream.COMPLETE_NANOS, T0);
  }

  @Test
  void aHeartbeatIsDueOnlyAfterTheIntervalWithNothingSent() {
    Pacing pacing = pacing();
    assertThat(pacing.heartbeatDue(T0)).isFalse();
    assertThat(pacing.heartbeatDue(T0 + 249 * MS)).isFalse();
    assertThat(pacing.heartbeatDue(T0 + 250 * MS)).isTrue();
    assertThat(pacing.wake()).isEqualTo(T0 + 250 * MS);
    // Anything sent puts it off: a busy stream sends no heartbeats.
    pacing.sent(T0 + 200 * MS);
    assertThat(pacing.heartbeatDue(T0 + 250 * MS)).isFalse();
    assertThat(pacing.heartbeatDue(T0 + 450 * MS)).isTrue();
    assertThat(pacing.wake()).isEqualTo(T0 + 450 * MS);
  }

  @Test
  void aChangeWaits20MsForOthersCountedFromTheFirst() {
    Pacing pacing = pacing();
    pacing.changed(T0 + 100 * MS);
    pacing.changed(T0 + 115 * MS);
    assertThat(pacing.changesDue(T0 + 119 * MS)).isFalse();
    assertThat(pacing.wake()).isEqualTo(T0 + 120 * MS);
    assertThat(pacing.changesDue(T0 + 120 * MS)).isTrue();
    pacing.changesTaken();
    assertThat(pacing.changesDue(T0 + 500 * MS)).isFalse();
    assertThat(pacing.wake()).isEqualTo(T0 + 250 * MS);
    // The next change starts its own wait.
    pacing.changed(T0 + 600 * MS);
    assertThat(pacing.changesDue(T0 + 619 * MS)).isFalse();
    assertThat(pacing.changesDue(T0 + 620 * MS)).isTrue();
  }

  @Test
  void everyValueIsDueAgainEach10SecondsFromTheLastTime() {
    Pacing pacing = pacing();
    assertThat(pacing.completeDue(T0 + Stream.COMPLETE_NANOS - 1)).isFalse();
    assertThat(pacing.completeDue(T0 + Stream.COMPLETE_NANOS)).isTrue();
    // A new description sent every value early: the 10 s start again from then.
    pacing.completeSent(T0 + 3_000 * MS);
    assertThat(pacing.completeDue(T0 + Stream.COMPLETE_NANOS)).isFalse();
    assertThat(pacing.completeDue(T0 + 13_000 * MS)).isTrue();
  }

  @Test
  void itWakesForTheSoonestOfThem() {
    Pacing pacing = new Pacing(5_000 * MS, 1_000 * MS, T0);
    assertThat(pacing.wake()).isEqualTo(T0 + 1_000 * MS);
    pacing.changed(T0 + 10 * MS);
    assertThat(pacing.wake()).isEqualTo(T0 + 30 * MS);
  }
}
