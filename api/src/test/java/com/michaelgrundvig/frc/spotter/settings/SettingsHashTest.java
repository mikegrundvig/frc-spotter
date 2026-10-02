package com.michaelgrundvig.frc.spotter.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.settings.SettingsHash.Action;
import com.michaelgrundvig.frc.spotter.settings.SettingsHash.Rule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hash counts what a person set, and nothing PhotonVision changes by itself. */
class SettingsHashTest {
  private static Settings camera(String config) {
    return new Settings(
        2, List.of(SettingsRow.fromText("cameras", "cam", Map.of("config_json", config))));
  }

  private static final String CONFIG =
      "{\"nickname\":\"front-left\",\"FOV\":70.0,\"currentPipelineIndex\":0,\"streamIndex\":0,"
          + "\"matchedCameraInfo\":{\"type\":\"PVUsbCameraInfo\",\"dev\":0,\"path\":\"/dev/video0\","
          + "\"otherPaths\":[\"/dev/v4l/by-path/p-usb-0:1:1.0-video-index0\",\"/dev/v4l/by-id/u\"]},"
          + "\"pipelineSettings\":[{\"cameraExposureRaw\":12.0}]}";

  /**
   * The exclusion list, explicitly: changing it changes every team's hashes, so it changes here
   * too, on purpose, with the reason in the rule.
   */
  @Test
  void theRulesAreTheseAndSayWhy() {
    assertThat(SettingsHash.RULES)
        .extracting(
            rule ->
                rule.table()
                    + "/"
                    + rule.column()
                    + ":"
                    + String.join(".", rule.path())
                    + " "
                    + rule.action()
                    + " "
                    + rule.when())
        .containsExactly(
            "cameras/config_json:currentPipelineIndex EXCLUDE ALWAYS",
            "cameras/config_json:streamIndex EXCLUDE ALWAYS",
            "cameras/config_json:matchedCameraInfo.dev EXCLUDE USB_BY_PATH",
            "cameras/config_json:matchedCameraInfo.path EXCLUDE USB_BY_PATH",
            "cameras/config_json:matchedCameraInfo.otherPaths UNORDERED ALWAYS");
    assertThat(SettingsHash.RULES).allSatisfy(rule -> assertThat(rule.why()).isNotBlank());
  }

  @Test
  void aCameraNotMatchedByItsUsbPortKeepsItsPathInTheHash() {
    String csi =
        "{\"matchedCameraInfo\":{\"type\":\"PVCSICameraInfo\",\"path\":\"/base/soc/i2c0mux/i2c@1/ov9281@60\","
            + "\"dev\":0}}";
    assertThat(camera(csi.replace("i2c@1", "i2c@0")).hash()).isNotEqualTo(camera(csi).hash());
    assertThat(camera(csi.replace("\"dev\":0", "\"dev\":1")).hash())
        .isNotEqualTo(camera(csi).hash());

    String usbWithoutPort =
        "{\"matchedCameraInfo\":{\"type\":\"PVUsbCameraInfo\",\"path\":\"/dev/video0\","
            + "\"otherPaths\":[\"/dev/v4l/by-id/u\"]}}";
    assertThat(camera(usbWithoutPort.replace("video0", "video2")).hash())
        .isNotEqualTo(camera(usbWithoutPort).hash());
  }

  @Test
  void whatPhotonVisionChangesByItselfDoesntChangeTheHash() {
    String hash = camera(CONFIG).hash();
    assertThat(
            camera(
                    "{\"nickname\":\"front-left\",\"FOV\":70,\"currentPipelineIndex\":-1,"
                        + "\"streamIndex\":3,\"matchedCameraInfo\":{\"type\":\"PVUsbCameraInfo\","
                        + "\"dev\":4,\"path\":\"/dev/video4\",\"otherPaths\":[\"/dev/v4l/by-id/u\","
                        + "\"/dev/v4l/by-path/p-usb-0:1:1.0-video-index0\"]},"
                        + "\"pipelineSettings\":[{\"cameraExposureRaw\":1.2e1}]}")
                .hash())
        .isEqualTo(hash);
  }

  @Test
  void whatAPersonSetsChangesTheHash() {
    String hash = camera(CONFIG).hash();
    assertThat(camera(CONFIG.replace("\"FOV\":70.0", "\"FOV\":71.0")).hash()).isNotEqualTo(hash);
    assertThat(camera(CONFIG.replace("12.0", "13.0")).hash()).isNotEqualTo(hash);
    assertThat(camera(CONFIG.replace("front-left", "front-right")).hash()).isNotEqualTo(hash);
    // The camera moved to another USB port: the by-path entry is what PhotonVision matches by.
    assertThat(camera(CONFIG.replace("usb-0:1:1.0", "usb-0:2:1.0")).hash()).isNotEqualTo(hash);
    assertThat(new Settings(3, camera(CONFIG).rows()).hash()).isNotEqualTo(hash);
  }

