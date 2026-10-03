package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A log's lines as entries: each a JSON object, its parts under the keys its pack's {@code map}
 * names ({@code journalctl -o json} is a log as it stands). An entry's time is ISO 8601 text, or a
 * number with the unit the map declares; its level {@code error}, {@code warning}, {@code info},
 * {@code debug}, or syslog's 0 to 7 read as those. A line that isn't JSON is a message on its own,
 * at {@code info}, as a run's standard error has them.
 */
final class LogLines {
  /** The longest message kept; a longer one is cut. */
  static final int MAX_MESSAGE = 4096;

  private LogLines() {}

  /**
   * An entry from a line; empty when the line is blank.
   *
   * @param nowMicros the time an entry without one gets: when a run's line was read; 0 for none
   */
  static Optional<Spotter.LogEntry> entry(String line, Pack.LogMap map, long nowMicros) {
    if (line.isBlank()) {
      return Optional.empty();
    }
    Optional<Map<String, Object>> json = JsonText.asObject(line);
    Spotter.LogEntry entry = Spotter.LogEntry.newInstance();
    if (json.isEmpty()) {
      return Optional.of(
          entry
              .setTimeMicros(nowMicros)
              .setLevel(Spotter.LogLevel.LOG_LEVEL_INFO)
              .setMessage(cut(line.strip())));
    }
    Map<String, Object> keys = json.get();
    long micros = micros(keys.get(map.time()), map.timeUnit());
    entry.setTimeMicros(micros != 0 ? micros : nowMicros);
    entry.setLevel(level(keys.get(map.level())));
    entry.setSource(text(keys.get(map.source())));
    entry.setMessage(cut(text(keys.get(map.message()))));
    entry.setCursor(text(keys.get(map.cursor())));
    return Optional.of(entry);
  }

  /** A level as a log or a request names it; unspecified when it isn't one. */
  static Spotter.LogLevel level(@Nullable Object value) {
    if (value == null) {
      return Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED;
    }
    String text = (value instanceof String ? (String) value : JsonText.write(value)).strip();
    switch (text.toLowerCase(Locale.ROOT)) {
      case "error":
      case "0":
      case "1":
      case "2":
      case "3":
        return Spotter.LogLevel.LOG_LEVEL_ERROR;
      case "warning":
      case "4":
        return Spotter.LogLevel.LOG_LEVEL_WARNING;
      case "info":
      case "5":
      case "6":
        return Spotter.LogLevel.LOG_LEVEL_INFO;
      case "debug":
      case "7":
        return Spotter.LogLevel.LOG_LEVEL_DEBUG;
      default:
        return Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED;
    }
  }

  /** A level's name, as {@code SPOTTER_LEVEL} gives it. */
  static String name(Spotter.LogLevel level) {
    switch (level) {
      case LOG_LEVEL_ERROR:
        return "error";
      case LOG_LEVEL_WARNING:
        return "warning";
      case LOG_LEVEL_INFO:
        return "info";
      default:
        return "debug";
    }
  }

  /**
   * A time in microseconds since the epoch: a number (or a number's text, as journalctl writes
   * them) in the declared unit, or ISO 8601 text; 0 when it's neither.
   */
  static long micros(@Nullable Object value, String unit) {
    if (value == null) {
      return 0;
    }
    String text = (value instanceof String ? (String) value : JsonText.write(value)).strip();
    if (!unit.isEmpty()) {
      if (!Fill.NUMBER.matcher(text).matches()) {
        return 0;
      }
      double number = Double.parseDouble(text);
      switch (unit) {
        case "ns":
          return Math.round(number / 1000);
        case "ms":
          return Math.round(number * 1000);
        case "s":
          return Math.round(number * 1_000_000);
        default:
          return Math.round(number);
      }
    }
    try {
      Instant instant =
          text.endsWith("Z") ? Instant.parse(text) : OffsetDateTime.parse(text).toInstant();
      return instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1000;
    } catch (DateTimeException e) {
      return 0;
    }
  }

  private static String text(@Nullable Object value) {
    if (value == null) {
      return "";
    }
    return value instanceof String ? (String) value : JsonText.write(value);
  }

  private static String cut(String text) {
    return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE);
  }
}
