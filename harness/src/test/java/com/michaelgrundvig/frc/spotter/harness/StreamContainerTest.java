package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Network;

/**
 * The stream as the manager keeps it open: on connect the description, every value and the runs;
 * then deltas, heartbeats when idle, and every value again each 10 s. A paused agent goes silent,
 * so the manager's second of silence finds it missing; an agent restarted is reconnected to and
 * fully re-synced.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamContainerTest {
  Network network;
  Coprocessor coprocessor;
  TestClient client;

  @BeforeAll
  void aCoprocessor() {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(TestImages.agent(), network, 41, "vision-stream");
    coprocessor.start();
    client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
  }

  @AfterAll
  void stop() {
    if (coprocessor != null) {
      coprocessor.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  @Test
  void onConnectThenDeltasHeartbeatsAndEveryValueEach10Seconds() throws Exception {
    try (TestClient.Stream stream = client.stream("?heartbeat=100ms")) {
      Spotter.Description description = stream.next(Duration.ofSeconds(5)).event().getDescribed();
      int ticker = -1;
      for (int i = 0; i < description.getValues().length(); i++) {
        if (description.getValues().get(i).getId().equals("standin.ticker.second")) {
          ticker = i;
        }
      }
      assertThat(ticker).isNotNegative();
      Spotter.Values all = stream.next(Duration.ofSeconds(5)).event().getValues();
      assertThat(all.getComplete()).isTrue();
      assertThat(all.getValues()).hasSize(description.getValues().length());
      assertThat(stream.next(Duration.ofSeconds(5)).event().hasRuns()).isTrue();

      long connected = System.nanoTime();
      long last = connected;
      double longest = 0;
      int deltas = 0;
      int heartbeats = 0;
      int tickerChanges = 0;
      double complete = -1;
      while (System.nanoTime() - connected < 11_000_000_000L) {
        TestClient.Got got = stream.next(Duration.ofSeconds(2));
        longest = Math.max(longest, (got.nanos() - last) / 1e6);
        last = got.nanos();
        if (got.event().hasHeartbeat()) {
          heartbeats++;
        } else if (got.event().hasValues()) {
          Spotter.Values values = got.event().getValues();
          assertThat(values.getRevision()).isEqualTo(description.getRevision());
          if (values.getComplete()) {
            if (complete < 0) {
              complete = (got.nanos() - connected) / 1e9;
              assertThat(values.getValues()).hasSize(description.getValues().length());
            }
          } else {
            deltas++;
            // A delta has only what changed, by index.
            assertThat(values.getValues().length()).isLessThan(description.getValues().length());
            for (Spotter.FieldValue value : values.getValues()) {
              if (value.getIndex() == ticker) {
                tickerChanges++;
              }
            }
          }
        }
      }
      System.out.printf(
          "In 11 s: %d deltas (%d of the ticking second), %d heartbeats, every value again at %.1f"
              + " s, never silent past %.0f ms%n",
          deltas, tickerChanges, heartbeats, complete, longest);
      // Bounds a busy CI machine keeps to: about 11 changes, 10 s, and 100 ms at most, measured.
      assertThat(tickerChanges).isBetween(5, 14);
      assertThat(heartbeats).isPositive();
      assertThat(complete).isBetween(9.5, 12.0);
      assertThat(longest).isLessThan(1000);
    }
  }

  @Test
  void aPausedAgentGoesSilentSoTheManagerFindsItMissing() throws Exception {
    try (TestClient.Stream stream = client.stream("?heartbeat=100ms")) {
      for (int i = 0; i < 5; i++) {
        stream.next(Duration.ofSeconds(5));
      }
      coprocessor.run("systemctl", "kill", "--signal=SIGSTOP", "frc-spotter.service");
      long paused = System.nanoTime();
      try {
        // What was sent before the pause may still arrive; then nothing, for well past a second.
        while (stream.events.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) != null) {
          assertThat((System.nanoTime() - paused) / 1e6).isLessThan(1000);
        }
        long silentFrom = stream.last;
        Thread.sleep(1500);
        assertThat(stream.events).isEmpty();
        double silence = (System.nanoTime() - silentFrom) / 1e9;
        System.out.printf("Paused (SIGSTOP): silent for %.1f s, past the manager's 1 s%n", silence);
        assertThat(silence).isGreaterThan(1.0);
      } finally {
        coprocessor.run("systemctl", "kill", "--signal=SIGCONT", "frc-spotter.service");
      }
      // Woken, it speaks again on the same stream.
      assertThat(stream.next(Duration.ofSeconds(2)).event()).isNotNull();
    }
  }

  @Test
  void anAgentRestartedIsReconnectedToAndFullyResynced() throws Exception {
    Spotter.Description before;
    String challenge;
    try (TestClient.Stream stream = client.stream("")) {
      challenge = stream.challenge();
      before = stream.next(Duration.ofSeconds(5)).event().getDescribed();
      coprocessor.run("systemctl", "restart", "frc-spotter.service");
      for (int i = 0; i < 100 && !stream.ended; i++) {
        Thread.sleep(100);
      }
      assertThat(stream.ended).as("the stream ends with the agent").isTrue();
    }
    coprocessor.awaitAgent();
    try (TestClient.Stream stream = client.stream("")) {
      assertThat(stream.challenge()).isNotEmpty().isNotEqualTo(challenge);
      Spotter.Description after = stream.next(Duration.ofSeconds(5)).event().getDescribed();
      // The same board and packs: the same description, revision and all.
      assertThat(after.getRevision()).isEqualTo(before.getRevision());
      Spotter.Values values = stream.next(Duration.ofSeconds(5)).event().getValues();
      assertThat(values.getComplete()).isTrue();
      assertThat(values.getValues()).hasSize(after.getValues().length());
      assertThat(stream.next(Duration.ofSeconds(5)).event().hasRuns()).isTrue();
    }
  }
}
