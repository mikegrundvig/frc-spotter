package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How a command's output fills fields, the same for collectors and actions. A JSON object fills
 * each declared field from the key of its name (keys nobody declared are ignored); any other output
 * is one value, trimmed and converted to the type of the one field that holds it. A field that
 * can't be filled is unavailable, with the reason.
 */
final class Fill {
  /** A number as text: what a non-JSON output's first word must be to fill a number. */
  static final Pattern NUMBER =
      Pattern.compile("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?");

  /** The most of an output a reason quotes. */
  static final int QUOTED = 60;

  private Fill() {}

  /**
   * A collector's fields from one run of its command: each unavailable, with the reason, when the
   * command failed (it couldn't start, timed out, exited non-zero printing nothing, answered an
   * HTTP error) or printed more than was kept.
   *
   * @param maxOutput the most of its output kept, to say so
   */
  static List<Spotter.FieldValue> collector(
      List<Field> fields, Commands.Result result, int maxOutput) {
    String failure = failure(result, maxOutput);
    if (!failure.isEmpty()) {
      return unavailable(fields, failure);
    }
    return fields(fields, result.text());
  }

  /** Why a collector's run fills nothing; empty when it fills its fields. */
  static String failure(Commands.Result result, int maxOutput) {
    if (!result.completed()) {
      return result.message();
    }
    if (result.truncated()) {
      return "it printed more than the " + maxOutput / 1024 + " KiB kept";
    }
    if (result.kind() == Commands.Kind.RUN
        && result.code() != 0
        && result.text().strip().isEmpty()) {
      return "exit " + result.code() + (result.errors().isEmpty() ? "" : ": " + result.errors());
    }
    if (result.kind() == Commands.Kind.HTTP && (result.code() < 200 || result.code() > 299)) {
      return "it answered HTTP " + result.code();
    }
    return "";
  }

  /** Fields from an output, by the rule. */
  static List<Spotter.FieldValue> fields(List<Field> fields, String output) {
    Optional<Map<String, Object>> json = JsonText.asObject(output);
    List<Spotter.FieldValue> values = new ArrayList<>();
    if (json.isPresent()) {
      for (Field field : fields) {
        Map<String, Object> keys = json.get();
        values.add(
            keys.containsKey(field.name())
                ? fromJson(field, keys.get(field.name()))
                : unavailable("its output has no \"" + field.name() + "\""));
      }
      return values;
    }
    if (fields.size() != 1) {
      return unavailable(
          fields,
          "its output isn't a JSON object, which its "
              + fields.size()
              + " fields need: "
              + quote(output.strip()));
    }
    values.add(fromText(fields.get(0), output.strip()));
    return values;
  }

  /** A field from text: trimmed, and converted to its type. */
  static Spotter.FieldValue fromText(Field field, String text) {
    switch (field.type()) {
      case FIELD_TYPE_NUMBER:
        String first = text.isEmpty() ? "" : text.split("\\s+", 2)[0];
        if (NUMBER.matcher(first).matches()) {
          double number = Double.parseDouble(first);
          if (Double.isFinite(number)) {
            return Spotter.FieldValue.newInstance().setNumber(number);
          }
        }
        return unavailable("not a number: " + quote(text));
      case FIELD_TYPE_BOOLEAN:
        String flag = text.toLowerCase(Locale.ROOT);
        if (flag.equals("true") || flag.equals("false")) {
          return Spotter.FieldValue.newInstance().setFlag(flag.equals("true"));
        }
        return unavailable("not true or false: " + quote(text));
      case FIELD_TYPE_STATUS:
        return unavailable(
            "a status is a JSON object, {\"level\": \"ok\", \"message\": ...}, not " + quote(text));
      case FIELD_TYPE_JSON:
        return Spotter.FieldValue.newInstance().setJson(text);
      default:
        return Spotter.FieldValue.newInstance().setText(text);
    }
  }

  /** A field from a JSON value: converted to its type. */
  static Spotter.FieldValue fromJson(Field field, @Nullable Object value) {
    if (value == null) {
      return unavailable("\"" + field.name() + "\" is null");
    }
    switch (field.type()) {
      case FIELD_TYPE_NUMBER:
        if (value instanceof Number) {
          return Spotter.FieldValue.newInstance().setNumber(((Number) value).doubleValue());
        }
        return value instanceof String
            ? fromText(field, ((String) value).strip())
            : unavailable("not a number: " + quote(JsonText.write(value)));
      case FIELD_TYPE_BOOLEAN:
        if (value instanceof Boolean) {
          return Spotter.FieldValue.newInstance().setFlag((Boolean) value);
        }
        return value instanceof String
            ? fromText(field, ((String) value).strip())
            : unavailable("not true or false: " + quote(JsonText.write(value)));
      case FIELD_TYPE_STATUS:
        return status(value);
      case FIELD_TYPE_JSON:
        return Spotter.FieldValue.newInstance().setJson(JsonText.write(value));
      default:
        return Spotter.FieldValue.newInstance()
            .setText(value instanceof String ? (String) value : JsonText.write(value));
    }
  }

  /** A status from its JSON: {@code {"level": "ok", "message": "..."}}. */
  private static Spotter.FieldValue status(Object value) {
    if (value instanceof Map) {
      Map<?, ?> members = (Map<?, ?>) value;
      Object level = members.get("level");
      Object message = members.get("message");
      Spotter.Level named = null;
      if (level instanceof String) {
        switch (((String) level).toLowerCase(Locale.ROOT)) {
          case "ok":
            named = Spotter.Level.LEVEL_OK;
            break;
          case "warning":
            named = Spotter.Level.LEVEL_WARNING;
            break;
          case "failing":
            named = Spotter.Level.LEVEL_FAILING;
            break;
          default:
            break;
        }
      }
      if (named != null) {
        Spotter.Status status = Spotter.Status.newInstance().setLevel(named);
        if (message != null) {
          status.setMessage(message instanceof String ? (String) message : JsonText.write(message));
        }
        return Spotter.FieldValue.newInstance().setStatus(status);
      }
    }
    return unavailable(
        "a status's level is ok, warning or failing: " + quote(JsonText.write(value)));
  }

  /** A value that's unavailable, and why. */
  static Spotter.FieldValue unavailable(String why) {
    return Spotter.FieldValue.newInstance().setUnavailable(why);
  }

  /** Every field unavailable, for one reason. */
  static List<Spotter.FieldValue> unavailable(List<Field> fields, String why) {
    List<Spotter.FieldValue> values = new ArrayList<>();
    for (int i = 0; i < fields.size(); i++) {
      values.add(unavailable(why));
    }
    return values;
  }

  /** Text quoted in a reason, cut short. */
  static String quote(String text) {
    String shown = text.length() <= QUOTED ? text : text.substring(0, QUOTED) + "...";
    return "\"" + shown.replace("\n", "\\n") + "\"";
  }
}
