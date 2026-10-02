package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.ClockSync;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Whether the clock is synchronized, and its offset, whatever keeps it. Synchronized is the
 * kernel's own view, as {@code timedatectl} reads it (systemd's {@code ntp_synced()}: the kernel's
 * maximum error under 16 s), which chrony and systemd-timesyncd both set. The offset is the
 * daemon's: chrony's (its tracking report's correction, by {@code chronyc -c tracking}), else
 * systemd-timesyncd's (its last NTP message's, by {@code timedatectl timesync-status}). Both are
 * positive when the clock is behind its source (verified in their sources: chrony's {@code
 * client.c} prints a positive correction as "slow", timedatectl's offset is {@code ((T2 - T1) + (T3
 * - T4)) / 2}). Read at most every {@link #EVERY_MICROS}, as each reading runs programs.
 */
final class ClockSource {
  /** How long a reading is kept. */
  static final long EVERY_MICROS = 10_000_000;

  /** A time span as timedatectl writes one: {@code +1.234ms}, {@code -2min 3.5s}. */
  private static final Pattern SPAN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(us|ms|s|min|h|d)");

  private final Host host;
  private final Duration timeout;
  private @Nullable ClockSync last;
  private long lastMicros;

  ClockSource(Host host, Duration timeout) {
    this.host = host;
    this.timeout = timeout;
  }

  /** The clock now, or as read within the last {@link #EVERY_MICROS}. */
  synchronized ClockSync read() throws IOException {
    long now = host.monotonicMicros();
    ClockSync kept = last;
    if (kept != null && now - lastMicros < EVERY_MICROS) {
      return kept;
    }
    Commands.Output synced =
        host.commands()
            .run(
                List.of("timedatectl", "show", "--property=NTPSynchronized", "--value"),
                timeout,
                4,
                256);
    if (!synced.ok() || synced.lines().isEmpty()) {
      throw new IOException("timedatectl " + (synced.timedOut() ? "timed out" : "failed"));
    }
    String answer = synced.lines().get(0).strip();
    Boolean isSynced =
        answer.equals("yes") ? Boolean.TRUE : answer.equals("no") ? Boolean.FALSE : null;
    ClockSync read = offset(isSynced);
    last = read;
    lastMicros = now;
    return read;
  }

  /** The clock, with the offset of whichever daemon says. */
  private ClockSync offset(@Nullable Boolean synced) {
    double chrony = chrony();
    if (!Double.isNaN(chrony)) {
      return new ClockSync(synced, "chrony", chrony);
    }
    double timesyncd = timesyncd();
    if (!Double.isNaN(timesyncd)) {
      return new ClockSync(synced, "systemd-timesyncd", timesyncd);
    }
    return new ClockSync(synced, "", Double.NaN);
  }

  /** chrony's correction, in milliseconds: its tracking report's fifth field, in seconds. */
  private double chrony() {
    try {
      Commands.Output output =
          host.commands().run(List.of("chronyc", "-c", "tracking"), timeout, 4, 1024);
      if (!output.ok() || output.lines().isEmpty()) {
        return Double.NaN;
      }
      String[] fields = output.lines().get(0).split(",");
      return fields.length > 4 ? Double.parseDouble(fields[4]) * 1000 : Double.NaN;
    } catch (IOException | NumberFormatException e) {
      return Double.NaN; // no chrony
    }
  }

  /** systemd-timesyncd's offset, in milliseconds, from its status's {@code Offset:} line. */
  private double timesyncd() {
    try {
      Commands.Output output =
          host.commands()
              .run(List.of("timedatectl", "timesync-status", "--no-pager"), timeout, 40, 4096);
      if (!output.ok()) {
        return Double.NaN;
      }
      for (String line : output.lines()) {
        String text = line.strip();
        if (text.startsWith("Offset:")) {
          return span(text.substring("Offset:".length()).strip());
        }
      }
      return Double.NaN;
    } catch (IOException e) {
      return Double.NaN; // no timesyncd
    }
  }

  /** A signed time span as timedatectl writes one, in milliseconds; NaN when it isn't one. */
  static double span(String text) {
    String spaced = text.toLowerCase(Locale.ROOT).strip();
    double sign = 1;
    if (spaced.startsWith("+") || spaced.startsWith("-")) {
      sign = spaced.startsWith("-") ? -1 : 1;
      spaced = spaced.substring(1);
    }
    Matcher matcher = SPAN.matcher(spaced);
    double millis = 0;
    int end = 0;
    boolean any = false;
    while (matcher.find()) {
      if (!spaced.substring(end, matcher.start()).isBlank()) {
        return Double.NaN;
      }
      double value = Double.parseDouble(matcher.group(1));
      millis +=
          value
              * switch (matcher.group(2)) {
                case "us" -> 0.001;
                case "ms" -> 1;
                case "s" -> 1000;
                case "min" -> 60_000;
                case "h" -> 3_600_000;
                default -> 86_400_000;
              };
      end = matcher.end();
      any = true;
    }
    return any && spaced.substring(end).isBlank() ? sign * millis : Double.NaN;
  }
}
