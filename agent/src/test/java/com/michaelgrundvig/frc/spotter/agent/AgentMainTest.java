package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line: what it serves, on which port, and from which root. */
class AgentMainTest {
  @TempDir Path dir;

  @Test
  void optionsAreNamedAndAnythingElseShowsTheUsage() {
    assertThat(AgentMain.options(List.of("serve", "--port=5809", "--bind=127.0.0.1")))
        .isEqualTo(Map.of("port", "5809", "bind", "127.0.0.1"));
    assertThatThrownBy(() -> AgentMain.options(List.of("--port")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Usage:");
  }

  @Test
  void theControllerCanBeNamedAndThenAShutdownIsTakenFromItAlone() throws Exception {
    Fixture fixture = new Fixture(dir);
    // It's at 10.12.34.11, whose controller is 10.12.34.2; this test asks from 127.0.0.1.
    HttpRequest shutdown =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:0/v1/shutdown"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try (AgentServer named =
        AgentMain.serve(
            fixture.host,
            List.of("--port=0", "--bind=127.0.0.1", "--controller=127.0.0.1"),
            Runnable::run)) {
      assertThat(send(named, shutdown).statusCode()).isEqualTo(202);
    }
    assertThat(fixture.log()).contains("Shutdown asked for by 127.0.0.1: powering off");
    try (AgentServer derived =
        AgentMain.serve(fixture.host, List.of("--port=0", "--bind=127.0.0.1"), Runnable::run)) {
      HttpResponse<String> refused = send(derived, shutdown);
      assertThat(refused.statusCode()).isEqualTo(403);
      assertThat(refused.body()).contains("(10.12.34.2)");
    }
  }

  @Test
  void theControllerIsAnAddressNeverAName() {
    for (String bad : List.of("robot.local", "10.12.34", "10.12.34.256", "::1", "")) {
      assertThatThrownBy(() -> AgentMain.checkAddress(bad, "--controller"))
          .as(bad)
          .hasMessageContaining("--controller must be an IPv4 address");
    }
    AgentMain.checkAddress("10.12.34.2", "--controller");
    assertThatThrownBy(() -> AgentMain.serve(new Fixture(dir).host, List.of("--bind=any"), r -> {}))
        .hasMessageContaining("--bind must be an IPv4 address");
  }

  private static HttpResponse<String> send(AgentServer server, HttpRequest request)
      throws Exception {
    URI at = request.uri();
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + at.getPath()))
                .method(request.method(), HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void itServesOn5808UnlessItsOverridesOrCommandLineSay() throws Exception {
    Fixture fixture = new Fixture(dir);
    Configuration none = Configuration.read(fixture.host);
    assertThat(AgentMain.port(Map.of(), none)).isEqualTo(5808);
    assertThat(AgentMain.bind(Map.of(), none)).isEqualTo("0.0.0.0");
    fixture.config("{\"port\": 5809, \"bind\": \"127.0.0.1\"}");
    Configuration configured = Configuration.read(fixture.host);
    assertThat(AgentMain.port(Map.of(), configured)).isEqualTo(5809);
    assertThat(AgentMain.bind(Map.of(), configured)).isEqualTo("127.0.0.1");
    assertThat(AgentMain.port(Map.of("port", "5807"), configured)).isEqualTo(5807);
    assertThat(AgentMain.bind(Map.of("bind", "10.12.34.11"), configured)).isEqualTo("10.12.34.11");
    // Overrides that can't be read are ignored.
    fixture.config("{\"port\": 80}");
    assertThat(AgentMain.port(Map.of(), Configuration.read(fixture.host))).isEqualTo(5808);
    assertThat(AgentMain.options(List.of("--root=/x"))).containsEntry("root", "/x");
  }

  @Test
  void itServesItsStampWithNothingConfigured() throws Exception {
    Fixture fixture = new Fixture(dir);
    try (AgentServer server =
        AgentMain.serve(fixture.host, List.of("--port=0", "--bind=127.0.0.1"), Runnable::run)) {
      HttpResponse<String> stamp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + server.port() + "/v1/stamp"))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(stamp.statusCode()).isEqualTo(200);
      assertThat(stamp.body()).contains("\"hostname\":\"vision-front\"");
    }
  }

  @Test
  void whatItCouldntReadIsLoggedAsItStarts() throws Exception {
    Fixture fixture = new Fixture(dir);
    fixture.pack("mine", "pack: mine\n");
    fixture.owners.put("/etc/frc-spotter/packs/mine.yaml", new Host.Owner(1000, 0600));
    try (AgentServer server =
        AgentMain.serve(fixture.host, List.of("--port=0", "--bind=127.0.0.1"), Runnable::run)) {
      assertThat(server.port()).isPositive();
    }
    assertThat(fixture.log())
        .contains(
            "pack ignored: /etc/frc-spotter/packs/mine.yaml isn't root's (its owner is user 1000)");
  }
}
