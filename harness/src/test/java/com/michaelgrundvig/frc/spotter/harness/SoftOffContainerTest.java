package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.ClientSettings;
import com.michaelgrundvig.frc.spotter.client.PowerDowner;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/**
 * Soft-off end to end: the robot's client asks; the agent takes it only from its controller's
 * address (a second container on the same network is refused); it runs its pack's step (stopping
 * the software it watches), powers its container off through polkit, and the client sees both ports
 * go. An agent that's been killed, or that hangs, never takes it: after the client's 30 s it's
 * still not gone, and says why, for the robot to name it.
 */
@ContainerTest
class SoftOffContainerTest {
  /** The configuration, with its controller: the address the robot's connections come from. */
  static AgentConfig controlledBy(String name, String controller) {
    return new AgentConfig(name, controller, 5808, List.of("standin"), List.of(), List.of());
  }

  @Test
  void theControllerAloneMayPowerItDownAndItsGoneFromBothPorts() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(Images.agent(), network, 11)) {
      coprocessor.start();
      String robot = coprocessor.robotAddress();
      coprocessor.configure(controlledBy("vision-front", robot));

      // Another computer on the robot's network, at .50: refused, and nothing happens.
      try (GenericContainer<?> stranger =
          new GenericContainer<>(DockerImageName.parse(Images.base()))
              .withNetwork(network)
              .withLabel(ContainerRuntime.LABEL, "true")
              .withCreateContainerCmdModifier(
                  cmd -> cmd.withIpv4Address(Images.address(50)).withEntrypoint("sleep", "600"))) {
        stranger.start();
        Container.ExecResult refused =
            stranger.execInContainer(
                "curl",
                "-s",
                "-w",
                " %{http_code}",
                "-X",
                "POST",
                "http://" + Images.address(11) + ":5808/v1/shutdown");
        assertThat(refused.getStdout())
            .endsWith(" 403")
            .contains("only the robot controller (" + robot + ") may shut this computer down");
      }
      assertThat(coprocessor.run("systemctl", "is-active", "vision.service").strip())
          .isEqualTo("active");
      assertThat(coprocessor.run("journalctl", "-u", "frc-coprocessor-agent", "--no-pager"))
          .contains("Refused a shutdown from " + Images.address(50) + ": not the robot controller");

      // The robot asks: taken, its step runs, and it powers off.
      try (AgentClient client =
          new AgentClient(
              "vision-front",
              coprocessor.agentHost(),
              coprocessor.agentPort(),
              coprocessor.softwarePort(),
              ClientSettings.DEFAULTS,
              System::nanoTime)) {
        long asked = System.nanoTime();
        client.powerDown();
        PowerDowner.State state = client.powerDownState();
        for (int i = 0; i < 600 && !state.gone(); i++) {
          Thread.sleep(100);
          state = client.powerDownState();
        }
        double seconds = (System.nanoTime() - asked) / 1e9;
        System.out.printf(
            "Powered down: gone from both ports %.1f s after it was asked%n", seconds);
        assertThat(state.accepted()).isTrue();
        assertThat(state.answer()).isEqualTo("accepted");
        assertThat(state.gone()).as("%s", state).isTrue();
      }
      // systemd powered the container off: its first process ended, cleanly.
      for (int i = 0; i < 100 && coprocessor.isRunning(); i++) {
        Thread.sleep(100);
      }
      assertThat(coprocessor.isRunning()).isFalse();
      Long exit = coprocessor.getCurrentContainerInfo().getState().getExitCodeLong();
      System.out.printf("The container exited with %s%n", exit);
    }
  }

  @Test
  void aKilledOrHungAgentNeverTakesItAndSaysWhyAfterThirtySeconds() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor killed = new Coprocessor(Images.agent(), network, 12);
        Coprocessor hung = new Coprocessor(Images.agent(), network, 13)) {
      killed.start();
      hung.start();
      killed.configure(controlledBy("vision-back", killed.robotAddress()));
      hung.configure(controlledBy("vision-side", hung.robotAddress()));
      killed.run("systemctl", "stop", "frc-coprocessor-agent.service");
      hung.run("systemctl", "kill", "--signal=SIGSTOP", "frc-coprocessor-agent.service");

      List<AgentClient> clients = new ArrayList<>();
      try {
        for (Coprocessor coprocessor : List.of(killed, hung)) {
          clients.add(
              new AgentClient(
                  coprocessor == killed ? "vision-back" : "vision-side",
                  coprocessor.agentHost(),
                  coprocessor.agentPort(),
                  coprocessor.softwarePort(),
                  ClientSettings.DEFAULTS,
                  System::nanoTime));
        }
        long asked = System.nanoTime();
        clients.forEach(AgentClient::powerDown);
        // The client asks each poll for 30 s, then gives up on one that never took it.
        Thread.sleep(31_000);
        List<String> notGone = new ArrayList<>();
        for (AgentClient client : clients) {
          PowerDowner.State state = client.powerDownState();
          assertThat(state.accepted()).isFalse();
          assertThat(state.gone()).isFalse();
          assertThat(state.softwareOpen()).isTrue();
          notGone.add(client.name() + " (couldn't be reached: " + state.answer() + ")");
        }
        String alert =
            String.format(
                "Not powered down %.0f s after being asked: %s",
                (System.nanoTime() - asked) / 1e9, String.join(", ", notGone));
        System.out.println(alert);
        assertThat(alert)
            .contains("vision-back (couldn't be reached: Connection re")
            .contains("vision-side (couldn't be reached: Read timed out)");
      } finally {
        clients.forEach(AgentClient::close);
      }
    }
  }
}
