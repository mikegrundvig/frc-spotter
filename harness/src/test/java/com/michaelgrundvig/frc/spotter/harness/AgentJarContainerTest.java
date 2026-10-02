package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.ClientSettings;
import com.michaelgrundvig.frc.spotter.settings.PhotonVisionDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * The agent's -all.jar on a stock Java 17 (Temurin's), installed with its install script, as on a
 * board that has a Java of its own: it starts under its unit, answers the robot's client, and runs
 * PhotonVision's pack helper on that same Java 17 (the agent names it in {@code FRC_AGENT_JAVA}),
 * which hashes a settings database as the robot's build does.
 */
@ContainerTest
class AgentJarContainerTest {
  @Test
  void theAllJarRunsOnJava17AndSoDoesThePacksHelper(@TempDir Path folder) throws Exception {
    Path database = PhotonVisionDatabase.configured(folder.resolve("db"));
    String expected;
    try (Connection connection = PhotonVisionDatabase.open(database)) {
      expected = SettingsDatabase.readText(connection).hash();
    }
    // Made once and kept: the image is known by its content, so the same file reuses it.
    Path copy = folder.resolve("photon.sqlite");
    Files.copy(database, copy);

    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(Images.agentOnJava17(copy), network, 21)) {
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
          new AgentConfig("vision-front", "", 5808, List.of("photonvision"), List.of(), List.of()));
      try (AgentClient client =
          new AgentClient(
              "vision-front",
              coprocessor.agentHost(),
              coprocessor.agentPort(),
              coprocessor.softwarePort(),
              ClientSettings.DEFAULTS,
              System::nanoTime)) {
        ProbeResult hash = client.runProbe("photonvision.settings-hash");
        assertThat(hash.status()).as("%s", hash).isEqualTo(ProbeResult.PASS);
        assertThat(hash.value()).isEqualTo(expected);
        System.out.printf(
            "The helper on Java 17 hashed the settings in %.0f ms: %s%n",
            hash.durationMillis(), hash.value());

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
