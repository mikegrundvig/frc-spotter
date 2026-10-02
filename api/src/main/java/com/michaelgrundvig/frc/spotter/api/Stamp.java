package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * Which image a coprocessor runs, and as which computer: {@code GET /v1/stamp}. The image's
 * stamping writes all but the last two into {@code /etc/coprocessor/stamp.json}; the agent adds the
 * boot and the MAC address as it answers.
 *
 * @param name the computer's name in the table, and its hostname
 * @param team the team number its address is built from
 * @param address its address, such as {@code 10.12.34.11}
 * @param board the board the image was built for, such as {@code orangepi-5}
 * @param cameras the PhotonVision camera names it runs (its role)
 * @param release the release the image came from, such as a Git tag
 * @param recipeHash the hash of the recipe that built the image (see the coprocessor README)
 * @param photonvisionVersion the PhotonVision version in the image, exactly as PhotonLib's
 * @param settingsHash the hash of the settings stamped into the image; empty when none were
 * @param probesHash the hash of the probe definitions stamped into the image ({@code
 *     /etc/coprocessor/probes.json}: the SHA-256 of the file); empty for an image without them
 * @param bootId this boot's ID (/proc/sys/kernel/random/boot_id); empty in the file
 * @param mac the MAC address of the network interface, lowercase; empty in the file
 */
public record Stamp(
    String name,
    int team,
    String address,
    String board,
    List<String> cameras,
    String release,
    String recipeHash,
    String photonvisionVersion,
    String settingsHash,
    String probesHash,
    String bootId,
    String mac) {
  public Stamp {
    cameras = List.copyOf(cameras);
  }

  /** A stamp without probe definitions: an image stamped before them. */
  public Stamp(
      String name,
      int team,
      String address,
      String board,
      List<String> cameras,
      String release,
      String recipeHash,
      String photonvisionVersion,
      String settingsHash,
      String bootId,
      String mac) {
    this(
        name,
        team,
        address,
        board,
        cameras,
        release,
        recipeHash,
        photonvisionVersion,
        settingsHash,
        "",
        bootId,
        mac);
  }

  /** A stamp that says nothing: what the agent reports when the image carries none. */
  public static final Stamp NONE = new Stamp("", 0, "", "", List.of(), "", "", "", "", "", "", "");

  /** This stamp, as an image carrying these probe definitions would say. */
  public Stamp withProbesHash(String probesHash) {
    return new Stamp(
        name,
        team,
        address,
        board,
        cameras,
        release,
        recipeHash,
        photonvisionVersion,
        settingsHash,
        probesHash,
        bootId,
        mac);
  }

  /** This stamp with the boot and MAC address the agent found. */
  public Stamp withRuntime(String bootId, String mac) {
    return new Stamp(
        name,
        team,
        address,
        board,
        cameras,
        release,
        recipeHash,
        photonvisionVersion,
        settingsHash,
        probesHash,
        bootId,
        mac);
  }

  /** The stamp as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("team", team)
        .put("address", address)
        .put("board", board)
        .put("cameras", cameras)
        .put("release", release)
        .put("recipeHash", recipeHash)
        .put("photonvisionVersion", photonvisionVersion)
        .put("settingsHash", settingsHash)
        .put("probesHash", probesHash)
        .put("bootId", bootId)
        .put("mac", mac)
        .build();
  }

  /** A stamp from its JSON; what's missing is empty. */
  public static Stamp fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("stamp");
    return new Stamp(
        o.string("name", ""),
        o.integer("team", 0),
        o.string("address", ""),
        o.string("board", ""),
        o.strings("cameras"),
        o.string("release", ""),
        o.string("recipeHash", ""),
        o.string("photonvisionVersion", ""),
        o.string("settingsHash", ""),
        o.string("probesHash", ""),
        o.string("bootId", ""),
        o.string("mac", ""));
  }

  /** A stamp from JSON text, such as {@code /v1/stamp}'s answer or the stamp file. */
  public static Stamp parse(String json) {
    return fromJson(Json.parse(json));
  }
}
