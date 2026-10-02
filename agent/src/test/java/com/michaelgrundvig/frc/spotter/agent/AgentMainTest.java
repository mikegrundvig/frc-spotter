package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.settings.PhotonVisionDatabase;
import com.michaelgrundvig.frc.spotter.settings.Settings;
import com.michaelgrundvig.frc.spotter.settings.SettingsDatabase;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line: serving, and building a settings database for stamping. */
class AgentMainTest {
  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int settingsDb(String... args) {
    return AgentMain.settingsDb(
        List.of(args),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void settingsDbBuildsTheDatabaseFromCommittedRowsAndPrintsTheirHash() throws Exception {
    Settings settings;
    try (Connection connection =
        PhotonVisionDatabase.open(PhotonVisionDatabase.configured(dir.resolve("live")))) {
      settings = SettingsDatabase.read(connection);
    }
    Path rows = dir.resolve("coprocessors/vision-front/settings");
    SettingsFiles.write(settings, rows);
    Path empty = PhotonVisionDatabase.empty(dir.resolve("empty"));
    Path built = dir.resolve("photon.sqlite");

    assertThat(settingsDb(rows.toString(), empty.toString(), built.toString())).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8).strip()).isEqualTo(settings.hash());
    try (Connection connection = PhotonVisionDatabase.open(built)) {
      assertThat(SettingsDatabase.read(connection)).isEqualTo(settings);
    }
    // The empty database is copied, not changed.
    try (Connection connection = PhotonVisionDatabase.open(empty)) {
      assertThat(SettingsDatabase.read(connection).rows()).isEmpty();
    }
  }

  @Test
  void settingsDbSaysWhatsWrong() throws Exception {
    assertThat(settingsDb("one")).isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Usage:");

    Path empty = PhotonVisionDatabase.empty(dir.resolve("empty"));
    assertThat(
            settingsDb(
                dir.resolve("none").toString(), empty.toString(), dir.resolve("x").toString()))
        .isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("there are no settings in");

    Path rows = dir.resolve("rows");
    Files.createDirectories(rows);
    Files.writeString(rows.resolve("database.json"), "{\"userVersion\": 9}");
    assertThat(
            settingsDb(
                rows.toString(),
                dir.resolve("missing.sqlite").toString(),
                dir.resolve("x").toString()))
        .isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("there's no database at");
    assertThat(settingsDb(rows.toString(), empty.toString(), dir.resolve("x.sqlite").toString()))
        .isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("schema version 9");
  }

  @Test
  void optionsAreNamedAndAnythingElseShowsTheUsage() {
    assertThat(AgentMain.options(List.of("serve", "--port=5809", "--bind=127.0.0.1")))
        .isEqualTo(Map.of("port", "5809", "bind", "127.0.0.1"));
    assertThatThrownBy(() -> AgentMain.options(List.of("--port")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Usage:");
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
