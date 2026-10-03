package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

/**
 * Who may change a board: the robot controller's address alone, with nothing configured; with
 * signatures, only writes signed by a trusted key over the current connection's challenge, each
 * counter once; and nobody while its settings can't be read.
 */
@ContainerTest
class WritesContainerTest {
  private static GenericContainer<?> computer(Network network, int last) {
    return new GenericContainer<>(DockerImageName.parse(Images.base()))
        .withNetwork(network)
        .withLabel(ContainerRuntime.LABEL, "true")
        .withCreateContainerCmdModifier(
            cmd -> cmd.withIpv4Address(Images.address(last)).withEntrypoint("sleep", "600"));
  }

  /** A write from another computer on the network: its status and answer, by curl. */
  private static String write(GenericContainer<?> from, String method, String url)
      throws Exception {
    Container.ExecResult result =
        from.execInContainer(
            "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", method, url);
    return result.getStdout();
  }

  @Test
  void writesAreTakenFromTheRobotControllerAloneWithNothingConfigured() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(TestImages.agent(), network, 61, "vision-writes");
        GenericContainer<?> controller = computer(network, 2);
        GenericContainer<?> stranger = computer(network, 50)) {
      coprocessor.start();
      controller.start();
      stranger.start();
      String agent = "http://" + Images.address(61) + ":5808";
      // At 10.99.71.61, its controller is 10.99.71.2; .50 is anyone else.
      assertThat(write(stranger, "POST", agent + "/v2/actions/standin.fail")).isEqualTo("403");
      assertThat(write(stranger, "DELETE", agent + "/v2/runs/0123456789abcdef")).isEqualTo("403");
      assertThat(write(stranger, "POST", agent + "/v2/packs")).isEqualTo("403");
      // A controller only worked out from the board's address (on any 10.x network, a school's
      // included) runs the built-in actions alone: a pack's action, a cancel, a push are refused.
      assertThat(write(controller, "POST", agent + "/v2/actions/standin.fail")).isEqualTo("403");
      assertThat(write(controller, "DELETE", agent + "/v2/runs/0123456789abcdef")).isEqualTo("403");
      assertThat(write(controller, "POST", agent + "/v2/packs")).isEqualTo("403");
      Container.ExecResult why =
          controller.execInContainer(
              "curl",
              "-s",
              "-H",
              "Accept: application/json",
              "-X",
              "POST",
              agent + "/v2/actions/standin.fail");
      assertThat(why.getStdout())
          .contains(
              "this board takes only its built-in actions from a robot controller it works out"
                  + " from its own address (10.99.71.2)");
      // Reading is open to anyone.
      Container.ExecResult read =
          stranger.execInContainer(
              "curl", "-s", "-H", "Accept: application/json", agent + "/v2/describe");
      assertThat(read.getStdout()).contains("\"hostname\": \"vision-writes\"");
      assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
          .contains("from " + Images.address(50) + ": not the robot controller");
    }
  }

  @Test
  void aBoardWhoseSettingsNameItsTeamTakesEveryWriteFromItsController() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(
                TestImages.agentWith("{\"team\": " + Images.TEAM + "}"),
                network,
                64,
                "vision-team");
        GenericContainer<?> controller = computer(network, 2);
        GenericContainer<?> stranger = computer(network, 50)) {
      coprocessor.start();
      controller.start();
      stranger.start();
      String agent = "http://" + Images.address(64) + ":5808";
      assertThat(write(stranger, "POST", agent + "/v2/actions/standin.fail")).isEqualTo("403");
      assertThat(write(controller, "POST", agent + "/v2/actions/standin.fail")).isEqualTo("202");
      assertThat(write(controller, "DELETE", agent + "/v2/runs/0123456789abcdef")).isEqualTo("404");
      // A push is taken, and read: this one's body isn't a bundle.
      Container.ExecResult push =
          controller.execInContainer(
              "curl",
              "-s",
              "-o",
              "/dev/null",
              "-w",
              "%{http_code}",
              "--data-binary",
              "zip",
              agent + "/v2/packs");
      assertThat(push.getStdout()).isEqualTo("400");
    }
  }

  @Test
  void aBoardRequiringSignaturesTakesOnlyWritesSignedOverItsCurrentChallenge() throws Exception {
    TestClient.Signed team = new TestClient.Signed();
    TestClient.Signed other = new TestClient.Signed();
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(
                TestImages.agentWith("{\"trustedKeys\": [\"" + team.publicKey + "\"]}"),
                network,
                62,
                "vision-signed")) {
      coprocessor.start();
      coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
      assertThat(client.describe().getRequiresSignatures()).isTrue();
      String path = Protocol.ACTIONS + "standin.fail";
      byte[] none = new byte[0];
      String stale;
      try (TestClient.Stream stream = client.stream("")) {
        stale = stream.challenge();
        team.over(stale);
        other.over(stale);
        HttpResponse<byte[]> missing = client.post(path, none, null);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(TestClient.problem(missing).getMessage())
            .isEqualTo("this board requires signed writes, and this one isn't signed");
        String header = team.header("POST", path, none);
        HttpResponse<byte[]> valid = client.send(postWith(client, path, header));
        assertThat(valid.statusCode()).isEqualTo(202);
        HttpResponse<byte[]> wrongKey = client.post(path, none, other);
        assertThat(wrongKey.statusCode()).isEqualTo(401);
        assertThat(TestClient.problem(wrongKey).getMessage())
            .isEqualTo("key " + other.keyId + " isn't one this board trusts");
        HttpResponse<byte[]> replayed = client.send(postWith(client, path, header));
        assertThat(replayed.statusCode()).isEqualTo(401);
        assertThat(TestClient.problem(replayed).getMessage())
            .startsWith("counter 1 was used already");
      }
      // A reconnect's new challenge: the old one is useless, once the agent has found its
      // connection gone, at its next heartbeat (250 ms).
      Thread.sleep(1000);
      try (TestClient.Stream stream = client.stream("")) {
        assertThat(stream.challenge()).isNotEqualTo(stale);
        HttpResponse<byte[]> old = client.post(path, none, team.over(stale));
        assertThat(old.statusCode()).isEqualTo(401);
        assertThat(TestClient.problem(old).getMessage()).startsWith("the signature doesn't match");
        assertThat(client.post(path, none, team.over(stream.challenge())).statusCode())
            .isIn(202, 409);
      }
      assertThat(coprocessor.run("journalctl", "-u", "frc-spotter", "--no-pager"))
          .contains("Refused a write, unsigned or wrongly signed");
    }
  }

  private static java.net.http.HttpRequest.Builder postWith(
      TestClient client, String path, String header) {
    return java.net.http.HttpRequest.newBuilder(java.net.URI.create(client.base() + path))
        .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
        .header(Protocol.SIGNATURE, header);
  }

  @Test
  void settingsThatCantBeReadRefuseEveryWrite() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(
                TestImages.agentWith("{\"acceptPushes\": fals"), network, 63, "vision-broken")) {
      coprocessor.start();
      coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
      for (HttpResponse<byte[]> refused :
          List.of(
              client.post(Protocol.ACTIONS + "standin.fail", new byte[0], null),
              client.delete(Protocol.RUNS + "0123456789abcdef", null),
              client.post(Protocol.PACKS, "zip".getBytes(StandardCharsets.UTF_8), null))) {
        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(TestClient.problem(refused).getMessage())
            .startsWith("every write is refused while /etc/frc-spotter/agent.json can't be read");
      }
      assertThat(client.describe().getProblems().get(0))
          .startsWith("/etc/frc-spotter/agent.json: can't be read, so every write is refused");
      assertThat(client.values().getComplete()).isTrue();
    }
  }
}
