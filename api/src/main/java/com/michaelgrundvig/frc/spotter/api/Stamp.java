package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which computer this is: {@code GET /v1/stamp}, and every health answer's {@code stamp}. The agent
 * reads it from the computer as it answers, and needs nothing configured: its hostname, its
 * addresses, its MAC, this boot, and {@code /etc/os-release} as it is. An image builder labels its
 * images there ({@code IMAGE_ID}, {@code IMAGE_VERSION}, and its own prefixed fields, as
 * os-release(5) has them); Spotter passes them through without reading meaning into any.
 *
 * @param hostname the computer's hostname, which is the agent's name for it
 * @param addresses its network addresses, IPv4 first: every interface's but loopback's, and IPv6
 *     link-local ones left out
 * @param mac the MAC address of its wired interface, lowercase; empty when it has none
 * @param bootId this boot's ID (/proc/sys/kernel/random/boot_id); it changes every boot
 * @param uptimeSeconds seconds since the kernel started; NaN when unknown
 * @param osRelease every key of {@code /etc/os-release} with its value, unquoted, and nothing else
 *     done to it
 */
public record Stamp(
    String hostname,
    List<String> addresses,
    String mac,
    String bootId,
    double uptimeSeconds,
    Map<String, String> osRelease) {
  public Stamp {
    addresses = List.copyOf(addresses);
    osRelease = Collections.unmodifiableMap(new TreeMap<>(osRelease));
  }

  /** A stamp that says nothing: what's reported when none of it could be read. */
  public static final Stamp NONE = new Stamp("", List.of(), "", "", Double.NaN, Map.of());

  /** A key's value in {@code /etc/os-release}; empty when the computer has no such key. */
  public String osRelease(String key) {
    return osRelease.getOrDefault(key, "");
  }

  /** The stamp as JSON. */
  public JsonValue.Obj toJson() {
    JsonValue.Obj.Builder release = JsonValue.Obj.builder();
    osRelease.forEach(release::put);
    return JsonValue.Obj.builder()
        .put("hostname", hostname)
        .put("addresses", addresses)
        .put("mac", mac)
        .put("bootId", bootId)
        .put("uptimeSeconds", uptimeSeconds)
        .put("osRelease", release.build())
        .build();
  }

  /** A stamp from its JSON; what's missing is empty. */
  public static Stamp fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("stamp");
    JsonValue.Obj release = o.objectOrEmpty("osRelease");
    Map<String, String> osRelease = new TreeMap<>();
    for (String key : release.members().keySet()) {
      osRelease.put(key, release.string(key, ""));
    }
    return new Stamp(
        o.string("hostname", ""),
        o.strings("addresses"),
        o.string("mac", ""),
        o.string("bootId", ""),
        o.number("uptimeSeconds", Double.NaN),
        osRelease);
  }

  /** A stamp from JSON text: {@code /v1/stamp}'s answer. */
  public static Stamp parse(String json) {
    return fromJson(Json.parse(json));
  }
}
