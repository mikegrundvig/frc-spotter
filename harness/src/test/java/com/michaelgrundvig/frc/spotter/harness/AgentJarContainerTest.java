package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * The agent's -all.jar on a stock Java 17 (Temurin's, on Ubuntu), installed with its install
 * script, as on a board that has a Java of its own: it starts under its unit with nothing
 * configured, reads the pack copied in, and answers the robot's client with Ubuntu's os-release.
 */
@ContainerTest
class AgentJarContainerTest {
  @Test
  void theAllJarRunsOnJava17WithItsPacks() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(TestImages.agentOnJava17(), network, 21, "vision-java17")) {
      coprocessor.start();
      String version = coprocessor.run("sh", "-c", "java -version 2>&1 | head -n 1").strip();
      System.out.println("The board's Java: " + version);
      assertThat(version).contains("\"17.");
      // Its unit runs the jar on that Java: the package brought no runtime of its own.
      String pid =
          coprocessor
              .run("systemctl", "show", "frc-spotter", "--property=MainPID", "--value")
              .strip();
      assertThat(coprocessor.run("sh", "-c", "tr '\\0' ' ' < /proc/" + pid + "/cmdline"))
          .startsWith("/usr/local/bin/java ")
          .contains("-jar /usr/lib/frc-spotter/frc-spotter.jar");

      try (AgentClient client = coprocessor.client("vision-front")) {
        for (int i = 0; i < 50 && client.latest().answer() == null; i++) {
          Thread.sleep(100);
        }
        Health health =
            Objects.requireNonNull(client.latest().answer(), () -> client.latest().error());
        assertThat(health.stamp().hostname()).isEqualTo("vision-java17");
        assertThat(health.stamp().osRelease("ID")).isEqualTo("ubuntu");
        assertThat(health.memory().totalMb()).isPositive();
        assertThat(health.probes()).extracting(ProbeResult::id).contains("vision.unit");
        // The stand-in's software isn't on this computer: its pack says so.
        ProbeResult unit = client.runProbe("vision.unit");
        assertThat(unit.status()).as("%s", unit).isEqualTo(ProbeResult.FAIL);
        assertThat(unit.detail()).contains("isn't installed");
      }
      System.out.printf("The -all.jar on Java 17: its unit %d MiB%n", coprocessor.agentMemoryMb());
    }
  }
}
