package com.michaelgrundvig.frc.spotter.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Asking one coprocessor to power down, against a stand-in for its agent and ports: asked again
 * until taken, never again once refused, never past the timeout, and never overwritten by an
 * earlier request's thread.
 */
class PowerDownerTest {
  /** A coprocessor whose answers the test lines up: a status, or no answer. */
  private static final class Stand implements PowerDowner.Target {
    final Deque<Object> answers = new ConcurrentLinkedDeque<>();
    final AtomicInteger asks = new AtomicInteger();
    final AtomicInteger probes = new AtomicInteger();
    volatile boolean agentOpen = true;
    volatile boolean softwareOpen = true;

    /** Holds the first request until released, deaf to interrupts as a stuck socket can be. */
    volatile CountDownLatch holdFirst = new CountDownLatch(0);

    @Override
    public int ask() throws IOException {
      int asked = asks.incrementAndGet();
      Object polled = answers.pollFirst();
      Object next = polled != null ? polled : 202;
      if (asked == 1) {
        awaitIgnoringInterrupts(holdFirst);
      }
      if (next instanceof IOException e) {
        throw e;
      }
      return (Integer) next;
    }

    @Override
    public boolean agentAnswers() {
      probes.incrementAndGet();
      return agentOpen;
    }

    @Override
    public boolean softwareAnswers() {
      return softwareOpen;
    }
  }

  private static void awaitIgnoringInterrupts(CountDownLatch latch) {
    while (true) {
      try {
        latch.await();
        return;
      } catch (InterruptedException ignored) {
        // As a socket deaf to interrupts: keeps waiting.
      }
    }
  }

  private final Stand stand = new Stand();
  private PowerDowner downer = new PowerDowner("vision-front", stand, 10, 5_000);

  @AfterEach
  void stop() {
    downer.close();
  }

  private static void until(BooleanSupplier done) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!done.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
  }

  @Test
  void aRequestThatFindsNoOneIsAskedAgainUntilItsTaken() throws Exception {
    stand.answers.add(new SocketTimeoutException("connect timed out"));
    stand.answers.add(new SocketTimeoutException("connect timed out"));
    stand.answers.add(202);

    downer.ask();
    until(() -> downer.state().accepted());
    stand.agentOpen = false;
    stand.softwareOpen = false;
    until(() -> downer.state().gone());

    assertThat(stand.asks).hasValue(3);
    assertThat(downer.state().answer()).isEqualTo("accepted");
  }

  /** Unreached, it's quiet on both ports, and still never gone: it never took the request. */
  @Test
  void aBoardThatNeverTookTheRequestIsNeverGone() throws Exception {
    stand.agentOpen = false;
    stand.softwareOpen = false;
    for (int i = 0; i < 1000; i++) {
      stand.answers.add(new SocketTimeoutException("connect timed out"));
    }

    downer.ask();
    until(() -> downer.state().tried());
    Thread.sleep(100);

    assertThat(downer.state().gone()).isFalse();
    assertThat(downer.state().answer()).isEqualTo("connect timed out");
  }

  @Test
  void aRefusalIsntAskedAgainAndItsPortsArentWatched() throws Exception {
    stand.answers.add(404);

    downer.ask();
    until(() -> downer.state().tried());
    int probes = stand.probes.get();
    Thread.sleep(100);

    assertThat(stand.asks).hasValue(1);
    assertThat(stand.probes).hasValue(probes);
    assertThat(downer.state().answer()).isEqualTo("refused: answered 404");
    assertThat(downer.state().accepted()).isFalse();
  }

  @Test
  void anAgentThatFailsIsAskedAgain() throws Exception {
    stand.answers.add(500);
    stand.answers.add(202);

    downer.ask();
    until(() -> downer.state().accepted());

    assertThat(stand.asks).hasValue(2);
  }

  @Test
  void askingStopsAtTheTimeout() throws Exception {
    downer = new PowerDowner("vision-front", stand, 10, 100);
    for (int i = 0; i < 1000; i++) {
      stand.answers.add(new SocketTimeoutException("connect timed out"));
    }

    downer.ask();
    Thread.sleep(300);
    int asked = stand.asks.get();
    Thread.sleep(100);

    assertThat(stand.asks).hasValue(asked);
    assertThat(asked).isBetween(2, 20);
  }

  /**
   * Asked again while an earlier request's thread is stuck: when that thread comes unstuck, what it
   * found never overwrites the later request's state.
   */
  @Test
  void anEarlierRequestNeverWritesOverALaterOne() throws Exception {
    stand.holdFirst = new CountDownLatch(1);
    stand.answers.add(500); // the first request, held, then failing
    stand.answers.add(202); // the second, taken

    downer.ask();
    until(() -> stand.asks.get() == 1);
    downer.ask();
    until(() -> downer.state().accepted());
    stand.holdFirst.countDown();
    Thread.sleep(100);

    assertThat(downer.state().answer()).isEqualTo("accepted");
    assertThat(downer.state().accepted()).isTrue();
  }

  @Test
  void stoppingEndsTheWatch() throws Exception {
    downer.ask();
    until(() -> downer.state().accepted());

    downer.stop();
    Thread.sleep(50);
    int probes = stand.probes.get();
    Thread.sleep(100);

    assertThat(stand.probes).hasValue(probes);
    assertThat(downer.state().accepted()).as("where it stood stays").isTrue();
  }

  @Test
  void beforeAnyRequestNothingIsAsked() throws Exception {
    assertThat(downer.state()).isEqualTo(PowerDowner.State.NONE);
    assertThat(stand.holdFirst.await(0, TimeUnit.MILLISECONDS)).isTrue();
  }
}
