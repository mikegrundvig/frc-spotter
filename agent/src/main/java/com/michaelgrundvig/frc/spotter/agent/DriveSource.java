package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The NVMe drive's health. Reading it takes root, so a root job on the image writes nvme-cli's
 * reports to {@code /run/coprocessor/} every minute, and the agent reads those files only: {@code
 * nvme smart-log --output-format=json} and {@code nvme id-ctrl --output-format=json}.
 *
 * <p>nvme-cli's JSON has changed between versions, so values are read leniently (unverified against
 * the image's nvme-cli): a temperature may be a number in kelvins (as the drive reports it) or text
 * with its unit; a percentage used may be {@code percent_used} or {@code percentage_used}; a
 * critical warning may be a number or an object with a {@code value}.
 */
final class DriveSource {
  static final String SMART_LOG = "/run/coprocessor/nvme-smart-log.json";
  static final String ID_CTRL = "/run/coprocessor/nvme-id-ctrl.json";
  static final String DEVICE = "/dev/nvme0";
  private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");

  private final Host host;

  DriveSource(Host host) {
    this.host = host;
  }

  /** The drive's health; empty when the image wrote none (no NVMe drive). */
  Optional<Drive> read() throws IOException {
    Optional<String> text = host.read(SMART_LOG);
    if (text.isEmpty() || text.get().isBlank()) {
      return Optional.empty();
    }
    JsonValue.Obj log = Json.parse(text.get()).asObject(SMART_LOG);
    String device = DEVICE;
    Optional<String> controller = host.read(ID_CTRL);
    if (controller.isPresent() && !controller.get().isBlank()) {
      String model = Json.parse(controller.get()).asObject(ID_CTRL).string("mn", "").strip();
      if (!model.isEmpty()) {
        device = DEVICE + " (" + model + ")";
      }
    }
    return Optional.of(
        new Drive(
            device,
            celsius(log.get("temperature")),
            (int) count(first(log, "percent_used", "percentage_used")),
            count(log.get("unsafe_shutdowns")),
            count(log.get("power_cycles")),
            count(log.get("power_on_hours")),
            count(log.get("media_errors")),
            (int) count(log.get("critical_warning"))));
  }

  private static @Nullable JsonValue first(JsonValue.Obj log, String name, String other) {
    JsonValue value = log.get(name);
    return value != null ? value : log.get(other);
  }

  /** A temperature in °C: kelvins when it's a bare number over 200, or text saying its unit. */
  static double celsius(@Nullable JsonValue value) {
    if (value instanceof JsonValue.Num number) {
      double reading = number.doubleValue();
      return reading > 200 ? Math.round((reading - 273.15) * 10) / 10.0 : reading;
    }
    if (value instanceof JsonValue.Str text) {
      Matcher matcher = NUMBER.matcher(text.value());
      if (matcher.find()) {
        double reading = Double.parseDouble(matcher.group());
        boolean kelvin = text.value().contains("K") && !text.value().contains("C");
        return kelvin ? Math.round((reading - 273.15) * 10) / 10.0 : reading;
      }
    }
    return Double.NaN;
  }

  /** A count: a number, a number as text, or an object's {@code value}; capped at a long. */
  static long count(@Nullable JsonValue value) {
    if (value instanceof JsonValue.Obj object) {
      return count(object.get("value"));
    }
    String text =
        value instanceof JsonValue.Num number
            ? number.text()
            : value instanceof JsonValue.Str string ? string.value().strip() : "";
    try {
      BigDecimal decimal = new BigDecimal(text);
      return decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
          ? Long.MAX_VALUE
          : decimal.longValue();
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
