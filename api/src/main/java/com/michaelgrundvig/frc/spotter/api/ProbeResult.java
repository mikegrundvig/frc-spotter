package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * One probe's latest result, in {@link Health#probes}: a check the image defines, by name, that the
 * agent ran (docs/agent.md, "Probes"). The agent says what it found; the robot judges whether
 * that's what it needs.
 *
 * @param id the probe's id, as the image defines it
 * @param kind its kind: {@code command}, {@code http}, {@code file}, {@code unit}, {@code usb}, or
 *     {@code threshold}
 * @param status {@link #PASS}, {@link #FAIL}, {@link #ERROR}, or {@link #PENDING}
 * @param value what it found, at most {@link #MAX_TEXT} characters: the text its pattern matched, a
 *     unit's state, a metric's value, a file's hash; empty when it found nothing to say
 * @param detail why it failed or erred, at most {@link #MAX_TEXT} characters; empty when it passed
 * @param ranMicros when it last ran, on the agent's monotonic clock (microseconds since boot, the
 *     journal's clock); 0 when it hasn't
 * @param durationMillis how long that run took, in milliseconds
 */
public record ProbeResult(
    String id,
    String kind,
    String status,
    String value,
    String detail,
    long ranMicros,
    double durationMillis) {
  /** It ran, and found what the image expects. */
  public static final String PASS = "pass";

  /** It ran, and found something other than what the image expects. */
  public static final String FAIL = "fail";

  /**
   * It couldn't run, or couldn't finish: it timed out, its program couldn't start, or a read
   * failed.
   */
  public static final String ERROR = "error";

  /**
   * It hasn't run yet: an on-demand probe nobody has asked for, or one waiting for its first turn.
   */
  public static final String PENDING = "pending";

  /** The longest a value or detail is sent. */
  public static final int MAX_TEXT = 256;

  public ProbeResult {
    value = cut(value);
    detail = cut(detail);
  }

  /** Whether it passed. */
  public boolean passed() {
    return status.equals(PASS);
  }

  /** A probe that hasn't run. */
  public static ProbeResult pending(String id, String kind) {
    return new ProbeResult(id, kind, PENDING, "", "", 0, 0);
  }

  /** The result as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("id", id)
        .put("kind", kind)
        .put("status", status)
        .put("value", value)
        .put("detail", detail)
        .put("ranMicros", ranMicros)
        .put("durationMillis", durationMillis)
        .build();
  }

  /** A result from its JSON. */
  public static ProbeResult fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("probe result");
    return new ProbeResult(
        o.string("id", ""),
        o.string("kind", ""),
        o.string("status", ""),
        o.string("value", ""),
        o.string("detail", ""),
        o.integer("ranMicros", 0L),
        o.number("durationMillis", 0));
  }

  /** Text cut to {@link #MAX_TEXT} characters, saying so. */
  static String cut(String text) {
    return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
  }
}
