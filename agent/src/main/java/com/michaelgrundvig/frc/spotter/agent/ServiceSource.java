package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Service;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** PhotonVision's service as systemd sees it, which PhotonVision can't report when it's down. */
final class ServiceSource {
  static final List<String> PROPERTIES =
      List.of("ActiveState", "SubState", "Result", "NRestarts", "ActiveEnterTimestampMonotonic");

  private final Host host;
  private final String unit;
  private final Duration timeout;

  ServiceSource(Host host, String unit, Duration timeout) {
    this.host = host;
    this.unit = unit;
    this.timeout = timeout;
  }

  Service read() throws IOException {
    Commands.Output output =
        host.commands()
            .run(
                List.of("systemctl", "show", unit, "--property=" + String.join(",", PROPERTIES)),
                timeout,
                32,
                4096);
    if (!output.ok()) {
      throw new IOException(
          "systemctl show " + unit + (output.timedOut() ? " timed out" : " failed"));
    }
    Map<String, String> values = new HashMap<>();
    for (String line : output.lines()) {
      int equals = line.indexOf('=');
      if (equals > 0) {
        values.put(line.substring(0, equals), line.substring(equals + 1));
      }
    }
    return new Service(
        unit,
        values.getOrDefault("ActiveState", ""),
        values.getOrDefault("SubState", ""),
        values.getOrDefault("Result", ""),
        (int) number(values.get("NRestarts")),
        number(values.get("ActiveEnterTimestampMonotonic")));
  }

  private static long number(@Nullable String text) {
    try {
      return text == null ? 0 : Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
