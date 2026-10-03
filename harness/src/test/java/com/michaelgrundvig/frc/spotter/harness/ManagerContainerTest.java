package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.manager.Alert;
import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.Connection;
import com.michaelgrundvig.frc.spotter.manager.Level;
import com.michaelgrundvig.frc.spotter.manager.Manager;
import com.michaelgrundvig.frc.spotter.manager.Recorder;
import com.michaelgrundvig.frc.spotter.manager.Robot;
import com.michaelgrundvig.frc.spotter.manager.Run;
import com.michaelgrundvig.frc.spotter.manager.Settings;
import com.michaelgrundvig.frc.spotter.manager.Value;
import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * The robot's manager, end to end, against the agent installed from its package in a container,
 * under systemd, on a board that requires signed writes: a signed action and a cancel; a board gone
 * silent, missing on the robot's clock, and back; a board on another major version of the protocol;
 * and the team's packs pushed, signed, the agent restarted by systemd with them. The key is made as
 * the tests run, and never committed.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagerContainerTest {
  /** A Spotter agent from the future: it answers every request with protocol 3.0, and a 404. */
  static final String FUTURE_AGENT =
      """
      #!/bin/sh
      while read -r line; do
        [ -z "$(printf '%s' "$line" | tr -d '\\r')" ] && break
      done
      printf 'HTTP/1.1 404 Not Found\\r\\nSpotter-Protocol: 3.0\\r\\nContent-Length: 0\\r\\nConnection: close\\r\\n\\r\\n'
      """;

  @TempDir Path dir;
  KeyPair team;
  String publicKey = "";
  Network network;
  Coprocessor coprocessor;

  @BeforeAll
  void aBoardThatRequiresSignatures() throws Exception {
    team = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    publicKey = Base64.getEncoder().encodeToString(team.getPublic().getEncoded());
    network = TestNetwork.create();
    coprocessor =
        new Coprocessor(
            TestImages.agentWith("{\"trustedKeys\": [\"" + publicKey + "\"]}"),
            network,
            71,
            "vision-managed");
    coprocessor.start();
    coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
  }

  @AfterAll
  void stop() {
    coprocessor.close();
    network.close();
  }

  /**
   * The robot's key pair, as the robot controller keeps it, as openssl writes it: the private key
   * PKCS#8 in PEM, {@code spotter.key}; its public key X.509 in PEM beside it, {@code spotter.pub}.
   */
  private Path key(Path folder) throws Exception {
    Files.writeString(folder.resolve("spotter.pub"), pem("PUBLIC KEY", team.getPublic()));
    Path file = folder.resolve("spotter.key");
    Files.writeString(file, pem("PRIVATE KEY", team.getPrivate()));
    return file;
  }

  private static String pem(String kind, java.security.Key key) {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
    return "-----BEGIN " + kind + "-----\n" + base64 + "\n-----END " + kind + "-----\n";
  }

  /** A disabled robot, off the field, on this computer's clock. */
  static Robot robot() {
    return new Robot(() -> false, () -> false, System::nanoTime);
  }

  /** Updates the manager every 20 ms until the condition holds, for up to 30 s. */
  static void await(Manager manager, String what, BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + 30_000_000_000L;
    while (true) {
      manager.update();
      if (condition.getAsBoolean()) {
        return;
      }
      if (System.nanoTime() > until) {
        throw new AssertionError(
            "not within 30 s: " + what + "; " + manager.boards() + ", " + manager.alerts());
      }
      Thread.sleep(20);
    }
  }

  private String agent() {
    return coprocessor.agentHost() + ":" + coprocessor.agentPort();
  }

  @Test
  void aSignedActionRunsAndAGoingOneIsCancelled() throws Exception {
    try (Manager manager =
        new Manager(
            robot(), List.of(agent()), Settings.DEFAULTS.withKey(key(dir)), Recorder.NONE)) {
      manager.start();
      Board board = manager.boards().get(0);
      await(manager, "described", () -> board.description().getActions().length() > 0);
      assertThat(board.description().getRequiresSignatures()).isTrue();
      assertThat(board.name()).isEqualTo("vision-managed");

      Run hello = board.run("standin.hello", "Ada").whenDone().get(30, TimeUnit.SECONDS);
      assertThat(hello.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
      assertThat(hello.log()).extracting(Spotter.LogEntry::getMessage).contains("greeting Ada");
      Value greeting = Objects.requireNonNull(hello.response("greeting"));
      assertThat(greeting.text()).isEqualTo("hello, Ada");
      assertThat(Objects.requireNonNull(hello.response("exit")).level()).isEqualTo(Level.OK);

      Run wait = board.run("standin.wait");
      for (int i = 0; i < 200 && wait.log().isEmpty(); i++) {
        Thread.sleep(50);
      }
      wait.cancel().get(30, TimeUnit.SECONDS);
      wait.whenDone().get(30, TimeUnit.SECONDS);
      assertThat(wait.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
      assertThat(wait.log())
          .extracting(Spotter.LogEntry::getMessage)
          .containsSubsequence("waiting", "stopping");
    }
  }

  @Test
  void withoutTheKeyReadsWorkAndWritesAreRefused() throws Exception {
    Path none = dir.resolve("none.key");
    try (Manager manager =
        new Manager(robot(), List.of(agent()), Settings.DEFAULTS.withKey(none), Recorder.NONE)) {
      manager.start();
      Board board = manager.boards().get(0);
      await(manager, "described", () -> board.description().getRequiresSignatures());
      assertThat(manager.alerts())
          .contains(
              new Alert(
                  Level.WARNING,
                  "",
                  "no Spotter key pair on this controller ("
                      + none
                      + ", "
                      + dir.resolve("spotter.pub")
                      + "): boards that require signatures will refuse its actions and pushes"));
      assertThat(board.values()).isNotEmpty();
      Run refused = board.run("standin.hello", "Ada").whenDone().get(30, TimeUnit.SECONDS);
      assertThat(refused.why())
          .isEqualTo(
              "it answered 401: this board requires signed writes, and this one isn't signed");
    }
  }

  @Test
  void aBoardGoneSilentIsMissingAndBackWhenItSpeaksAgain() throws Exception {
    try (Manager manager = new Manager(robot(), List.of(agent()))) {
      manager.start();
      Board board = manager.boards().get(0);
      await(manager, "connected", () -> board.connection() == Connection.CONNECTED);
      coprocessor.run("systemctl", "kill", "--signal=SIGSTOP", "frc-spotter.service");
      long paused = System.nanoTime();
      try {
        await(manager, "missing", () -> board.connection() == Connection.MISSING);
        double after = (System.nanoTime() - paused) / 1e9;
        System.out.printf("Paused (SIGSTOP): missing after %.1f s%n", after);
        // The missing threshold, 1 s after the last heartbeat, which came at most 250 ms before the
        // pause; and a busy machine's second at most.
        assertThat(after).isBetween(0.7, 2.5);
        assertThat(manager.alerts())
            .anyMatch(
                alert ->
                    alert.level() == Level.FAILING
                        && alert.text().startsWith("vision-managed is missing: "));
      } finally {
        coprocessor.run("systemctl", "kill", "--signal=SIGCONT", "frc-spotter.service");
      }
      await(manager, "connected again", () -> board.connection() == Connection.CONNECTED);
      assertThat(manager.alerts()).noneMatch(alert -> alert.text().contains("is missing"));
    }
  }

  @Test
  void aBoardOnAnotherMajorVersionIsNotUsedAndRaisesTheHighestAlert() throws Exception {
    // The future agent on 5800, the port the stand-in software had.
    coprocessor.run("systemctl", "stop", "vision.service");
    coprocessor.write("/run/future-agent", FUTURE_AGENT);
    coprocessor.run("chmod", "755", "/run/future-agent");
    coprocessor.run(
        "systemd-run",
        "--unit=future-agent",
        "/bin/busybox",
        "nc",
        "-ll",
        "-p",
        String.valueOf(Coprocessor.SOFTWARE),
        "-e",
        "/run/future-agent");
    String future = coprocessor.agentHost() + ":" + coprocessor.softwarePort();
    try (Manager manager = new Manager(robot(), List.of(future))) {
      manager.start();
      Board board = manager.boards().get(0);
      await(manager, "another protocol", () -> board.connection() == Connection.OTHER_PROTOCOL);
      assertThat(board.protocol()).isEqualTo("3.0");
      assertThat(board.values()).isEmpty();
      assertThat(manager.alerts())
          .containsExactly(
              new Alert(
                  Level.FAILING,
                  future,
                  future + ": it speaks Spotter protocol 3.0, the robot 2.0"));
      assertThat(board.run("standin.hello").state()).isEqualTo(Run.State.REFUSED);
    } finally {
      coprocessor.run("systemctl", "stop", "future-agent");
      coprocessor.run("systemctl", "start", "vision.service");
    }
  }

  @Test
  void theTeamsPacksArePushedSignedAndTheAgentRestartsWithThem(@TempDir Path deploy)
      throws Exception {
    Path packs = deploy.resolve("spotter-packs");
    Path folder = packs.resolve("team");
    Files.createDirectories(folder);
    Path source = TestImages.folder("pushed/team");
    for (String file : new String[] {"pack.yaml", "greet"}) {
      Files.copy(source.resolve(file), folder.resolve(file));
    }
    Files.setPosixFilePermissions(
        folder.resolve("greet"), PosixFilePermissions.fromString("rwxr-xr-x"));
    String hash = PackHash.of(packs);
    List<String> pushed = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (Manager manager =
        new Manager(
            robot(),
            List.of(agent()),
            Settings.DEFAULTS.withKey(key(dir)).withPacks(packs),
            new Recorder() {
              @Override
              public void pushed(Board board, String hashPushed, boolean forced) {
                pushed.add(hashPushed);
              }
            })) {
      manager.start();
      Board board = manager.boards().get(0);
      await(
          manager,
          "the pushed pack's value",
          () -> {
            Value greeting = board.value("team.greet.greeting");
            return greeting != null && greeting.available();
          });
      assertThat(board.description().getPushedPacks()).isEqualTo(hash);
      assertThat(pushed).containsExactly(hash);
      assertThat(coprocessor.run("ls", "/var/lib/frc-spotter/packs/team")).contains("greet");
      // Its packs are the robot's: no alert says they differ. (Its problems, the packs its image
      // holds that it mustn't trust, are a warning of their own.)
      assertThat(manager.alerts())
          .noneMatch(alert -> alert.text().startsWith("vision-managed's packs"));
    }
  }
}
