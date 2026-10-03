package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static com.michaelgrundvig.frc.spotter.manager.Boards.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A board that sends what no agent does, by fault or on purpose, can't make the robot allocate past
 * what it sent, flood the loop with alerts, or take its own board away for good: an event past the
 * most taken, or one that can't be read safely, drops the connection; a description past the most
 * taken isn't used, and says so; a board's value alerts stop at a count; and whatever its thread
 * meets, it connects again.
 */
class HostileBoardTest {
  final AtomicLong clock = new AtomicLong(5_000_000_000L);

  /** A varint's bytes. */
  private static void varint(ByteArrayOutputStream out, long value) {
    while ((value & ~0x7fL) != 0) {
      out.write((int) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
    out.write((int) value);
  }

  /** Events as a stream has them, each its length first. */
  private static byte[] stream(Spotter.Event... events) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Spotter.Event event : events) {
      ZeroAllocationTest.write(out, event);
    }
    return out.toByteArray();
  }

  private Link link(Board board) {
    return new Link(board, Boards.robot(clock), Settings.DEFAULTS, Recorder.NONE);
  }

  @Test
  void anEventPastTheMostTakenDropsTheConnectionUnread() {
    Board board = new Board("10.12.34.11", Settings.DEFAULTS);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    varint(out, Protocol.MAX_EVENT + 1L);
    assertThatThrownBy(() -> link(board).read(new ByteArrayInputStream(out.toByteArray())))
        .isInstanceOf(IOException.class)
        .hasMessage("it sent an event of 1048577 bytes, past the 1024 KiB the manager takes");
  }

  @Test
  void aStringThatSays2GiBIsRefusedWithoutAllocatingIt() {
    // Event { described: Description { problems: "2 GiB" } }, in eight bytes: QuickBuffers 1.4
    // on its own allocates the 2 GiB before it finds there's nothing there.
    byte[] event = {0x0a, 0x06, 0x42, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07};
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    varint(out, event.length);
    out.writeBytes(event);
    Board board = new Board("10.12.34.11", Settings.DEFAULTS);
    Link link = link(board);
    long before = ZeroAllocationTest.THREADS.getCurrentThreadAllocatedBytes();
    assertThatThrownBy(() -> link.read(new ByteArrayInputStream(out.toByteArray())))
        .isInstanceOf(IOException.class)
        .hasMessage(
            "it sent an event that can't be read safely: spotter.v2.Description's field 8 says"
                + " 2147483647 bytes, and 0 are left");
    long allocated = ZeroAllocationTest.THREADS.getCurrentThreadAllocatedBytes() - before;
    assertThat(allocated).as("bytes allocated refusing it").isLessThan(1 << 20);
  }

  /**
   * A description of {@code count} numbers, each warning below 10 or, past {@code warn}, failing.
   */
  private static Spotter.Description numbers(int count, int warn) {
    Spotter.Description description = Spotter.Description.newInstance().setRevision(9);
    description.getMutableIdentity().setHostname("vision-front");
    for (int i = 0; i < count; i++) {
      Spotter.Limit below = Spotter.Limit.newInstance().setBelow(10);
      Spotter.FieldDeclaration value =
          Spotter.FieldDeclaration.newInstance()
              .setId("v.c.n" + i)
              .setType(Spotter.FieldType.FIELD_TYPE_NUMBER);
      description.addValues(i < warn ? value.setWarn(below) : value.setFail(below));
    }
    return description;
  }

  /** Every value of a description of numbers, at 0. */
  private static Spotter.Values zeros(int count) {
    Spotter.Values values = Spotter.Values.newInstance().setRevision(9).setComplete(true);
    for (int i = 0; i < count; i++) {
      values.addValues(Boards.number(0).setIndex(i));
    }
    return values;
  }

  @Test
  void aDescriptionPastTheMostTakenIsntUsedAndSaysSo() {
    Board board = new Board("10.12.34.11", Settings.DEFAULTS);
    int count = Protocol.MAX_VALUES + 1;
    byte[] events =
        stream(
            Spotter.Event.newInstance().setDescribed(numbers(count, count)),
            Spotter.Event.newInstance().setValues(zeros(count)));
    assertThatThrownBy(() -> link(board).read(new ByteArrayInputStream(events)))
        .isInstanceOf(java.io.EOFException.class);
    board.update(clock.get(), 0, Settings.DEFAULTS.missing().toNanos());
    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);
    assertThat(board.values()).isEmpty();
    assertThat(board.description().getValues().length()).isZero();
    assertThat(board.alerts())
        .containsExactly(
            new Alert(
                Level.FAILING,
                "vision-front",
                "vision-front describes 2049 values (at most 2048), more than the manager takes:"
                    + " its values and actions aren't used"));
  }

  @Test
  void aBoardsValueAlertsStopAtTheMostAndSayHowManyMore() {
    Board board = new Board("10.12.34.11", Settings.DEFAULTS);
    // 25 warnings, then 15 failing: 40 alerts, past the 32.
    byte[] events =
        stream(
            Spotter.Event.newInstance().setDescribed(numbers(40, 25)),
            Spotter.Event.newInstance().setValues(zeros(40)));
    assertThatThrownBy(() -> link(board).read(new ByteArrayInputStream(events)))
        .isInstanceOf(java.io.EOFException.class);
    board.update(clock.get(), 0, Settings.DEFAULTS.missing().toNanos());
    List<Alert> alerts = board.alerts();
    assertThat(alerts).hasSize(Link.MAX_ALERTS);
    // The failing ones first, all of them; then warnings, in order; then how many more.
    assertThat(alerts.subList(0, 15)).allMatch(alert -> alert.level() == Level.FAILING);
    assertThat(alerts.get(0).text()).isEqualTo("vision-front: v.c.n25 below 10");
    assertThat(alerts.get(15).text()).isEqualTo("vision-front: v.c.n0 below 10");
    assertThat(alerts.get(Link.MAX_ALERTS - 1))
        .isEqualTo(
            new Alert(
                Level.WARNING,
                "vision-front",
                "vision-front: 9 more values at warning or failing"));
  }

  @Test
  void whateverABoardsThreadMeetsItConnectsAgain(@TempDir Path dir) throws Exception {
    AtomicBoolean thrown = new AtomicBoolean();
    Recorder failing =
        new Recorder() {
          @Override
          public void described(Board board, Spotter.Description description) {
            // An Error, once, as a stack overflow or an allocation that failed would be.
            if (thrown.compareAndSet(false, true)) {
              throw new OutOfMemoryError("a test's");
            }
          }
        };
    try (LocalAgent agent = new LocalAgent(dir, "vision-front")) {
      agent
          .pack("vision", ManagerTest.PACK)
          .script("vision", "health", ManagerTest.HEALTHY)
          .start();
      try (Manager manager =
          new Manager(
              Boards.robot(),
              List.of(agent.address()),
              Settings.DEFAULTS.withBackoff(java.time.Duration.ofMillis(250)),
              failing)) {
        manager.start();
        Board board = manager.boards().get(0);
        await(
            manager,
            "connected again after the error",
            () ->
                thrown.get()
                    && board.value("vision.health.fps") != null
                    && value(board, "vision.health.fps").available());
        assertThat(Objects.requireNonNull(manager.link(0).thread()).isAlive()).isTrue();
      }
    }
  }
}