  @Test
  void theHashIsPinned() throws SQLException {
    // The robot's build and every agent must compute the same hash from the same settings, across
    // versions. A change to the canonical form or the rules fails here first; if it's meant, it
    // changes every committed hash, and every image must be stamped again.
    assertThat(new Settings(2, List.of()).hash())
        .isEqualTo("21f65b396293bf5d2b216334c33ddd81da8de2dd86296f3bef91729eb695eee9");
    assertThat(camera(CONFIG).hash())
        .isEqualTo("dc2bbfd0bf922e86c2c3d5c294879f57ad05c772983a3e74b6fc9e2964f40814");
  }

  @Test
  void theHashIsTheSha256OfTheHashableForm() throws Exception {
    Settings two =
        new Settings(
            2,
            List.of(
                SettingsRow.fromText("global", "a", Map.of("contents", "{\"z\":1,\"y\":2.0}")),
                SettingsRow.fromText("global", "b", Map.of("contents", "[]")),
                SettingsRow.fromText("cameras", "c", Map.of("config_json", CONFIG))));
    for (Settings settings : List.of(new Settings(0, List.of()), camera(CONFIG), two)) {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  Json.hashable(SettingsHash.hashed(settings, SettingsHash.RULES))
                      .getBytes(StandardCharsets.UTF_8));
      assertThat(settings.hash()).isEqualTo(HexFormat.of().formatHex(digest));
    }
  }

  @Test
  void aDigestTakesRowsInOrderOnly() {
    SettingsHash.Digest digest = new SettingsHash.Digest();
    digest.add(SettingsRow.fromText("t", "b", Map.of()));
    assertThatThrownBy(() -> digest.add(SettingsRow.fromText("t", "a", Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    digest.finish(1);
    assertThatThrownBy(() -> digest.add(SettingsRow.fromText("u", "a", Map.of())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theFixturesHashTheSameFromTheDatabaseAndFromFiles(@TempDir Path dir) throws SQLException {
    Settings fromDatabase;
    try (Connection connection = PhotonVisionDatabase.open(PhotonVisionDatabase.configured(dir))) {
      fromDatabase = SettingsDatabase.read(connection);
    }
    Settings fromFiles = SettingsFiles.parse(SettingsFiles.render(fromDatabase));
    assertThat(SettingsHash.of(fromFiles)).isEqualTo(SettingsHash.of(fromDatabase)).hasSize(64);
    SettingsText text;
    try (Connection connection = PhotonVisionDatabase.open(dir.resolve("photon.sqlite"))) {
      text = SettingsDatabase.readText(connection);
    }
    assertThat(text.hash()).isEqualTo(fromDatabase.hash());
    assertThat(text.parse()).isEqualTo(fromDatabase);
    assertThat(text.length()).isGreaterThan(1000);
  }

  @Test
  void rulesReachIntoArraysAndAnyMember() {
    Settings settings =
        new Settings(
            1,
            List.of(
                SettingsRow.fromText(
                    "t",
                    "k",
                    Map.of("c", "{\"list\":[{\"x\":1,\"y\":2}],\"o\":{\"a\":1,\"b\":[3,1]}}"))));
    assertThat(
            Json.sorted(
                SettingsHash.hashed(
                    settings,
                    List.of(
                        new Rule(
                            "t",
                            "c",
                            List.of("list", "*", "x"),
                            Action.EXCLUDE,
                            SettingsHash.When.ALWAYS,
                            "why"),
                        new Rule(
                            "t",
                            "c",
                            List.of("o", "*"),
                            Action.UNORDERED,
                            SettingsHash.When.ALWAYS,
                            "why"),
                        new Rule(
                            "other",
                            "c",
                            List.of("o"),
                            Action.EXCLUDE,
                            SettingsHash.When.ALWAYS,
                            "why")))))
        .isEqualTo(
            "{\"tables\":{\"t\":{\"k\":{\"c\":{\"list\":[{\"y\":2}],\"o\":{\"a\":1,\"b\":[1,3]}}}}},"
                + "\"userVersion\":1}");
    assertThat(
            Json.sorted(
                SettingsHash.hashed(
                    settings,
                    List.of(
                        new Rule(
                            "t",
                            "c",
                            List.of("list", "*"),
                            Action.EXCLUDE,
                            SettingsHash.When.ALWAYS,
                            "why")))))
        .contains("\"list\":[]");
    assertThatThrownBy(
            () -> new Rule("t", "c", List.of(), Action.EXCLUDE, SettingsHash.When.ALWAYS, "why"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void settingsRoundTripThroughTheirJson() {
    Settings settings = camera(CONFIG);
    String json = Json.compact(settings.toJson());
    assertThat(json).startsWith("{\"hash\":\"" + settings.hash() + "\",\"userVersion\":2,");
    assertThat(Settings.parse(json)).isEqualTo(settings);
  }
}
