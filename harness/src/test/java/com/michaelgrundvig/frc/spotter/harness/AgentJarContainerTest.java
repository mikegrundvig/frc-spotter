package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * The agent's -all.jar on a stock Java 17 (Temurin's, on Ubuntu), installed with its install
 * script, as on a board that has a Java of its own: it starts under its unit with nothing
 * configured, reads the pack put in place, and describes itself with Ubuntu's os-release.
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

      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
      Spotter.Description description = client.describe();
      assertThat(description.getIdentity().getHostname()).isEqualTo("vision-java17");
      assertThat(description.getIdentity().getOsRelease())
          .anyMatch(e -> e.getKey().equals("ID") && e.getValue().equals("ubuntu"));
      assertThat(description.getValues().get(0).getId()).isEqualTo("standin.running");
      // The stand-in's software isn't on this computer: its pack's values say so.
      Spotter.FieldValue running = Spotter.FieldValue.newInstance();
      for (int i = 0; i < 50 && !running.hasText(); i++) {
        Thread.sleep(100);
        running = client.values().getValues().get(0);
      }
      assertThat(running.getText()).isEqualTo("inactive");
      assertThat(client.values().getValues().get(2).getUnavailable())
          .startsWith("connection refused (localhost:5800)");
    }
  }
}
