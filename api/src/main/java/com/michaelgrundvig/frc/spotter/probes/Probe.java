package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * One probe, as an image defines it: a named check the agent runs, on its own schedule or when the
 * robot asks, and reports. The robot can only name a probe; everything it runs, reads, or asks is
 * fixed here, in the image.
 *
 * @param id its name: printable ASCII, no {@code /}, no space at either end, at most {@link
 *     #MAX_ID} characters; unique on its computer
 * @param pack the pack it came from ({@code table} for one written in the coprocessor table)
 * @param check what it checks
 * @param everySeconds how often it runs, in seconds; 0 to run only when asked
 * @param timeoutSeconds how long a run may take before it's stopped and counted an error
 * @param watch files whose change reruns it at once: between changes, a probe that watches files
 *     runs only every {@link #WATCHED_EVERY_SECONDS}, so a costly one (a hash of a database) costs
 *     nothing while nothing changes; empty for none
 */
public record Probe(
    String id,
    String pack,
    Check check,
    double everySeconds,
    double timeoutSeconds,
    List<String> watch) {
  /** The longest an id may be. */
  public static final int MAX_ID = 128;

  /** The most files a probe watches. */
  public static final int MAX_WATCH = 8;

  /** The longest a run may take. */
  public static final double MAX_TIMEOUT_SECONDS = 120;

  /** The longest between runs. */
  public static final double MAX_EVERY_SECONDS = 86_400;

  /** How often a probe that watches files runs while none of them changes. */
  public static final double WATCHED_EVERY_SECONDS = 300;

  /** A run's timeout unless the definition says. */
  public static final double DEFAULT_TIMEOUT_SECONDS = 2;

  public Probe {
    watch = List.copyOf(watch);
    checkId(id, "id");
    if (!pack.matches("[a-z][a-z0-9-]{0,63}")) {
      throw new IllegalArgumentException("pack \"" + pack + "\" isn't a pack's name");
    }
    if (!(everySeconds == 0 || (everySeconds >= 0.1 && everySeconds <= MAX_EVERY_SECONDS))) {
      throw new IllegalArgumentException(
          "every must be 0 (when asked) or 0.1 to " + (long) MAX_EVERY_SECONDS + " seconds");
    }
    if (!(timeoutSeconds >= 0.1 && timeoutSeconds <= MAX_TIMEOUT_SECONDS)) {
      throw new IllegalArgumentException(
          "timeout must be 0.1 to " + (long) MAX_TIMEOUT_SECONDS + " seconds");
    }
    if (watch.size() > MAX_WATCH) {
      throw new IllegalArgumentException("watch names more than " + MAX_WATCH + " files");
    }
    for (String file : watch) {
      Check.checkPath(file, "watch");
    }
  }

  /** Checks a name a probe, step, or download goes by. */
  static void checkId(String id, String what) {
    if (id.isEmpty()
        || id.length() > MAX_ID
        || !id.equals(id.strip())
        || id.indexOf('/') >= 0
        || id.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
      throw new IllegalArgumentException(
          what
              + " \""
              + id
              + "\" must be printable ASCII without /, with no space at either end, at most "
              + MAX_ID
              + " characters");
    }
  }

  /** Its kind. */
  public ProbeKind kind() {
    return check.kind();
  }

  /** Whether it runs only when asked. */
  public boolean onDemand() {
    return everySeconds == 0;
  }

  /** The probe as JSON: its own members, then its check's. */
  public JsonValue.Obj toJson() {
    JsonValue.Obj.Builder json =
        JsonValue.Obj.builder()
            .put("id", id)
            .put("pack", pack)
            .put("kind", kind().id())
            .put("every", everySeconds)
            .put("timeout", timeoutSeconds)
            .put("watch", watch);
    check.toJson().members().forEach(json::put);
    return json.build();
  }

  /** A probe from its JSON, checked. */
  public static Probe fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("probe");
    String kindId = o.string("kind", "");
    ProbeKind kind =
        ProbeKind.byId(kindId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "kind \"" + kindId + "\" isn't one of " + ProbeKind.ids()));
    return new Probe(
        o.string("id", ""),
        o.string("pack", ""),
        Check.fromJson(kind, o),
        o.number("every", 0),
        o.number("timeout", DEFAULT_TIMEOUT_SECONDS),
        o.strings("watch"));
  }
}
