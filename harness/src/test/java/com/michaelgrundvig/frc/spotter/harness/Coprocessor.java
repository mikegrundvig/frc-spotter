package com.michaelgrundvig.frc.spotter.harness;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A coprocessor in a container, as close to a board as a container gets: systemd as its first
 * process, so the agent's unit, its sandboxing, polkit, and a power-off are real; its root
 * read-only, with {@code /data} a writable volume and the rest that's written in RAM, as on a
 * board's image; at a fixed address on the tests' network. Rootless Podman runs it as is, given
 * SYS_ADMIN (its user namespace's, for systemd's sandboxing) and no SELinux labels; Docker needs
 * privileged mode.
 */
final class Coprocessor extends GenericContainer<Coprocessor> {
  /** The agent's port. */
  static final int AGENT = 5808;

  /** The software's port: the stand-in's page. */
  static final int SOFTWARE = 5800;

  /** What's written while it runs, in RAM, as on a board's image (its fstab's tmpfs). */
  static final Map<String, String> TMPFS =
      Map.of(
          "/run", "rw,mode=755",
          "/run/lock", "rw",
          "/tmp", "rw,mode=1777",
          "/var/tmp", "rw,mode=1777",
          "/var/log", "rw,mode=755",
          "/var/lib/systemd", "rw,mode=755");

  /**
   * @param image the image, from {@link Images}
   * @param network the tests' network
   * @param last the last number of its address: 10.99.71.last
   */
  Coprocessor(String image, Network network, int last) {
    super(DockerImageName.parse(image));
    boolean podman = ContainerRuntime.podman();
    withNetwork(network);
    withExposedPorts(AGENT, SOFTWARE);
    withLabel(ContainerRuntime.LABEL, "true");
    withTmpFs(TMPFS);
    withCreateContainerCmdModifier(
        cmd -> {
          cmd.withIpv4Address(Images.address(last));
          HostConfig host = cmd.getHostConfig();
          if (host == null) {
            return;
          }
          host.withReadonlyRootfs(true);
          if (podman) {
            host.withCapAdd(Capability.SYS_ADMIN).withSecurityOpts(List.of("label=disable"));
          } else {
            host.withPrivileged(true);
          }
        });
    waitingFor(
        Wait.forHttp("/v1/stamp")
            .forPort(AGENT)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(2)));
  }

  /** The agent's address and port as the robot (this test) reaches it. */
  String agentHost() {
    return getHost();
  }

  int agentPort() {
    return getMappedPort(AGENT);
  }

  int softwarePort() {
    return getMappedPort(SOFTWARE);
  }

  /** Runs a command as root inside, and answers what it printed; fails if it fails. */
  String run(String... command) {
    try {
      Container.ExecResult result = execInContainer(command);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            String.join(" ", command)
                + " failed ("
                + result.getExitCode()
                + "): "
                + result.getStderr()
                + result.getStdout());
      }
      return result.getStdout();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Runs a command inside as the agent's own account, and answers how it went. */
  Container.ExecResult runAsAgent(String... command) {
    try {
      return execInContainerWithUser("frc-coprocessor-agent", command);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Writes a file inside, as root: on /data, or a tmpfs. */
  void write(String path, String text) {
    String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    run(
        "sh",
        "-c",
        "mkdir -p \"$(dirname '"
            + path
            + "')\" && echo "
            + encoded
            + " | base64 -d > '"
            + path
            + "'");
  }

  /**
   * Configures its agent as a team would on a board whose root is read-only ({@code
   * /data/frc-coprocessor/agent.json}), and restarts it to read it.
   */
  void configure(AgentConfig config) {
    write(AgentConfig.DATA_PATH, config.text());
    run("systemctl", "restart", "frc-coprocessor-agent.service");
    awaitAgent();
  }

  /** Waits until its agent answers again. */
  void awaitAgent() {
    for (int i = 0; i < 300; i++) {
      try {
        Container.ExecResult stamp =
            execInContainer(
                "curl", "-sf", "-o", "/dev/null", "http://127.0.0.1:" + AGENT + "/v1/stamp");
        if (stamp.getExitCode() == 0) {
          return;
        }
        Thread.sleep(100);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new IllegalStateException(
        "its agent didn't answer within 30 s: "
            + run("systemctl", "status", "frc-coprocessor-agent", "--no-pager"));
  }

  /**
   * The address its agent sees this test's connections come from: through the runtime's port
   * forwarding, that's the network's gateway on Docker, and its own address on rootless Podman. So
   * the robot's address on the tests' network is found, not assumed: a connection is held open and
   * the agent's side of it read from {@code /proc/net/tcp}.
   */
  String robotAddress() throws IOException {
    try (java.net.Socket socket = new java.net.Socket(agentHost(), agentPort())) {
      // A request begun, never finished: a port forwarder may dial the container only once data
      // comes, and the agent holds a request that hasn't finished for up to 4 s.
      socket.getOutputStream().write("GET /v1/stamp HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
      for (int i = 0; i < 30; i++) {
        // Java listens on a dual-stack socket, so an IPv4 connection shows in tcp6, IPv4-mapped.
        for (String line : run("cat", "/proc/net/tcp", "/proc/net/tcp6").split("\n")) {
          String[] fields = line.trim().split("\\s+");
          // local_address rem_address st: ESTABLISHED (01) on the agent's port (16B0).
          if (fields.length > 3 && fields[1].endsWith(":16B0") && fields[3].equals("01")) {
            String remote = fields[2].substring(0, fields[2].indexOf(':'));
            return address(remote.substring(remote.length() - 8));
          }
        }
        Thread.sleep(100);
      }
      throw new IOException("no connection to the agent's port showed in /proc/net/tcp");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  /**
   * An IPv4 address as /proc/net/tcp writes it (or the last word of an IPv4-mapped one in tcp6):
   * hex, its bytes reversed.
   */
  static String address(String hex) {
    long value = Long.parseLong(hex, 16);
    return (value & 0xff)
        + "."
        + ((value >> 8) & 0xff)
        + "."
        + ((value >> 16) & 0xff)
        + "."
        + ((value >> 24) & 0xff);
  }

  /** How much memory it uses now and has at most, its cgroup's, in MiB. */
  long[] memoryMb() {
    String current = run("cat", "/sys/fs/cgroup/memory.current").strip();
    String peak = run("sh", "-c", "cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0").strip();
    return new long[] {Long.parseLong(current) >> 20, Long.parseLong(peak) >> 20};
  }

  /** The agent's own memory: its unit's cgroup, in MiB, now. */
  long agentMemoryMb() {
    String value =
        run("systemctl", "show", "frc-coprocessor-agent", "--property=MemoryCurrent", "--value")
            .strip();
    return value.matches("\\d+") ? Long.parseLong(value) >> 20 : -1;
  }
}
