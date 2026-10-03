package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
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
    assertThat(AgentMain.options(List.of("--root=/x"))).containsEntry("root", "/x");
    assertThatThrownBy(() -> AgentMain.options(List.of("--port")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Usage:");
  }

  @Test
  void theControllerIsAnAddressNeverAName() throws Exception {
    for (String bad : List.of("robot.local", "10.12.34", "10.12.34.256", "::1", "")) {
      assertThatThrownBy(() -> AgentMain.checkAddress(bad, "--controller"))
          .as(bad)
          .hasMessageContaining("--controller must be an IPv4 address");
    }
    AgentMain.checkAddress("10.12.34.2", "--controller");
    Fixture fixture = new Fixture(dir);
    assertThatThrownBy(() -> AgentMain.serve(fixture.host, List.of("--bind=any"), "0.4.0"))
        .hasMessageContaining("--bind must be an IPv4 address");
    assertThatThrownBy(() -> AgentMain.serve(fixture.host, List.of("--controller=x"), "0.4.0"))
        .hasMessageContaining("--controller must be an IPv4 address");
  }

  @Test
  void itServesOn5808UnlessItsSettingsOrCommandLineSay() throws Exception {
    Fixture fixture = new Fixture(dir);
    Configuration none = fixture.configuration();
    assertThat(AgentMain.port(Map.of(), none)).isEqualTo(Protocol.PORT);
    assertThat(AgentMain.bind(Map.of(), none)).isEqualTo("0.0.0.0");
    fixture.config("{\"port\": 5809, \"bind\": \"127.0.0.1\"}");
    Configuration configured = fixture.configuration();
    assertThat(AgentMain.port(Map.of(), configured)).isEqualTo(5809);
    assertThat(AgentMain.bind(Map.of(), configured)).isEqualTo("127.0.0.1");
    assertThat(AgentMain.port(Map.of("port", "5807"), configured)).isEqualTo(5807);
    assertThat(AgentMain.bind(Map.of("bind", "10.12.34.11"), configured)).isEqualTo("10.12.34.11");
    // Settings that can't be read are ignored.
    fixture.config("{\"port\": 80}");
    assertThat(AgentMain.port(Map.of(), fixture.configuration())).isEqualTo(Protocol.PORT);
  }

  @Test
  void itServesItsDescriptionWithNothingConfiguredAndLogsWhatItIgnored() throws Exception {
    Fixture fixture = new Fixture(dir);
    fixture.write(Packs.INSTALLED + "/old.yaml", "pack: old\n");
    try (AgentServer server =
        AgentMain.serve(fixture.host, List.of("--port=0", "--bind=127.0.0.1"), "0.4.0")) {
      HttpResponse<String> described =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + server.port() + Protocol.DESCRIBE))
                      .header("Accept", Protocol.JSON)
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(described.statusCode()).isEqualTo(200);
      assertThat(described.headers().firstValue(Protocol.HEADER)).hasValue(Protocol.VERSION);
      assertThat(described.body())
          .contains("\"hostname\": \"vision-front\"", "\"agentVersion\": \"0.4.0\"");
    }
    assertThat(fixture.log())
        .contains(
            Packs.INSTALLED
                + "/old.yaml: not a pack (a pack is a folder, <name>/pack.yaml), ignored");
  }

  @Test
  void itsVersionIsItsJarsOrUnreleased() {
    // Run from the build's classes, there's no jar to say.
    assertThat(AgentMain.version()).isEqualTo(AgentMain.UNRELEASED);
  }
}
