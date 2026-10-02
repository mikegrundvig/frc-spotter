package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;

/**
 * Something to run before the computer powers off, named and fixed by the image: a pack's way to
 * let its software save and stop first (a vision program's pack stops it, so its settings are
 * written). Steps run in order; one that fails or times out is logged, and the power-off goes on,
 * since an unclean stop beats none.
 *
 * @param name its name, for the log
 * @param argv the program and its arguments, run directly as the agent's user
 * @param timeoutSeconds how long it may take, at most {@link #MAX_TIMEOUT_SECONDS}
 */
public record Step(String name, List<String> argv, double timeoutSeconds) {
  /** The longest a step may take. */
  public static final double MAX_TIMEOUT_SECONDS = 300;

  public Step {
    argv = List.copyOf(argv);
    Probe.checkId(name, "a step's name");
    Check.checkArgv(argv, "argv");
    if (!(timeoutSeconds >= 0.1 && timeoutSeconds <= MAX_TIMEOUT_SECONDS)) {
      throw new IllegalArgumentException(
          "a step's timeout must be 0.1 to " + (long) MAX_TIMEOUT_SECONDS + " seconds");
    }
  }

  /** The step as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("argv", argv)
        .put("timeout", timeoutSeconds)
        .build();
  }

  /** A step from its JSON, checked. */
  public static Step fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("step");
    return new Step(o.string("name", ""), o.strings("argv"), o.number("timeout", 30));
  }
}
