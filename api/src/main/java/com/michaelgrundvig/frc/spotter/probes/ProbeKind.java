package com.michaelgrundvig.frc.spotter.probes;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The kinds of probe, and the only ones: each reads one sort of thing, with parameters its pack
 * fixes. No kind takes anything from a request: the robot names a probe, and nothing more
 * (docs/agent.md, "Probes").
 */
public enum ProbeKind {
  /** A program run directly (no shell), as the agent's user: its exit status and its output. */
  COMMAND,
  /** A GET on this computer ({@code localhost} only): its status, and a JSON field. */
  HTTP,
  /** A file: whether it's there, its size, its SHA-256, or a field or line in it. */
  FILE,
  /** A systemd unit: its state, and how often it has restarted this boot. */
  UNIT,
  /** A device at its stable {@code by-path} name, and its USB link speed. */
  USB,
  /** One of the agent's own measurements (heat, load, memory, disk), against limits. */
  THRESHOLD;

  /** Its name as definitions write it: {@code command}, {@code http}, ... */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The kind a definition names. */
  public static Optional<ProbeKind> byId(String id) {
    return Arrays.stream(values()).filter(kind -> kind.id().equals(id)).findFirst();
  }

  /** Every kind's name, for messages. */
  public static String ids() {
    return String.join(", ", Arrays.stream(values()).map(ProbeKind::id).toList());
  }
}
