package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.PowerDowner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/**
 * Soft-off end to end: the robot's client asks; the agent takes it only from its controller's
 * address (a second container on the same network is refused); it powers its container off through
 * polkit ({@code systemctl poweroff} stopping the software it watches first), and the client sees
 * both ports go. An agent that's been killed, or that hangs, never takes it: after the client's 30
 * s it's still not gone, and says why, for the robot to name it.
 *
 * <p>The robot here is this test, whose connections reach the agent through the runtime's port
 * forwarding, from an address the computer can't know: so each agent is told it with {@code
 * --controller}, as {@code /etc/frc-spotter/agent.json} could.
 */
@ContainerTest
class SoftOffContainerTest {
  @Test
  void theControllerAloneMayPowerItDownAndItsGoneFromBothPorts() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(TestImages.agent(), network, 11, "vision-front")) {
      coprocessor.start();
      String robot = coprocessor.robotAddress();
      coprocessor.restartAgent("--controller=" + robot);

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
      assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
          .contains("Refused a shutdown from " + Images.address(50) + ": not the robot controller");

      // The robot asks: taken, and it powers off, stopping the software first.
      try (AgentClient client = coprocessor.client("vision-front")) {
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
  void theRobotControllerOnItsOwnNetworkMayPowerItDownWithNothingConfigured() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(TestImages.agent(), network, 14, "vision-top");
        GenericContainer<?> robot =
            new GenericContainer<>(DockerImageName.parse(Images.base()))
                .withNetwork(network)
                .withLabel(ContainerRuntime.LABEL, "true")
                .withCreateContainerCmdModifier(
                    cmd -> cmd.withIpv4Address(Images.address(2)).withEntrypoint("sleep", "600"))) {
      coprocessor.start();
      robot.start();
      // At 10.99.71.14, its controller is 10.99.71.2: the robot container, asking directly.
      Container.ExecResult taken =
          robot.execInContainer(
              "curl",
              "-s",
              "-w",
              " %{http_code}",
              "-X",
              "POST",
              "http://" + Images.address(14) + ":5808/v1/shutdown");
      assertThat(taken.getStdout()).endsWith(" 202").contains("\"shuttingDown\":true");
      for (int i = 0; i < 300 && coprocessor.isRunning(); i++) {
        Thread.sleep(100);
      }
      assertThat(coprocessor.isRunning()).isFalse();
    }
  }

  @Test
  void aKilledOrHungAgentNeverTakesItAndSaysWhyAfterThirtySeconds() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor killed = new Coprocessor(TestImages.agent(), network, 12, "vision-back");
        Coprocessor hung = new Coprocessor(TestImages.agent(), network, 13, "vision-side")) {
      killed.start();
      hung.start();
      killed.restartAgent("--controller=" + killed.robotAddress());
      hung.restartAgent("--controller=" + hung.robotAddress());
      killed.run("systemctl", "stop", "frc-spotter.service");
      hung.run("systemctl", "kill", "--signal=SIGSTOP", "frc-spotter.service");

      List<AgentClient> clients = new ArrayList<>();
      try {
        for (Coprocessor coprocessor : List.of(killed, hung)) {
          clients.add(coprocessor.client(coprocessor == killed ? "vision-back" : "vision-side"));
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
