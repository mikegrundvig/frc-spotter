package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static com.michaelgrundvig.frc.spotter.manager.Boards.number;
import static com.michaelgrundvig.frc.spotter.manager.Boards.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stream is the robot's busiest input, and garbage made there is garbage the 20 ms loop pays
 * for. In steady state, decoding the stream into the snapshot allocates nothing, and nor does the
 * loop's update; text allocates only when it changes. Measured by the JVM's own count of the bytes
 * each thread allocates, over many messages.
 */
class ZeroAllocationTest {
  static final com.sun.management.ThreadMXBean THREADS =
      (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

  static final int REVISION = 3;

  final AtomicLong clock = new AtomicLong(5_000_000_000L);

  @org.junit.jupiter.api.BeforeAll
  static void countingIsOn() {
    assertThat(THREADS.isThreadAllocatedMemorySupported()).isTrue();
    assertThat(THREADS.isThreadAllocatedMemoryEnabled()).isTrue();
    // Whatever the count's own first calls make (loading, initialising) is made here, uncounted.
    for (int i = 0; i < 1000; i++) {
      THREADS.getCurrentThreadAllocatedBytes();
      THREADS.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
  }

  /** A board with a value of each kind, and limits on the numbers. */
  static Spotter.Description description() {
    Spotter.Description description = Spotter.Description.newInstance().setRevision(REVISION);
    description.getMutableIdentity().setHostname("vision-front");
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("vision.health.fps")
            .setLabel("Frames per second")
            .setType(Spotter.FieldType.FIELD_TYPE_NUMBER)
            .setUnit("fps")
            .setWarn(Spotter.Limit.newInstance().setBelow(30))
            .setFail(Spotter.Limit.newInstance().setBelow(5).setMissing(true)));
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("debian.thermal.cpu")
            .setType(Spotter.FieldType.FIELD_TYPE_NUMBER)
            .setUnit("°C")
            .setWarn(Spotter.Limit.newInstance().setAbove(80)));
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("vision.health.mode")
            .setType(Spotter.FieldType.FIELD_TYPE_TEXT)
            .setFail(
                Spotter.Limit.newInstance()
                    .setNotEquals(Spotter.Scalar.newInstance().setText("tracking"))));
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("vision.health.detector")
            .setType(Spotter.FieldType.FIELD_TYPE_STATUS));
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("vision.health.armed")
            .setType(Spotter.FieldType.FIELD_TYPE_BOOLEAN));
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("vision.camera.product")
            .setType(Spotter.FieldType.FIELD_TYPE_TEXT));
    return description;
  }

  /**
   * Values as the agent sends them: every value when {@code complete}, else the two numbers and the
   * text, changed; the mode the same text unless {@code modes} says otherwise.
   */
  static Spotter.Values values(int i, boolean complete, boolean modes) {
    Spotter.Values values =
        Spotter.Values.newInstance()
            .setRevision(REVISION)
            .setTimeNanos(1_000_000_000L + i * 20_000_000L)
            .setComplete(complete);
    values.addValues(number(40 + i % 10).setIndex(0));
    values.addValues(number(50 + i % 7).setIndex(1));
    values.addValues(
        Spotter.FieldValue.newInstance()
            .setIndex(2)
            .setText(modes ? "searching " + i : "tracking"));
    if (complete) {
      values.addValues(
          Spotter.FieldValue.newInstance()
              .setIndex(3)
              .setStatus(
                  Spotter.Status.newInstance()
                      .setLevel(Spotter.Level.LEVEL_WARNING)
                      .setMessage("one camera of two")));
      values.addValues(Spotter.FieldValue.newInstance().setIndex(4).setFlag(true));
      values.addValues(
          Spotter.FieldValue.newInstance().setIndex(5).setUnavailable("timed out after 2 s"));
    }
    return values;
  }

  static Spotter.Event heartbeat(int i) {
    return Spotter.Event.newInstance()
        .setHeartbeat(
            Spotter.Heartbeat.newInstance().setTimeNanos(1_000_000_000L + i * 20_000_000L));
  }

  /** Writes an event in protobuf's delimited form. */
  static void write(ByteArrayOutputStream out, Spotter.Event event) {
    try {
      VersionTest.send(out, event);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * A stream as the agent sends it: the description, every value, then {@code messages} more,
   * mostly changes and heartbeats, every value again now and then.
   */
  static byte[] stream(int from, int messages, boolean modes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (from == 0) {
      write(out, Spotter.Event.newInstance().setDescribed(description()));
      write(out, Spotter.Event.newInstance().setValues(values(0, true, modes)));
    }
    for (int i = from + 1; i <= from + messages; i++) {
      if (i % 3 == 0) {
        write(out, heartbeat(i));
      } else {
        write(out, Spotter.Event.newInstance().setValues(values(i, i % 500 == 0, modes)));
      }
    }
    return out.toByteArray();
  }

  /**
   * Bytes from an array, counting what the reading thread allocates once it's past the warm-up:
   * from the first byte after it, to the end. Its own reads allocate nothing.
   */
  static final class Measured extends InputStream {
    final byte[] bytes;
    final int warm;
    int position;
    long start = -1;
    long end = -1;

    Measured(byte[] warmUp, byte[] measured) {
      bytes = new byte[warmUp.length + measured.length];
      System.arraycopy(warmUp, 0, bytes, 0, warmUp.length);
      System.arraycopy(measured, 0, bytes, warmUp.length, measured.length);
      warm = warmUp.length;
    }

    @Override
    public int read() {
      if (position == warm && start < 0) {
        start = THREADS.getCurrentThreadAllocatedBytes();
      }
      if (position == bytes.length) {
        if (end < 0) {
          end = THREADS.getCurrentThreadAllocatedBytes();
        }
        return -1;
      }
      return bytes[position++] & 0xff;
    }

    @Override
    public int read(byte[] into, int offset, int length) {
      int n = 0;
      while (n < length) {
        int b = read();
        if (b < 0) {
          return n == 0 ? -1 : n;
        }
        into[offset + n++] = (byte) b;
      }
      return n;
    }

    long allocated() {
      assertThat(start).as("the measured part was reached").isNotNegative();
      assertThat(end).as("the end was reached").isNotNegative();
      return end - start;
    }
  }

  private Link link(Board board) {
    return new Link(board, Boards.robot(clock), Settings.DEFAULTS, Recorder.NONE);
  }

  @Test
  void decodingTheStreamIntoTheSnapshotAllocatesNothing() {
    Board board = new Board("10.12.34.11", Settings.DEFAULTS);
    Link link = link(board);
    // 60,000 messages to warm up, 100,000 measured: the JIT compiles the decoding, and swaps
    // read()'s
    // loop to compiled code (at about 41,000), before anything's counted.
    Measured in = new Measured(stream(0, 60_000, false), stream(60_000, 100_000, false));
    assertThatThrownBy(() -> link.read(in)).isInstanceOf(EOFException.class);
    assertThat(in.allocated()).as("bytes allocated decoding 100,000 messages").isZero();

    // And what it decoded is what was sent.
    board.update(clock.get(), 0, Settings.DEFAULTS.missing().toNanos());
    // The last message sent, 160,000, is values.
    assertThat(value(board, "vision.health.fps").number()).isEqualTo(40 + 160_000 % 10);
    assertThat(value(board, "vision.health.mode").text()).isEqualTo("tracking");
    assertThat(value(board, "vision.health.detector").level()).isEqualTo(Level.WARNING);
    assertThat(value(board, "vision.camera.product").unavailable())
        .isEqualTo("timed out after 2 s");
  }

  @Test
  void textAllocatesOnlyWhenItChanges() {
    Board board = new Board("10.12.34.11", Settings.DEFAULTS);
    Link link = link(board);
    Measured in = new Measured(stream(0, 20_000, true), stream(20_000, 10_000, true));
    assertThatThrownBy(() -> link.read(in)).isInstanceOf(EOFException.class);
    // A new mode in two of every three messages: each decoded once, as a String.
    assertThat(in.allocated()).as("bytes allocated decoding 10,000 messages").isPositive();
    board.update(clock.get(), 0, Settings.DEFAULTS.missing().toNanos());
    assertThat(value(board, "vision.health.mode").text()).isEqualTo("searching 29999");
  }

  @Test
  void theLoopsUpdateAllocatesNothing() {
    Manager manager =
        new Manager(Boards.robot(clock), List.of("10.12.34.11"), Settings.DEFAULTS, Recorder.NONE);
    Link link = manager.link(0);
    Spotter.Event[] events = new Spotter.Event[30];
    events[0] = Spotter.Event.newInstance().setDescribed(description());
    for (int i = 1; i < events.length; i++) {
      events[i] =
          i % 3 == 0
              ? heartbeat(i)
              : Spotter.Event.newInstance().setValues(values(i, i == 1, false));
    }
    // One loop, warm-up and measurement alike, the robot's clock moving 1 ms a turn; counted from
    // turn 60,000, after the agent's clock map has switched windows (each 10 s) a few times. Each
    // turn's own calls are counted, from just before the event to just after the update: never the
    // loop between turns, where the JIT swaps a hot loop to compiled code (about 41,000 turns after
    // it starts, or after a branch it hadn't seen), which can cost the test's thread a few hundred
    // bytes the manager never allocated.
    int warm = 60_000;
    int measured = 100_000;
    long received = 0;
    long ticked = 0;
    long updated = 0;
    int first = -1;
    for (int i = 0; i < warm + measured; i++) {
      long before = THREADS.getCurrentThreadAllocatedBytes();
      link.received(events[i < events.length ? i : 1 + i % (events.length - 1)]);
      long afterEvent = THREADS.getCurrentThreadAllocatedBytes();
      clock.addAndGet(1_000_000);
      long afterClock = THREADS.getCurrentThreadAllocatedBytes();
      manager.update();
      long afterUpdate = THREADS.getCurrentThreadAllocatedBytes();
      if (i >= warm && afterUpdate != before) {
        received += afterEvent - before;
        ticked += afterClock - afterEvent;
        updated += afterUpdate - afterClock;
        first = first < 0 ? i - warm : first;
      }
    }
    assertThat(received + ticked + updated)
        .as(
            "bytes allocated in 100,000 events and updates: received %d, clock %d, update %d,"
                + " the first at turn %d",
            received, ticked, updated, first)
        .isZero();
    assertThat(manager.boards().get(0).connection()).isEqualTo(Connection.CONNECTED);
    assertThat(manager.alerts()).hasSize(1);
  }

  @Test
  void aBoardsThreadAllocatesLittleReadingARealAgentsStream(@TempDir Path dir) throws Exception {
    // Decoding and publishing allocate nothing (above). What the board's thread does allocate is
    // the JDK's HTTP client's, reading each event's chunk: a few dozen bytes. A bound, not a zero,
    // so a regression shows without fighting the JDK.
    try (LocalAgent agent = new LocalAgent(dir, "vision-front")) {
      agent
          .pack("vision", ManagerTest.PACK)
          .script("vision", "health", ManagerTest.HEALTHY)
          .start();
      Manager manager =
          new Manager(
              Boards.robot(),
              List.of(agent.address()),
              Settings.DEFAULTS.withHeartbeat(java.time.Duration.ofMillis(50)),
              Recorder.NONE);
      try {
        manager.start();
        Board board = manager.boards().get(0);
        await(
            manager,
            "connected",
            () ->
                board.value("vision.health.fps") != null
                    && value(board, "vision.health.fps").available());
        Link link = manager.link(0);
        long thread = Objects.requireNonNull(link.thread()).getId();
        // Warm up: changes and heartbeats, for a few seconds.
        for (int i = 0; i < 300; i++) {
          agent.set("vision.health.fps", number(40 + i % 10));
          Thread.sleep(10);
        }
        long events = link.events();
        long start = THREADS.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 300; i++) {
          agent.set("vision.health.fps", number(50 + i % 10));
          Thread.sleep(10);
        }
        long allocated = THREADS.getThreadAllocatedBytes(thread) - start;
        long received = link.events() - events;
        System.out.printf(
            "A board's thread, reading a real agent's stream: %d events, %d bytes each%n",
            received, allocated / Math.max(1, received));
        // Changes, bundled 20 ms at a time, and heartbeats between them.
        assertThat(received).isGreaterThan(50);
        assertThat(allocated / received).as("bytes allocated per event").isLessThan(256);
      } finally {
        manager.close();
      }
    }
  }
}
