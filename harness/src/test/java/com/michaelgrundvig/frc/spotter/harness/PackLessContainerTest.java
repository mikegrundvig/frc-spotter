package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * An agent with no packs at all is still a valid agent: it says which computer it is, sends
 * heartbeats, and offers power-off and reboot.
 */
@ContainerTest
class PackLessContainerTest {
  @Test
  void anAgentWithNoPacksDescribesItselfStreamsAndOffersItsTwoActions() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(TestImages.packLess(), network, 31, "bare")) {
      coprocessor.start();
      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
      Spotter.Description description = client.describe();
      assertThat(description.getIdentity().getHostname()).isEqualTo("bare");
      assertThat(description.getIdentity().getAddresses().get(0)).isEqualTo(Images.address(31));
      assertThat(description.getPacks()).isEmpty();
      assertThat(description.getValues()).isEmpty();
      assertThat(description.getLogs()).isEmpty();
      assertThat(description.getActions())
          .extracting(Spotter.ActionDeclaration::getId)
          .containsExactly("core.power-off", "core.reboot");
      assertThat(description.getProblems()).isEmpty();
      assertThat(description.getPushedPacks()).isEmpty();
      assertThat(description.getRefusesPushes()).isFalse();
      assertThat(description.getRequiresSignatures()).isFalse();
      Spotter.Values values = client.values();
      assertThat(values.getComplete()).isTrue();
      assertThat(values.getValues()).isEmpty();
      assertThat(values.getRevision()).isEqualTo(description.getRevision());

      try (TestClient.Stream stream = client.stream("?heartbeat=100ms")) {
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.next(Duration.ofSeconds(5)).event().getDescribed())
            .isEqualTo(description);
        assertThat(stream.next(Duration.ofSeconds(5)).event().getValues().getComplete()).isTrue();
        assertThat(stream.next(Duration.ofSeconds(5)).event().getRuns().getRuns()).isEmpty();
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
          TestClient.Got heartbeat = stream.next(Duration.ofSeconds(1));
          assertThat(heartbeat.event().hasHeartbeat()).isTrue();
          assertThat(heartbeat.event().getHeartbeat().getTimeNanos()).isPositive();
          times.add(heartbeat.nanos());
        }
        double mean = (times.get(times.size() - 1) - times.get(0)) / 1e6 / (times.size() - 1);
        System.out.printf(
            "A pack-less agent's heartbeats, asked every 100 ms: %.1f ms apart%n", mean);
        assertThat(mean).isBetween(80.0, 150.0);
      }
    }
  }
}
