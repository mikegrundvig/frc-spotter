package com.michaelgrundvig.frc.spotter.harness;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
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
 * board's image; with its hostname, at a fixed address on the tests' network. Rootless Podman runs
 * it as is, given SYS_ADMIN (its user namespace's, for systemd's sandboxing) and no SELinux labels;
 * Docker needs privileged mode.
 */
public final class Coprocessor extends GenericContainer<Coprocessor> {
  /** The agent's port. */
  public static final int AGENT = Protocol.PORT;

  /** The software's port: a vision program's page, 5800, as the stand-in's is. */
  public static final int SOFTWARE = 5800;

  /**
   * What's written while it runs, in RAM, as on a board's image (its fstab's tmpfs, and systemd's
   * /run). Where a board lets programs run from them, so does this: a Java program's native library
   * (sqlite-jdbc's, say) is unpacked into /tmp or a unit's runtime directory and loaded from there,
   * and Docker's tmpfs is noexec unless told otherwise. {@code /var/lib/frc-spotter} is where the
   * agent unpacks pushed packs, which a board with a read-only root makes writable; the agent's
   * tmpfiles.d entry gives it to the agent's account at boot.
   */
  public static final Map<String, String> TMPFS =
      Map.of(
          "/run", "rw,exec,mode=755",
          "/run/lock", "rw",
          "/tmp", "rw,exec,mode=1777",
          "/var/tmp", "rw,exec,mode=1777",
          "/var/log", "rw,mode=755",
          "/var/lib/systemd", "rw,mode=755",
          "/var/lib/frc-spotter", "rw,exec,mode=755");

  /**
   * A coprocessor named {@code coprocessor-<last>}.
   *
   * @param image the image, from {@link Images}
   * @param network the tests' network
   * @param last the last number of its address: 10.99.71.last
   */
  public Coprocessor(String image, Network network, int last) {
    this(image, network, last, "coprocessor-" + last);
  }

  /**
   * @param image the image, from {@link Images}
   * @param network the tests' network
   * @param last the last number of its address: 10.99.71.last
   * @param hostname its hostname, which is its agent's name for it
   */
  public Coprocessor(String image, Network network, int last, String hostname) {
    super(DockerImageName.parse(image));
    boolean podman = ContainerRuntime.podman();
    withNetwork(network);
    withExposedPorts(AGENT, SOFTWARE);
    withLabel(ContainerRuntime.LABEL, "true");
    withTmpFs(TMPFS);
    withCreateContainerCmdModifier(
        cmd -> {
          cmd.withIpv4Address(Images.address(last)).withHostName(hostname);
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
        Wait.forHttp(Protocol.DESCRIBE)
            .forPort(AGENT)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(2)));
  }

  /** The agent's address and port as the robot (this test) reaches it. */
  public String agentHost() {
    return getHost();
  }

  public int agentPort() {
    return getMappedPort(AGENT);
  }

  public int softwarePort() {
    return getMappedPort(SOFTWARE);
  }

  /** Runs a command as root inside, and answers what it printed; fails if it fails. */
  public String run(String... command) {
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
  public Container.ExecResult runAsAgent(String... command) {
    try {
      return execInContainerWithUser("frc-spotter", command);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Writes a file inside, as root: on /data, or a tmpfs. */
  public void write(String path, String text) {
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
   * Restarts its agent with these command-line options, by a drop-in in {@code
   * /run/systemd/system}, which the read-only root leaves writable and a reboot forgets: {@code
   * --controller=<address>} for a test whose robot reaches the agent through the runtime's port
   * forwarding, from an address the computer can't know.
   */
  public void restartAgent(String... options) {
    write(
        "/run/systemd/system/frc-spotter.service.d/harness.conf",
        "[Service]\nExecStart=\nExecStart=/usr/lib/frc-spotter/bin/frc-spotter "
            + String.join(" ", options)
            + "\n");
    run("systemctl", "daemon-reload");
    run("systemctl", "restart", "frc-spotter.service");
    awaitAgent();
  }

  /** Waits until its agent answers again. */
  public void awaitAgent() {
    for (int i = 0; i < 300; i++) {
      try {
        Container.ExecResult stamp =
            execInContainer(
                "curl", "-sf", "-o", "/dev/null", "http://127.0.0.1:" + AGENT + Protocol.DESCRIBE);
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
            + run("systemctl", "status", "frc-spotter", "--no-pager"));
  }

  /**
   * The address its agent sees this test's connections come from: through the runtime's port
   * forwarding, that's the network's gateway on Docker, and its own address on rootless Podman. So
   * the robot's address on the tests' network is found, not assumed: a connection is held open and
   * the agent's side of it read from {@code /proc/net/tcp}.
   */
  public String robotAddress() throws IOException {
    try (java.net.Socket socket = new java.net.Socket(agentHost(), agentPort())) {
      // A request begun, never finished: a port forwarder may dial the container only once data
      // comes, and the agent holds a request that hasn't finished for up to 4 s.
      socket
          .getOutputStream()
          .write(("GET " + Protocol.DESCRIBE + " HTTP/1.1\r\n").getBytes(StandardCharsets.UTF_8));
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
  public static String address(String hex) {
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
  public long[] memoryMb() {
    String current = run("cat", "/sys/fs/cgroup/memory.current").strip();
    String peak = run("sh", "-c", "cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0").strip();
    return new long[] {Long.parseLong(current) >> 20, Long.parseLong(peak) >> 20};
  }

  /** The agent's own memory: its unit's cgroup, in MiB, now. */
  public long agentMemoryMb() {
    String value =
        run("systemctl", "show", "frc-spotter", "--property=MemoryCurrent", "--value").strip();
    return value.matches("\\d+") ? Long.parseLong(value) >> 20 : -1;
  }
}
