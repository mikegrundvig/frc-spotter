package com.michaelgrundvig.frc.spotter.agent.server;

import com.michaelgrundvig.frc.spotter.agent.Agent;
import com.michaelgrundvig.frc.spotter.agent.packs.PackReader;
import com.michaelgrundvig.frc.spotter.agent.values.ValueStore;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * One manager's stream: on connect, the description, then every value, then the runs the agent
 * keeps; after that, as things happen. A value that changes is sent with whatever else changed
 * within {@link #BUNDLE_NANOS} of it, by its index; every value again each {@link #COMPLETE_NANOS},
 * so a missed change can't stick; a run's start, log lines and finish at once; the description
 * again whenever its revision changes; and a heartbeat whenever nothing else was sent for the
 * interval the manager asked for, which doubles as the check that the board is there. When each
 * goes is {@link Pacing}'s to say, on the clock the stream is given.
 */
final class Stream implements Events.Subscriber {
  /** How long a change waits for others to send with it. */
  static final long BUNDLE_NANOS = 20_000_000L;

  /** How often every value is sent again. */
  static final long COMPLETE_NANOS = 10_000_000_000L;

  /** The heartbeat interval unless asked, and the least and most it may be. */
  static final Duration HEARTBEAT = Duration.ofMillis(250);

  static final Duration MIN_HEARTBEAT = Duration.ofMillis(50);
  static final Duration MAX_HEARTBEAT = Duration.ofSeconds(5);

  /** The most run events waiting to be sent: a stream that falls this far behind is closed. */
  static final int MAX_QUEUED = 10_000;

  /**
   * The most an event's message may hold, in bytes: {@link Protocol#MAX_EVENT}, less room for the
   * event around it. Values past it are sent as several events; the runs listed on connect are the
   * running ones and the newest finished, as many as fit.
   */
  static final int MAX_HELD = Protocol.MAX_EVENT - 64;

  /** Where events go: the response, as protobuf's delimited form or JSON lines. */
  interface Sink {
    void send(Spotter.Event event) throws IOException;
  }

  private final Agent agent;
  private final LongSupplier clock;
  private final Sink sink;
  private final Object lock = new Object();
  private final ArrayDeque<Spotter.RunEvent> queued = new ArrayDeque<>();
  private final Pacing pacing;
  private boolean behind;
  private volatile long sendingSince;

  /**
   * @param clock the time, in nanoseconds, on a monotonic clock: the server's
   */
  Stream(Agent agent, Duration heartbeat, LongSupplier clock, Sink sink) {
    this(agent, heartbeat, COMPLETE_NANOS, clock, sink);
  }

  /**
   * @param complete how often every value is sent again, in nanoseconds
   * @param clock the time, in nanoseconds, on a monotonic clock: the server's, or a test's
   */
  Stream(Agent agent, Duration heartbeat, long complete, LongSupplier clock, Sink sink) {
    this.agent = agent;
    this.clock = clock;
    this.sink = sink;
    this.pacing = new Pacing(heartbeat.toNanos(), complete, clock.getAsLong());
  }

  /**
   * The heartbeat interval a request asks for ({@code 250ms}, {@code 1s}), kept between {@link
   * #MIN_HEARTBEAT} and {@link #MAX_HEARTBEAT}; empty when it isn't a duration.
   */
  static Optional<Duration> heartbeat(String asked) {
    if (asked.isEmpty()) {
      return Optional.of(HEARTBEAT);
    }
    return PackReader.duration(asked)
        .map(d -> d.compareTo(MIN_HEARTBEAT) < 0 ? MIN_HEARTBEAT : d)
        .map(d -> d.compareTo(MAX_HEARTBEAT) > 0 ? MAX_HEARTBEAT : d);
  }

  @Override
  public void valuesChanged() {
    synchronized (lock) {
      pacing.changed(clock.getAsLong());
      lock.notifyAll();
    }
  }

  @Override
  public void run(Spotter.RunEvent event) {
    synchronized (lock) {
      if (queued.size() >= MAX_QUEUED) {
        behind = true;
      } else {
        queued.add(event);
      }
      lock.notifyAll();
    }
  }

  /**
   * Sends events until the manager goes away (an {@link IOException} sending) or the thread is
   * interrupted.
   */
  void serve() throws IOException, InterruptedException {
    long connected = clock.getAsLong();
    Spotter.Description description = agent.description();
    int revision = description.getRevision();
    send(Spotter.Event.newInstance().setDescribed(description));
    ValueStore.Delta delta = agent.store().delta(-1, revision, agent.nanos());
    long seen = delta.changes();
    send(delta.values());
    send(Spotter.Event.newInstance().setRuns(runs(agent.runs().states())));
    synchronized (lock) {
      pacing.completeSent(connected);
    }
    while (true) {
      List<Spotter.RunEvent> events;
      boolean changed;
      boolean complete;
      long now;
      // Each wake decides on one reading of the clock, and each send is timed as it starts: so
      // what's due follows from the time alone, however long a send or a wake takes.
      synchronized (lock) {
        while (true) {
          if (behind) {
            throw new IOException("the stream fell behind by " + MAX_QUEUED + " run events");
          }
          now = clock.getAsLong();
          long wait = pacing.wake() - now;
          if (!queued.isEmpty() || pacing.changesDue(now) || wait <= 0) {
            break;
          }
          lock.wait(wait / 1_000_000, (int) (wait % 1_000_000));
        }
        events = new ArrayList<>(queued);
        queued.clear();
        changed = pacing.changesDue(now);
        if (changed) {
          pacing.changesTaken();
        }
        complete = pacing.completeDue(now);
      }
      boolean everything = false;
      if (agent.revision() != revision) {
        description = agent.description();
        revision = description.getRevision();
        send(Spotter.Event.newInstance().setDescribed(description));
        everything = true;
      }
      for (Spotter.RunEvent event : events) {
        send(Spotter.Event.newInstance().setRun(event));
      }
      if (everything || complete) {
        delta = agent.store().delta(-1, revision, agent.nanos());
        seen = delta.changes();
        long sending = clock.getAsLong();
        send(delta.values());
        synchronized (lock) {
          pacing.completeSent(sending);
        }
      } else if (changed) {
        delta = agent.store().delta(seen, revision, agent.nanos());
        seen = delta.changes();
        if (delta.values().getValues().length() > 0) {
          send(delta.values());
        }
      }
      boolean heartbeat;
      synchronized (lock) {
        heartbeat = pacing.heartbeatDue(now);
      }
      if (heartbeat) {
        send(
            Spotter.Event.newInstance()
                .setHeartbeat(Spotter.Heartbeat.newInstance().setTimeNanos(agent.nanos())));
      }
    }
  }

  /** Sends an event, timed as it starts. */
  private void send(Spotter.Event event) throws IOException {
    long now = clock.getAsLong();
    sendingSince = now | 1;
    try {
      sink.send(event);
    } finally {
      sendingSince = 0;
    }
    synchronized (lock) {
      pacing.sent(now);
    }
  }

  /**
   * How long its write under way has been stalled, in nanoseconds: a reader that stopped reading
   * leaves it waiting; 0 when none is under way.
   *
   * @param now the time on the stream's clock
   */
  long stalledNanos(long now) {
    long since = sendingSince;
    return since == 0 ? 0 : now - since;
  }

  /**
   * Sends values: in one event, or, past {@link #MAX_HELD}, in as many as they take, each with the
   * same revision, time and completeness, one after another.
   */
  private void send(Spotter.Values values) throws IOException {
    for (Spotter.Values part : parts(values)) {
      send(Spotter.Event.newInstance().setValues(part));
    }
  }

  /** Values as the events that carry them: one, or several each within {@link #MAX_HELD}. */
  static List<Spotter.Values> parts(Spotter.Values values) {
    if (values.getSerializedSize() <= MAX_HELD) {
      return List.of(values);
    }
    List<Spotter.Values> parts = new ArrayList<>();
    Spotter.Values part = null;
    int size = 0;
    for (Spotter.FieldValue value : values.getValues()) {
      int its = value.getSerializedSize() + 8;
      if (part == null || size + its > MAX_HELD) {
        part =
            Spotter.Values.newInstance()
                .setRevision(values.getRevision())
                .setTimeNanos(values.getTimeNanos())
                .setComplete(values.getComplete());
        parts.add(part);
        size = part.getSerializedSize();
      }
      part.addValues(value);
      size += its;
    }
    return parts;
  }

  /**
   * The runs listed on connect, in the order they started: every running one, then the newest
   * finished, as many as fit {@link #MAX_HELD}. One left out is still kept, and fetched by its id.
   */
  static Spotter.Runs runs(List<Spotter.RunState> states) {
    List<Spotter.RunState> chosen = new ArrayList<>();
    int size = 0;
    for (boolean running : new boolean[] {true, false}) {
      for (int i = states.size() - 1; i >= 0; i--) {
        Spotter.RunState state = states.get(i);
        int its = state.getSerializedSize() + 8;
        if (state.getRunning() == running && size + its <= MAX_HELD) {
          chosen.add(state);
          size += its;
        }
      }
    }
    Spotter.Runs runs = Spotter.Runs.newInstance();
    for (Spotter.RunState state : states) {
      if (chosen.contains(state)) {
        runs.addRuns(state);
      }
    }
    return runs;
  }
}
