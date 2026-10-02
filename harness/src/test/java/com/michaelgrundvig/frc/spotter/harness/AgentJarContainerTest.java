package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * The agent's -all.jar on a stock Java 17 (Temurin's), installed with its install script, as on a
 * board that has a Java of its own: it starts under its unit, answers the robot's client, and runs
 * the example pack's Java helper on that same Java 17 (the agent names it in {@code
 * FRC_AGENT_JAVA}).
 */
@ContainerTest
class AgentJarContainerTest {
  @Test
  void theAllJarAndAPacksJavaHelperRunOnJava17() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(TestImages.agentOnJava17(), network, 21)) {
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

      coprocessor.configure(
          new AgentConfig("vision-front", "", 5808, List.of("java-helper"), List.of(), List.of()));
      try (AgentClient client = coprocessor.client("vision-front")) {
        for (int i = 0; i < 50 && client.latest().answer() == null; i++) {
          Thread.sleep(100);
        }
        Health health =
            Objects.requireNonNull(client.latest().answer(), () -> client.latest().error());
        assertThat(health.probes()).extracting(ProbeResult::id).contains("builtin.memory");
        assertThat(health.memory().totalMb()).isPositive();

        ProbeResult java = client.runProbe("java-helper.java");
        assertThat(java.status()).as("%s", java).isEqualTo(ProbeResult.PASS);
        assertThat(java.value()).isEqualTo("17");
        ProbeResult hashed = client.runProbe("java-helper.os-release");
        String sha256sum = coprocessor.run("sha256sum", "/etc/os-release").substring(0, 64);
        assertThat(hashed.value()).as("%s", hashed).isEqualTo(sha256sum);
        System.out.printf(
            "The example pack's helper on Java %s: a JVM started and a file hashed in %.0f ms%n",
            java.value(), hashed.durationMillis());
      }
      System.out.printf("The -all.jar on Java 17: its unit %d MiB%n", coprocessor.agentMemoryMb());
    }
  }
}
