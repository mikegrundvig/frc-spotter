package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;

/**
 * Packs the robot pushes, end to end, as its manager does: a stream asked for with the robot's hash
 * is refused {@code 409} with the board's; the bundle is pushed; the agent restarts with it; and
 * the stream asked for again starts, the pushed pack running. A board whose owner refuses pushes
 * never answers {@code 409}, and refuses a push with {@code 403}.
 */
@ContainerTest
class PushContainerTest {
  @TempDir Path dir;

  /** The robot's packs, as its deploy folder has them: the pushed pack, its program executable. */
  private Path robotPacks() throws Exception {
    Path packs = dir.resolve("packs");
    Path team = packs.resolve("team");
    Files.createDirectories(team);
    Path source = TestImages.folder("pushed/team");
    for (String file : new String[] {"pack.yaml", "greet"}) {
      Files.copy(source.resolve(file), team.resolve(file));
    }
    Files.setPosixFilePermissions(
        team.resolve("pack.yaml"), PosixFilePermissions.fromString("rw-r--r--"));
    Files.setPosixFilePermissions(
        team.resolve("greet"), PosixFilePermissions.fromString("rwxr-xr-x"));
    return packs;
  }

  /** A bundle of the robot's packs, as the manager sends it: a {@code PackBundle}. */
  private static byte[] bundle(Path packs) throws Exception {
    return PackHash.bundle(packs).toByteArray();
  }

  @Test
  void aBoardWhosePacksDifferIsPushedRestartsAndMatches() throws Exception {
    Path packs = robotPacks();
    String hash = PackHash.of(packs);
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor = new Coprocessor(TestImages.agent(), network, 51, "vision-push")) {
      coprocessor.start();
      coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());

      try (TestClient.Stream refused = client.stream("?packs=" + hash)) {
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problem().getPushedPacks()).isEmpty();
        assertThat(refused.problem().getMessage()).contains("it has none");
      }
      long pushed = System.nanoTime();
      HttpResponse<byte[]> push = client.post(Protocol.PACKS, bundle(packs), null);
      assertThat(push.statusCode()).as("%s", push).isEqualTo(202);
      // The agent exits, systemd starts it again, and it reads the pushed pack.
      Spotter.Description description = null;
      for (int i = 0; i < 100; i++) {
        Thread.sleep(100);
        try {
          Spotter.Description now = client.describe();
          if (now.getPushedPacks().equals(hash)) {
            description = now;
            break;
          }
        } catch (java.io.IOException e) {
          // Restarting.
        }
      }
      Spotter.Description restarted =
          java.util.Objects.requireNonNull(description, "the pushed packs within 10 s");
      System.out.printf(
          "Pushed, restarted, and matching %.1f s after the push%n",
          (System.nanoTime() - pushed) / 1e9);
      assertThat(restarted.getPacks())
          .extracting(p -> p.getName() + " " + p.getPushed())
          .contains("team true", "standin false");
      try (TestClient.Stream stream = client.stream("?packs=" + hash)) {
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.next(Duration.ofSeconds(5)).event().getDescribed().getPushedPacks())
            .isEqualTo(hash);
      }
      // Its program kept executable, running as the agent's user.
      String greeting = "";
      for (int i = 0; i < 50 && greeting.isEmpty(); i++) {
        Spotter.FieldValue value = client.valuesById().get("team.greet.greeting");
        greeting = value == null ? "" : value.getText();
        Thread.sleep(100);
      }
      assertThat(greeting).isEqualTo("pushed, and executable");
      assertThat(coprocessor.run("stat", "-c", "%U %a", "/var/lib/frc-spotter/packs/team/greet"))
          .isEqualTo("frc-spotter 755\n");
      assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
          .contains("Packs pushed (" + hash + "): restarting to read them");
    }
  }

  @Test
  void aBoardThatRefusesPushesNeverAsksForThemAndRefusesThem() throws Exception {
    Path packs = robotPacks();
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(
                TestImages.agentWith("{\"acceptPushes\": false}"), network, 52, "vision-owned")) {
      coprocessor.start();
      coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
      assertThat(client.describe().getRefusesPushes()).isTrue();
      try (TestClient.Stream stream = client.stream("?packs=" + PackHash.of(packs))) {
        assertThat(stream.status()).isEqualTo(200);
      }
      HttpResponse<byte[]> refused = client.post(Protocol.PACKS, bundle(packs), null);
      assertThat(refused.statusCode()).isEqualTo(403);
      assertThat(TestClient.problem(refused).getMessage())
          .isEqualTo("this board refuses pushes: its agent.json says acceptPushes: false");
    }
  }
}
