package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** What the manager's tests share: a robot whose clock the test moves, and waiting for a board. */
final class Boards {
  private Boards() {}

  /** A disabled robot off the field, its clock the test's. */
  static Robot robot(AtomicLong nanos) {
    return new Robot(() -> false, () -> false, nanos::get);
  }

  /** A disabled robot off the field, on the computer's monotonic clock, as the agent's is. */
  static Robot robot() {
    return new Robot(() -> false, () -> false, System::nanoTime);
  }

  /** Updates the manager every 10 ms until the condition holds, for up to 10 s. */
  static void await(Manager manager, String what, BooleanSupplier condition)
      throws InterruptedException {
    long until = System.nanoTime() + 10_000_000_000L;
    while (true) {
      manager.update();
      if (condition.getAsBoolean()) {
        return;
      }
      if (System.nanoTime() > until) {
        throw new AssertionError(
            "not within 10 s: "
                + what
                + "; boards: "
                + manager.boards()
                + ", alerts: "
                + manager.alerts());
      }
      Thread.sleep(10);
    }
  }

  /** A board's value by id, which it must have. */
  static Value value(Board board, String id) {
    return Objects.requireNonNull(board.value(id), () -> board.name() + " has no value " + id);
  }

  static Spotter.FieldValue number(double value) {
    return Spotter.FieldValue.newInstance().setNumber(value);
  }

  static Spotter.FieldValue text(String value) {
    return Spotter.FieldValue.newInstance().setText(value);
  }

  static Spotter.FieldValue unavailable(String why) {
    return Spotter.FieldValue.newInstance().setUnavailable(why);
  }

  static Spotter.FieldValue status(Spotter.Level level, String message) {
    return Spotter.FieldValue.newInstance()
        .setStatus(Spotter.Status.newInstance().setLevel(level).setMessage(message));
  }
}
