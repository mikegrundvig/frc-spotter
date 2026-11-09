package com.michaelgrundvig.frc.spotter.agent.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.agent.Agent;
import com.michaelgrundvig.frc.spotter.agent.Fixture;
import com.michaelgrundvig.frc.spotter.agent.packs.Packs;
import com.michaelgrundvig.frc.spotter.agent.values.ValueStore;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One manager's stream: what it's sent on connect, then as things happen. Its clock is the board's
 * own, which only the test moves, so what's due when is exact: nothing is sent while the clock
 * stands still, and each step of it sends what that time makes due. ({@link PacingTest} has the
 * rules alone; the container tests time a real stream.)
 */
class StreamTest {
  @TempDir Path dir;
  Fixture fixture;
  Agent agent;
  @org.jspecify.annotations.Nullable Thread serving;
  volatile boolean gone;

  final BlockingQueue<Spotter.Event> got = new LinkedBlockingQueue<>();

  @BeforeEach
  void anAgent() throws Exception {
    fixture = new Fixture(dir);
    // Its program is there, so it's trusted; the tests set its values themselves.
    fixture.script(Packs.INSTALLED + "/vision/health", "true");
    fixture.pack(
        "vision",
        """
        pack: vision
        collectors:
          - id: health
            run: [./health]
            every: 1h
            fields:
              fps: {type: number}
              mode: {type: text}
        """);
    agent = fixture.agent();
  }

  @AfterEach
  void stop() throws Exception {
    gone = true;
    Thread thread = serving;
    if (thread != null) {
      thread.interrupt();
      thread.join(5000);
    }
    agent.close();
  }

  private void connect(Duration heartbeat, long complete) {
    Stream stream =
        new Stream(
            agent,
            heartbeat,
            complete,
            fixture.nanos::get,
            event -> {
              if (gone) {
                throw new IOException("the manager went away");
              }
              got.add(event.clone());
            });
    agent.events().subscribe(stream);
    serving =
        new Thread(
            () -> {
              try {
                stream.serve();
              } catch (IOException | InterruptedException e) {
                // gone
              } finally {
                agent.events().unsubscribe(stream);
              }
            });
    serving.start();
  }

  private Spotter.Event next() throws InterruptedException {
    return Objects.requireNonNull(got.poll(5, TimeUnit.SECONDS), "no event within 5 s");
  }

  /** The next event that isn't a heartbeat. */
  private Spotter.Event nextNotHeartbeat() throws InterruptedException {
    while (true) {
      Spotter.Event event = next();
      if (!event.hasHeartbeat()) {
        return event;
      }
    }
  }

  /** Moves the board's clock on, as time passing would. */
  private void advance(long nanos) {
    fixture.nanos.addAndGet(nanos);
  }

  /** Nothing is sent while the clock stands still: nothing is due yet. */
  private void nothingSent() throws InterruptedException {
    assertThat(got.poll(200, TimeUnit.MILLISECONDS)).isNull();
  }

  /** The three events sent on connect: the description, every value, the runs. */
  private void connected() throws InterruptedException {
    for (int i = 0; i < 3; i++) {
      next();
    }
  }

  private static Spotter.FieldValue number(double value) {
    return Spotter.FieldValue.newInstance().setNumber(value);
  }

  @Test
  void onConnectItSendsTheDescriptionEveryValueAndTheRuns() throws Exception {
    agent.store().set(0, List.of(number(30)));
    connect(Duration.ofSeconds(5), Stream.COMPLETE_NANOS);
    Spotter.Event described = next();
    assertThat(described.hasDescribed()).isTrue();
    assertThat(described.getDescribed().getValues()).hasSize(2);
    Spotter.Values values = next().getValues();
    assertThat(values.getComplete()).isTrue();
    assertThat(values.getRevision()).isEqualTo(described.getDescribed().getRevision());
    assertThat(values.getValues()).hasSize(2);
    assertThat(values.getValues().get(0).getNumber()).isEqualTo(30);
    assertThat(values.getValues().get(1).getUnavailable()).isEqualTo(ValueStore.NOT_YET);
    assertThat(next().hasRuns()).isTrue();
  }

  @Test
  void whenNothingHappensItSendsAHeartbeatEachIntervalAsked() throws Exception {
    connect(Duration.ofMillis(50), Stream.COMPLETE_NANOS);
    connected();
    nothingSent();
    for (int i = 0; i < 3; i++) {
      advance(30_000_000L);
      nothingSent();
      advance(20_000_000L);
      Spotter.Event heartbeat = next();
      assertThat(heartbeat.hasHeartbeat()).isTrue();
      assertThat(heartbeat.getHeartbeat().getTimeNanos()).isEqualTo(fixture.nanos.get());
    }
  }

  @Test
  void changesWithin20MsAreSentTogetherByIndexAndOnlyThem() throws Exception {
    connect(Duration.ofSeconds(1), Stream.COMPLETE_NANOS);
    connected();
    agent.store().set(0, List.of(number(29.5)));
    agent.store().set(1, List.of(Spotter.FieldValue.newInstance().setText("tags")));
    // Within the 20 ms a change waits for others: nothing yet.
    nothingSent();
    advance(Stream.BUNDLE_NANOS);
    Spotter.Values delta = next().getValues();
    assertThat(delta.getComplete()).isFalse();
    assertThat(delta.getValues()).extracting(Spotter.FieldValue::getIndex).containsExactly(0, 1);
    // The same value again is no change.
    agent.store().set(0, List.of(number(29.5)));
    agent.store().set(0, List.of(number(12)));
    advance(Stream.BUNDLE_NANOS);
    Spotter.Values next = next().getValues();
    assertThat(next.getValues()).hasSize(1);
    assertThat(next.getValues().get(0).getNumber()).isEqualTo(12);
  }

