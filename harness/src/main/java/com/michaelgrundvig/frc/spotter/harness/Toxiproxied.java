package com.michaelgrundvig.frc.spotter.harness;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import java.io.IOException;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * Toxiproxy between the robot (the test) and a coprocessor's agent, as over a robot's network that
 * misbehaves: the test adds latency, drops or resets connections, or stalls them through {@link
 * #proxy}, and asks the agent through it, at {@link #host} and {@link #port}.
 */
public final class Toxiproxied implements AutoCloseable {
  /** Toxiproxy's image, pinned. */
  public static final String IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";

  /** The port the proxy listens on, inside Toxiproxy's container. */
  static final int PORT = 8666;

  private final ToxiproxyContainer container;
  private final Proxy proxy;

  private Toxiproxied(ToxiproxyContainer container, Proxy proxy) {
    this.container = container;
    this.proxy = proxy;
  }

  /**
   * Starts Toxiproxy on the tests' network, proxying to a coprocessor's agent.
   *
   * @param network the tests' network
   * @param last the last number of Toxiproxy's address: 10.99.71.last
   * @param coprocessor the last number of the coprocessor's address
   */
  public static Toxiproxied start(Network network, int last, int coprocessor) throws IOException {
    ToxiproxyContainer container =
        new ToxiproxyContainer(IMAGE)
            .withNetwork(network)
            .withLabel(ContainerRuntime.LABEL, "true")
            .withCreateContainerCmdModifier(cmd -> cmd.withIpv4Address(Images.address(last)));
    container.start();
    try {
      Proxy proxy =
          new ToxiproxyClient(container.getHost(), container.getControlPort())
              .createProxy(
                  "agent",
                  "0.0.0.0:" + PORT,
                  Images.address(coprocessor) + ":" + Coprocessor.AGENT);
      return new Toxiproxied(container, proxy);
    } catch (IOException | RuntimeException e) {
      container.stop();
      throw e;
    }
  }

  /** The proxy, to add faults to and take them away. */
  public Proxy proxy() {
    return proxy;
  }

  /** Where the robot (the test) reaches the agent through the proxy: this host... */
  public String host() {
    return container.getHost();
  }

  /** ... at this port. */
  public int port() {
    return container.getMappedPort(PORT);
  }

  @Override
  public void close() {
    container.stop();
  }
}
