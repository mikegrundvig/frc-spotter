package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * One network interface's link, as the kernel counts it ({@code /sys/class/net}): whether it's up,
 * the speed it negotiated, and how often its link has dropped since boot. A loose plug at the radio
 * drops the link on every hit; a damaged cable negotiates 100 Mb/s.
 *
 * @param name the interface, such as {@code end0} or {@code eth0}
 * @param physical whether a device is behind it (a port), not a virtual interface
 * @param state its operational state: {@code up}, {@code down}, {@code dormant}, {@code
 *     lowerlayerdown}, or {@code unknown}
 * @param speedMbps the speed it negotiated, in Mb/s; -1 when it has none (down, or virtual)
 * @param carrierChanges how often its carrier came or went since boot: each drop counts two (down,
 *     then up again)
 * @param rxErrors receive errors since boot
 * @param txErrors transmit errors since boot
 */
public record NetworkLink(
    String name,
    boolean physical,
    String state,
    int speedMbps,
    long carrierChanges,
    long rxErrors,
    long txErrors) {

  /** Whether it's up. */
  public boolean up() {
    return state.equals("up");
  }

  /** The link as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("physical", physical)
        .put("state", state)
        .put("speedMbps", speedMbps)
        .put("carrierChanges", carrierChanges)
        .put("rxErrors", rxErrors)
        .put("txErrors", txErrors)
        .build();
  }

  /** A link from its JSON. */
  public static NetworkLink fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("network link");
    return new NetworkLink(
        o.string("name", ""),
        o.bool("physical", false),
        o.string("state", "unknown"),
        o.integer("speedMbps", -1),
        o.integer("carrierChanges", 0L),
        o.integer("rxErrors", 0L),
        o.integer("txErrors", 0L));
  }
}