  @Test
  void everyValueIsSentAgainSoAMissedChangeCantStick() throws Exception {
    connect(Duration.ofSeconds(1), Duration.ofMillis(200).toNanos());
    connected();
    advance(Duration.ofMillis(199).toNanos());
    nothingSent();
    advance(Duration.ofMillis(1).toNanos());
    Spotter.Values again = next().getValues();
    assertThat(again.getComplete()).isTrue();
    assertThat(again.getValues()).hasSize(2);
  }

  @Test
  void runEventsAreSentAsTheyHappen() throws Exception {
    connect(Duration.ofSeconds(5), Stream.COMPLETE_NANOS);
    connected();
    agent
        .events()
        .run(
            Spotter.RunEvent.newInstance()
                .setRun("0123456789abcdef")
                .setAction("core.reboot")
                .setStarted(true));
    // At once, the clock standing still.
    Spotter.Event run = next();
    assertThat(run.getRun().getRun()).isEqualTo("0123456789abcdef");
    assertThat(run.getRun().getStarted()).isTrue();
  }

  @Test
  void aNewDescriptionIsSentWithEveryValueWhenItsRevisionChanges() throws Exception {
    connect(Duration.ofMillis(50), Stream.COMPLETE_NANOS);
    Spotter.Description first = next().getDescribed();
    next();
    next();
    fixture.addresses.add("10.12.34.12");
    fixture.nanos.addAndGet(Agent.DESCRIPTION_NANOS);
    Spotter.Event described = nextNotHeartbeat();
    assertThat(described.hasDescribed()).isTrue();
    assertThat(described.getDescribed().getRevision()).isNotEqualTo(first.getRevision());
    Spotter.Values values = nextNotHeartbeat().getValues();
    assertThat(values.getComplete()).isTrue();
    assertThat(values.getRevision()).isEqualTo(described.getDescribed().getRevision());
  }

  @Test
  void theHeartbeatAskedForIsKeptWithinItsBounds() {
    assertThat(Stream.heartbeat("")).hasValue(Stream.HEARTBEAT);
    assertThat(Stream.heartbeat("100ms")).hasValue(Duration.ofMillis(100));
    assertThat(Stream.heartbeat("10ms")).hasValue(Stream.MIN_HEARTBEAT);
    assertThat(Stream.heartbeat("1m")).hasValue(Stream.MAX_HEARTBEAT);
    assertThat(Stream.heartbeat("often")).isEmpty();
  }

  @Test
  void valuesPastWhatAnEventHoldsAreSentAsSeveral() {
    Spotter.Values small = Spotter.Values.newInstance().setRevision(3);
    small.addValues(Spotter.FieldValue.newInstance().setIndex(0).setNumber(1));
    assertThat(Stream.parts(small)).containsExactly(small);

    // Forty texts of 60 KiB, as forty collectors' whole outputs: 2.3 MiB in all.
    Spotter.Values values =
        Spotter.Values.newInstance().setRevision(3).setTimeNanos(7).setComplete(true);
    String text = "x".repeat(60 * 1024);
    for (int i = 0; i < 40; i++) {
      values.addValues(Spotter.FieldValue.newInstance().setIndex(i).setText(text));
    }
    List<Spotter.Values> parts = Stream.parts(values);
    assertThat(parts)
        .hasSize(3)
        .allMatch(part -> part.getSerializedSize() <= Stream.MAX_HELD)
        .allMatch(part -> part.getRevision() == 3 && part.getTimeNanos() == 7)
        .allMatch(Spotter.Values::getComplete);
    List<Integer> indexes = new ArrayList<>();
    for (Spotter.Values part : parts) {
      for (Spotter.FieldValue value : part.getValues()) {
        indexes.add(value.getIndex());
      }
    }
    assertThat(indexes).hasSize(40).isSorted();
  }

  @Test
  void theRunsListedOnConnectFitAnEventTheRunningOnesFirst() {
    List<Spotter.RunState> states = new ArrayList<>();
    states.add(Spotter.RunState.newInstance().setRun("00").setAction("p.wait").setRunning(true));
    String output = "y".repeat(250 * 1024);
    for (int i = 1; i <= 10; i++) {
      Spotter.RunResult result =
          Spotter.RunResult.newInstance()
              .addResponse(Spotter.FieldValue.newInstance().setName("output").setText(output));
      states.add(
          Spotter.RunState.newInstance()
              .setRun(String.format("%02d", i))
              .setAction("p.a" + i)
              .setResult(result));
    }
    Spotter.Runs runs = Stream.runs(states);
    assertThat(runs.getSerializedSize()).isLessThanOrEqualTo(Stream.MAX_HELD);
    List<String> listed = new ArrayList<>();
    for (Spotter.RunState state : runs.getRuns()) {
      listed.add(state.getRun());
    }
    // The running one, then the newest four that fit, in the order they started.
    assertThat(listed).containsExactly("00", "07", "08", "09", "10");
  }
}
