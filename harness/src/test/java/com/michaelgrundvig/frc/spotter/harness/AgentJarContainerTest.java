package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.ClientSettings;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * The agent's -all.jar on a stock Java 17 (Temurin's), installed with its install script, as on a
 * board that has a Java of its own: it starts under its unit and answers the robot's client.
 */
@ContainerTest
class AgentJarContainerTest {
  @Test
  void theAllJarRunsOnJava17() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(Images.agentOnJava17(), network, 21)) {
      coprocessor.start();
      String version = coprocessor.run("sh", "-c", "java -version 2>&1 | head -n 1").strip();
      System.out.println("The board's Java: " + version);
      assertThat(version).contains("\"17.");
      // Its unit runs the jar on that Java: the package brought no runtime of its own.
      String pid =
          coprocessor
              .run("systemctl", "show", "frc-coprocessor-agent", "--property=MainPID", "--value")
              .strip();
      assertThat(coprocessor.run("sh", "-c", "tr '\\0' ' ' < /proc/" + pid + "/cmdline"))
          .startsWith("/usr/local/bin/java ")
          .contains("-jar /usr/lib/frc-coprocessor-agent/frc-coprocessor-agent.jar");

      try (AgentClient client =
          new AgentClient(
              "vision-front",
              coprocessor.agentHost(),
              coprocessor.agentPort(),
              coprocessor.softwarePort(),
              ClientSettings.DEFAULTS,
              System::nanoTime)) {
        for (int i = 0; i < 50 && client.latest().answer() == null; i++) {
          Thread.sleep(100);
        }
        Health health =
            Objects.requireNonNull(client.latest().answer(), () -> client.latest().error());
        assertThat(health.probes()).extracting(ProbeResult::id).contains("builtin.memory");
        assertThat(health.memory().totalMb()).isPositive();
      }
      System.out.printf("The -all.jar on Java 17: its unit %d MiB%n", coprocessor.agentMemoryMb());
    }
  }
}
