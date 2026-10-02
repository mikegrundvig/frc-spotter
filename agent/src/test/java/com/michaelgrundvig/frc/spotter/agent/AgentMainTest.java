package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.table.AgentConfig;
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
    // The stamp's team is 1234, whose controller is 10.12.34.2; this test asks from 127.0.0.1.
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
      assertThatThrownBy(() -> AgentMain.checkAddress(bad))
          .as(bad)
          .hasMessageContaining("--controller must be an IPv4 address");
    }
    AgentMain.checkAddress("10.12.34.2");
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
  void itServesOnTheConfigurationsPortElseTheStamps() throws Exception {
    Fixture fixture = new Fixture(dir);
    fixture.config(new AgentConfig("vision-front", "", 5809, List.of(), List.of(), List.of()));
    Configuration configured = Configuration.read(fixture.host);
    assertThat(AgentMain.port(Map.of(), configured, 5808)).isEqualTo(5809);
    assertThat(AgentMain.port(Map.of("port", "5807"), configured, 5808)).isEqualTo(5807);
    fixture.write(AgentConfig.PATH, "{\"port\": 80}");
    assertThat(AgentMain.port(Map.of(), Configuration.read(fixture.host), 5808)).isEqualTo(5808);
    fixture.delete(AgentConfig.PATH);
    assertThat(AgentMain.port(Map.of(), Configuration.read(fixture.host), 5806)).isEqualTo(5806);
    assertThat(AgentMain.options(List.of("--root=/x"))).containsEntry("root", "/x");
  }

  @Test
  void itServesOnTheStampsPortUnlessToldOtherwise() throws Exception {
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
    }
    assertThat(new StampSource(fixture.host).file().agentPort()).isEqualTo(5808);
  }
}
