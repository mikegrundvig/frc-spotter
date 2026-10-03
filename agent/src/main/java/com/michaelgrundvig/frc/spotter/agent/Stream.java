package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One manager's stream: on connect, the description, then every value, then the runs the agent
 * keeps; after that, as things happen. A value that changes is sent with whatever else changed
 * within {@link #BUNDLE_NANOS} of it, by its index; every value again each {@link #COMPLETE_NANOS},
 * so a missed change can't stick; a run's start, log lines and finish at once; the description
 * again whenever its revision changes; and a heartbeat whenever nothing else was sent for the
 * interval the manager asked for, which doubles as the check that the board is there.
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

  /** Where events go: the response, as protobuf's delimited form or JSON lines. */
  interface Sink {
    void send(Spotter.Event event) throws IOException;
  }

  private final Agent agent;
  private final long heartbeat;
  private final long complete;
  private final Sink sink;
  private final Object lock = new Object();
  private final ArrayDeque<Spotter.RunEvent> queued = new ArrayDeque<>();
  private boolean dirty;
  private long dirtySince;
  private boolean behind;
  private long lastSent;

  Stream(Agent agent, Duration heartbeat, Sink sink) {
    this(agent, heartbeat, COMPLETE_NANOS, sink);
  }

  /**
   * @param complete how often every value is sent again, in nanoseconds
   */
  Stream(Agent agent, Duration heartbeat, long complete, Sink sink) {
    this.agent = agent;
    this.heartbeat = heartbeat.toNanos();
    this.complete = complete;
    this.sink = sink;
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
      if (!dirty) {
        dirty = true;
        dirtySince = System.nanoTime();
      }
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
    Spotter.Description description = agent.description();
    int revision = description.getRevision();
    send(Spotter.Event.newInstance().setDescribed(description));
    ValueStore.Delta delta = agent.store().delta(-1, revision, agent.nanos());
    long seen = delta.changes();
    send(Spotter.Event.newInstance().setValues(delta.values()));
    Spotter.Runs runs = Spotter.Runs.newInstance();
    for (Spotter.RunState state : agent.runs().states()) {
      runs.addRuns(state);
    }
    send(Spotter.Event.newInstance().setRuns(runs));
    long nextComplete = System.nanoTime() + complete;
    while (true) {
      List<Spotter.RunEvent> events;
      boolean changed;
      synchronized (lock) {
        while (true) {
          if (behind) {
            throw new IOException("the stream fell behind by " + MAX_QUEUED + " run events");
          }
          long now = System.nanoTime();
          if (!queued.isEmpty() || (dirty && now - dirtySince >= BUNDLE_NANOS)) {
            break;
          }
          long wake = Math.min(lastSent + heartbeat, nextComplete);
          if (dirty) {
            wake = Math.min(wake, dirtySince + BUNDLE_NANOS);
          }
          if (now >= wake) {
            break;
          }
          long wait = wake - now;
          lock.wait(wait / 1_000_000, (int) (wait % 1_000_000));
        }
        events = new ArrayList<>(queued);
        queued.clear();
        changed = dirty && System.nanoTime() - dirtySince >= BUNDLE_NANOS;
        if (changed) {
          dirty = false;
        }
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
      long now = System.nanoTime();
      if (everything || now >= nextComplete) {
        delta = agent.store().delta(-1, revision, agent.nanos());
        seen = delta.changes();
        send(Spotter.Event.newInstance().setValues(delta.values()));
        nextComplete = now + complete;
      } else if (changed) {
        delta = agent.store().delta(seen, revision, agent.nanos());
        seen = delta.changes();
        if (delta.values().getValues().length() > 0) {
          send(Spotter.Event.newInstance().setValues(delta.values()));
        }
      }
      if (System.nanoTime() - lastSent >= heartbeat) {
        send(
            Spotter.Event.newInstance()
                .setHeartbeat(Spotter.Heartbeat.newInstance().setTimeNanos(agent.nanos())));
      }
    }
  }

  private void send(Spotter.Event event) throws IOException {
    sink.send(event);
    lastSent = System.nanoTime();
  }
}
