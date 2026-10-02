package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.client.AgentClient;
import com.michaelgrundvig.frc.spotter.client.ClientSettings;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Latency;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Network;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/**
 * The robot's client polling the agent through Toxiproxy, as over a robot's network that misbehaves:
 * latency under and past its timeouts (250 ms to connect, 500 ms to answer), dropped and reset
 * connections, a connection that stops passing data, and an agent that stalls. Each time the latest
 * answer goes stale and the coprocessor reads missing after 3 s, the robot's loop never waits (a
 * loop thread reads the client every 20 ms and times each read), and the client recovers once the
 * fault clears.
 *
 * <p>Toxiproxy accepts every connection itself, so a connect timeout can't be made here: a fault
 * shows as no answer in time. A computer that's gone from the network entirely (no SYN answered) is
 * the unit tests' and the bench's.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetworkFaultsContainerTest {
  /** Toxiproxy's image, pinned. */
  static final String TOXIPROXY = "ghcr.io/shopify/toxiproxy:2.12.0";

  Network network;
  Coprocessor coprocessor;
  ToxiproxyContainer toxiproxy;
  Proxy proxy;
  AgentClient client;
  Thread loop;
  final AtomicBoolean looping = new AtomicBoolean();
  final AtomicLong slowestReadNanos = new AtomicLong();

  @BeforeAll
  void aCoprocessorBehindToxiproxy() throws IOException {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(Images.agent(), network, 11);
    coprocessor.start();
    coprocessor.configure(
        new AgentConfig("vision-front", "", 5808, List.of("standin"), List.of(), List.of()));
    toxiproxy =
        new ToxiproxyContainer(TOXIPROXY)
            .withNetwork(network)
            .withLabel(ContainerRuntime.LABEL, "true")
            .withCreateContainerCmdModifier(cmd -> cmd.withIpv4Address(Images.address(3)));
    toxiproxy.start();
    proxy =
        new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
            .createProxy("agent", "0.0.0.0:8666", Images.address(11) + ":" + Coprocessor.AGENT);
    client =
        new AgentClient(
            "vision-front",
            toxiproxy.getHost(),
            toxiproxy.getMappedPort(8666),
            coprocessor.softwarePort(),
            ClientSettings.DEFAULTS,
            System::nanoTime);
  }

  @AfterAll
  void stop() {
    if (client != null) {
      client.close();
    }
    for (AutoCloseable container : new AutoCloseable[] {toxiproxy, coprocessor, network}) {
      try {
        if (container != null) {
          container.close();
        }
      } catch (Exception e) {
        // stopped anyway
      }
    }
  }

  /** The robot's loop: reads the client every 20 ms, as a robot's periodic does, timing each read. */
  @BeforeEach
  void theRobotsLoop() {
    slowestReadNanos.set(0);
    looping.set(true);
    loop =
        new Thread(
            () -> {
              while (looping.get()) {
                long started = System.nanoTime();
                client.latest();
                client.missing();
                client.ageSeconds();
                slowestReadNanos.accumulateAndGet(System.nanoTime() - started, Math::max);
                try {
                  Thread.sleep(20);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "robot loop");
    loop.start();
  }

  @AfterEach
  void theLoopNeverWaited() throws Exception {
    looping.set(false);
    loop.join();
    // A read of the latest answer takes microseconds; 5 ms would be a quarter of a loop.
    assertThat(slowestReadNanos.get()).isLessThan(5_000_000L);
    proxy.enable();
    for (var toxic : proxy.toxics().getAll()) {
      toxic.remove();
    }
    coprocessor.execInContainer("systemctl", "kill", "--signal=SIGCONT", "frc-coprocessor-agent.service");
    await(() -> !client.missing(), 10);
  }

  private static void await(BooleanSupplier condition, double seconds) throws InterruptedException {
    long end = System.nanoTime() + Math.round(seconds * 1e9);
    while (!condition.getAsBoolean() && System.nanoTime() < end) {
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).as("within %.1f s", seconds).isTrue();
  }

  /**
   * The fault reads missing after the 3 s stale time (not before 2 s of it), with why (a pattern);
   * and, once cleared, the client answers again within two polls.
   */
  private void goesMissingThenRecovers(Runnable clear, String why) throws Exception {
    long faulted = System.nanoTime();
    await(client::missing, 6);
    double after = (System.nanoTime() - faulted) / 1e9;
    assertThat(after).isGreaterThan(2);
    assertThat(client.latest().error()).containsPattern("(?i)" + why);
    System.out.printf("%s: missing after %.1f s (%s)%n", why, after, client.latest().error());
    long cleared = System.nanoTime();
    clear.run();
    await(() -> !client.missing() && client.latest().answered(), 4);
    System.out.printf("  answered again %.1f s after it cleared%n", (System.nanoTime() - cleared) / 1e9);
  }

  @Test
  void latencyUnderItsTimeoutIsAnswered() throws Exception {
    await(() -> client.latest().answered(), 5);
    Latency latency = proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 200);
    long polls = client.latest().polls();
    await(() -> client.latest().polls() >= polls + 3, 6);
    assertThat(client.latest().answered()).isTrue();
    assertThat(client.missing()).isFalse();
    latency.remove();
  }

  @Test
  void latencyPastItsTimeoutGoesMissingAndRecovers() throws Exception {
    await(() -> client.latest().answered(), 5);
    Latency latency = proxy.toxics().latency("slower", ToxicDirection.DOWNSTREAM, 700);
    goesMissingThenRecovers(
        () -> {
          try {
            latency.remove();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        },
        "timed out");
  }

  @Test
  void droppedConnectionsGoMissingAndRecover() throws Exception {
    await(() -> client.latest().answered(), 5);
    proxy.disable();
    goesMissingThenRecovers(
        () -> {
          try {
            proxy.enable();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        },
        // Refused, or reset by the runtime's port forwarding, which accepts for the closed proxy.
        "refused|reset");
  }

  @Test
  void resetConnectionsGoMissingAndRecover() throws Exception {
    await(() -> client.latest().answered(), 5);
    var reset = proxy.toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);
    goesMissingThenRecovers(
        () -> {
          try {
            reset.remove();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        },
        "");
  }

  @Test
  void aConnectionThatStopsPassingDataGoesMissingAndRecovers() throws Exception {
    await(() -> client.latest().answered(), 5);
    var stall = proxy.toxics().timeout("stall", ToxicDirection.DOWNSTREAM, 0);
    goesMissingThenRecovers(
        () -> {
          try {
            stall.remove();
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        },
        "timed out");
  }

  @Test
  void aStalledAgentGoesMissingAndRecovers() throws Exception {
    await(() -> client.latest().answered(), 5);
    coprocessor.run("systemctl", "kill", "--signal=SIGSTOP", "frc-coprocessor-agent.service");
    goesMissingThenRecovers(
        () ->
            coprocessor.run(
                "systemctl", "kill", "--signal=SIGCONT", "frc-coprocessor-agent.service"),
        "timed out");
  }
}
