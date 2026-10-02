package com.michaelgrundvig.frc.spotter.harness;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Network.Ipam;
import java.util.List;
import java.util.Map;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;

/**
 * The tests' robot network: 10.99.71.0/24, team 9971's addresses, where each coprocessor has its
 * fixed address as on a robot. Labeled, so what a crashed run left (Podman runs without
 * Testcontainers' cleanup container) is removed before a new one is made.
 */
final class TestNetwork {
  private TestNetwork() {}

  /** A fresh network, after removing any a crashed run left, and their containers. */
  static Network create() {
    ContainerRuntime.removeLeftovers();
    DockerClient client = DockerClientFactory.instance().client();
    for (com.github.dockerjava.api.model.Network left :
        client.listNetworksCmd().withFilter("label", List.of(ContainerRuntime.LABEL)).exec()) {
      client.removeNetworkCmd(left.getId()).exec();
    }
    return Network.builder()
        .createNetworkCmdModifier(
            cmd ->
                cmd.withLabels(Map.of(ContainerRuntime.LABEL, "true"))
                    .withIpam(
                        new Ipam()
                            .withConfig(
                                new Ipam.Config()
                                    .withSubnet(Images.address(0) + "/24")
                                    .withGateway(Images.address(1)))))
        .build();
  }
}
