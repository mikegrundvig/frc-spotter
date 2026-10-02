package com.michaelgrundvig.frc.spotter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Polling on a thread of its own, and what the loop reads of it. */
class PollerTest {
  private final AtomicLong clock = new AtomicLong(1_000);
  private volatile boolean answering = true;

  /** An answer that takes 400 ns on the robot's clock, numbering each. */
  private String fetch() throws IOException {
    clock.addAndGet(400);
    if (!answering) {
      throw new SocketTimeoutException("connect timed out");
    }
    return "answer at " + clock.get();
  }

  @Test
  void beforeTheFirstPollThereIsNothing() {
    var poller = new Poller<>("vision-front", this::fetch, 1, clock::get);

    var latest = poller.latest();

    assertThat(latest.polls()).isZero();
    assertThat(latest.answered()).isFalse();
    assertThat(latest.answer()).isNull();
    assertThat(latest.error()).isEqualTo("not asked yet");
  }

  @Test
  void anAnswerIsPlacedHalfwayBetweenAskingAndHearing() {
    var poller = new Poller<>("vision-front", this::fetch, 1, clock::get);

    poller.pollOnce();

    var latest = poller.latest();
    assertThat(latest.answered()).isTrue();
    assertThat(latest.answer()).isEqualTo("answer at 1400");
    assertThat(latest.answeredNanos()).isEqualTo(1_200);
    assertThat(latest.polledNanos()).isEqualTo(1_400);
    assertThat(latest.error()).isEmpty();
  }

  @Test
  void aFailedPollKeepsTheLastAnswerAndSaysWhy() {
    var poller = new Poller<>("vision-front", this::fetch, 1, clock::get);
    poller.pollOnce();

    answering = false;
    poller.pollOnce();

    var latest = poller.latest();
    assertThat(latest.polls()).isEqualTo(2);
    assertThat(latest.answered()).isFalse();
    assertThat(latest.error()).isEqualTo("connect timed out");
    assertThat(latest.answer()).isEqualTo("answer at 1400");
    assertThat(latest.answeredNanos()).isEqualTo(1_200);
  }

  @Test
  void anythingAPollThrowsIsWhyItFailed() {
    var poller =
        new Poller<String>(
            "vision-front",
            () -> {
              throw new IllegalStateException();
            },
            1,
            clock::get);

    poller.pollOnce();

    assertThat(poller.latest().error()).isEqualTo("IllegalStateException");
  }

  @Test
  void itPollsOnItsOwnThreadUntilClosed() throws Exception {
    CountDownLatch twice = new CountDownLatch(2);
    var poller =
        new Poller<>(
            "vision-front",
            () -> {
              twice.countDown();
              assertThat(Thread.currentThread().getName()).isEqualTo("Coprocessor vision-front");
              assertThat(Thread.currentThread().isDaemon()).isTrue();
              return "ok";
            },
            0.01,
            clock::get);

    poller.start();
    try {
      assertThat(twice.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(poller.latest().answer()).isEqualTo("ok");
    } finally {
      poller.close();
    }
  }

  @Test
  void aPeriodIsPositive() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new Poller<>("vision-front", this::fetch, 0, clock::get));
  }
}
