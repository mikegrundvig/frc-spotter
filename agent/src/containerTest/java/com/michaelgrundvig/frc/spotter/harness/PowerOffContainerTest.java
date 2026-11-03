package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/**
 * The built-in power-off, from the robot controller on the board's own network with nothing
 * configured: taken, and the board powers off cleanly (systemd stopping its services first),
 * through the package's polkit rule.
 */
@ContainerTest
class PowerOffContainerTest {
  @Test
  void theControllerPowersTheBoardOff() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(TestImages.agent(), network, 64, "vision-off");
        GenericContainer<?> robot =
            new GenericContainer<>(DockerImageName.parse(Images.base()))
                .withNetwork(network)
                .withLabel(ContainerRuntime.LABEL, "true")
                .withCreateContainerCmdModifier(
                    cmd -> cmd.withIpv4Address(Images.address(2)).withEntrypoint("sleep", "600"))) {
      coprocessor.start();
      robot.start();
      long asked = System.nanoTime();
      Container.ExecResult taken =
          robot.execInContainer(
              "curl",
              "-s",
              "-o",
              "/dev/null",
              "-w",
              "%{http_code}",
              "-X",
              "POST",
              "http://" + Images.address(64) + ":5808/v2/actions/core.power-off");
      assertThat(taken.getStdout()).isEqualTo("202");
      for (int i = 0; i < 300 && coprocessor.isRunning(); i++) {
        Thread.sleep(100);
      }
      assertThat(coprocessor.isRunning()).isFalse();
      System.out.printf(
          "Powered off %.1f s after core.power-off was asked%n", (System.nanoTime() - asked) / 1e9);
    }
  }
}
