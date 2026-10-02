package com.michaelgrundvig.frc.spotter.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Settings as files: one per row, readable in a diff, and back to the same rows. */
class SettingsFilesTest {
  static final Settings EXAMPLE =
      new Settings(
          2,
          List.of(
              SettingsRow.fromText(
                  "global",
                  "networkConfig",
                  Map.of("contents", "{\"teamNumber\":1234,\"b\":[1,2]}")),
              SettingsRow.fromText(
                  "cameras",
                  "front right",
                  Map.of("config_json", "{\"FOV\":70.0}", "drivermode_json", "null"))));

  @Test
  void eachRowIsAFileUnderItsTable() {
    SortedMap<String, String> files = SettingsFiles.render(EXAMPLE);
    assertThat(files.keySet())
        .containsExactly(
            "cameras/front%20right.json", "database.json", "global/networkConfig.json");
    assertThat(files.get("database.json")).isEqualTo("{\"userVersion\": 2}\n");
    assertThat(files.get("global/networkConfig.json"))
        .isEqualTo(
            """
            {
              "contents": {
                "b": [1, 2],
                "teamNumber": 1234
              }
            }
            """);
  }

  @Test
  void filesToRowsToFilesLosesNothing() {
    SortedMap<String, String> files = SettingsFiles.render(EXAMPLE);
    Settings again = SettingsFiles.parse(files);
    assertThat(again).isEqualTo(EXAMPLE);
    assertThat(SettingsFiles.render(again)).isEqualTo(files);
  }

  @Test
  void aFolderIsWrittenAndReadBack(@TempDir Path dir) throws IOException {
    Path folder = dir.resolve(SettingsFiles.folder("vision-front"));
    assertThat(SettingsFiles.read(folder)).isEmpty();

    Files.createDirectories(folder.resolve("cameras"));
    Files.writeString(folder.resolve("cameras/gone.json"), "{}");
    Files.writeString(folder.resolve(".gitkeep"), "");
    Files.writeString(folder.resolve("notes.txt"), "kept");
    SettingsFiles.write(EXAMPLE, folder);

    assertThat(folder.resolve("cameras/gone.json")).doesNotExist();
    assertThat(folder.resolve("notes.txt")).exists();
    assertThat(
            Files.readString(folder.resolve("cameras/front%20right.json"), StandardCharsets.UTF_8))
        .contains("\"FOV\": 70.0");
    assertThat(SettingsFiles.read(folder)).contains(EXAMPLE);
  }

  @Test
  void namesBecomeFileNamesOnEverySystemAndBack() {
    for (String name :
        List.of(
            "plain-Name_1.2", "front right", "a/b\\c:d", "é✓", ".hidden", "CON", "nul.txt", "%")) {
      String encoded = SettingsFiles.encode(name);
      assertThat(encoded).as(name).matches("[A-Za-z0-9._%-]+").doesNotStartWith(".");
      assertThat(SettingsFiles.decode("x", encoded)).isEqualTo(name);
    }
    assertThat(SettingsFiles.encode("front right")).isEqualTo("front%20right");
    assertThat(SettingsFiles.encode("CON")).isEqualTo("%43ON");
    assertThat(SettingsFiles.decode("x", "café")).isEqualTo("café");
    assertThatThrownBy(() -> SettingsFiles.encode("")).isInstanceOf(SettingsException.class);
    assertThatThrownBy(() -> SettingsFiles.decode("cameras/a%2.json", "a%2"))
        .hasMessage("cameras/a%2.json: a % in a name must be followed by two hex digits");
    assertThatThrownBy(() -> SettingsFiles.decode("x", "a%zz"))
        .hasMessageContaining("two hex digits");
    assertThatThrownBy(() -> SettingsFiles.decode("x", "a%+1"))
        .hasMessageContaining("two hex digits");
  }

  @Test
  void keysDifferingOnlyInCaseAreRefused() {
    Settings clash =
        new Settings(
            2,
            List.of(
                SettingsRow.fromText("cameras", "Front", Map.of("config_json", "{}")),
                SettingsRow.fromText("cameras", "front", Map.of("config_json", "{}"))));
    assertThatThrownBy(() -> SettingsFiles.render(clash))
        .isInstanceOf(SettingsException.class)
        .hasMessageContaining("differ only in case");
  }

  @Test
  void filesThatArentSettingsSayWhy() {
    assertThatThrownBy(() -> SettingsFiles.parse(Map.of())).hasMessage("database.json is missing");
    assertThatThrownBy(() -> SettingsFiles.parse(Map.of("database.json", "{}")))
        .hasMessage("database.json has no userVersion");
    assertThatThrownBy(
            () ->
                SettingsFiles.parse(Map.of("database.json", "{\"userVersion\":2}", "x.json", "{}")))
        .hasMessageContaining("x.json isn't a row");
    assertThatThrownBy(
            () ->
                SettingsFiles.parse(
                    Map.of("database.json", "{\"userVersion\":2}", "global/a.json", "{\"a\":")))
        .hasMessageStartingWith("global/a.json: line 1, column 6");
    assertThatThrownBy(
            () ->
                SettingsFiles.parse(
                    Map.of("database.json", "{\"userVersion\":2}", "global/a.json", "[]")))
        .hasMessageContaining("expected an object");
  }

  @Test
  void twoRowsWithOneKeyAreRefused() {
    SettingsRow row = SettingsRow.fromText("global", "a", Map.of("contents", "{}"));
    assertThatThrownBy(() -> new Settings(2, List.of(row, row)))
        .hasMessage("two rows of table global have the key \"a\"");
  }
}
