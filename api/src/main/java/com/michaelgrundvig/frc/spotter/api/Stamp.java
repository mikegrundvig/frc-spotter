package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which image a coprocessor runs, and as which computer: {@code GET /v1/stamp}. The image builder
 * writes the image's identity into {@code /etc/coprocessor/stamp.json}; the agent adds the hash of
 * the probes it runs, the boot, and the MAC address as it answers.
 *
 * @param name the computer's name in the table, and its hostname
 * @param team the team number its address is built from
 * @param address its address, such as {@code 10.12.34.11}
 * @param version the image's version, such as the release it came from
 * @param recipeHash the hash of what built the image, as its builder computes it; empty when it has
 *     none
 * @param builtAt when the image was built, ISO 8601 in UTC; empty when unknown
 * @param labels the builder's own facts about the image (the software in it and its version, the
 *     board it's for), by name, each a line of text; Spotter only carries them, and a build may
 *     expect some ({@link com.michaelgrundvig.frc.spotter.table.CompiledTable#labels})
 * @param probesHash the hash of the probes the agent runs ({@link
 *     com.michaelgrundvig.frc.spotter.probes.ProbeSet#hash}); empty in the file
 * @param bootId this boot's ID (/proc/sys/kernel/random/boot_id); empty in the file
 * @param mac the MAC address of the network interface, lowercase; empty in the file
 */
public record Stamp(
    String name,
    int team,
    String address,
    String version,
    String recipeHash,
    String builtAt,
    Map<String, String> labels,
    String probesHash,
    String bootId,
    String mac) {
  public Stamp {
    labels = Collections.unmodifiableMap(new TreeMap<>(labels));
  }

  /** A stamp that says nothing: what the agent reports when the image carries none. */
  public static final Stamp NONE = new Stamp("", 0, "", "", "", "", Map.of(), "", "", "");

  /** A label's value; empty when the image has none by that name. */
  public String label(String name) {
    return labels.getOrDefault(name, "");
  }

  /** This stamp, as an agent running these probes says it. */
  public Stamp withProbesHash(String probesHash) {
    return new Stamp(
        name, team, address, version, recipeHash, builtAt, labels, probesHash, bootId, mac);
  }

  /** This stamp with the boot and MAC address the agent found. */
  public Stamp withRuntime(String bootId, String mac) {
    return new Stamp(
        name, team, address, version, recipeHash, builtAt, labels, probesHash, bootId, mac);
  }

  /** The stamp as JSON. */
  public JsonValue.Obj toJson() {
    JsonValue.Obj.Builder labelsJson = JsonValue.Obj.builder();
    labels.forEach(labelsJson::put);
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("team", team)
        .put("address", address)
        .put("version", version)
        .put("recipeHash", recipeHash)
        .put("builtAt", builtAt)
        .put("labels", labelsJson.build())
        .put("probesHash", probesHash)
        .put("bootId", bootId)
        .put("mac", mac)
        .build();
  }

  /** A stamp from its JSON; what's missing is empty. */
  public static Stamp fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("stamp");
    JsonValue.Obj labelsJson = o.objectOrEmpty("labels");
    Map<String, String> labels = new TreeMap<>();
    for (String label : labelsJson.members().keySet()) {
      labels.put(label, labelsJson.string(label, ""));
    }
    return new Stamp(
        o.string("name", ""),
        o.integer("team", 0),
        o.string("address", ""),
        o.string("version", ""),
        o.string("recipeHash", ""),
        o.string("builtAt", ""),
        labels,
        o.string("probesHash", ""),
        o.string("bootId", ""),
        o.string("mac", ""));
  }

  /** A stamp from JSON text, such as {@code /v1/stamp}'s answer or the stamp file. */
  public static Stamp parse(String json) {
    return fromJson(Json.parse(json));
  }
}
