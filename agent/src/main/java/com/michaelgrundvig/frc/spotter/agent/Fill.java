package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How a command's output fills fields: one rule, the same for collectors and actions.
 *
 * <ul>
 *   <li>The <b>named parts</b> are filled by the agent, by name, and never from a JSON key: {@code
 *       outcome} and {@code outcomeMessage} (how the command went, and why), then a {@code run}
 *       command's {@code exit} and {@code output} (its exit code and its standard output), or an
 *       {@code http} request's {@code status} and {@code body}. A {@code file} read has only the
 *       first two.
 *   <li>Every other field: if the output is a JSON object, each takes the key with its name (keys
 *       nobody declared are ignored), whatever the exit code or status; otherwise the output is one
 *       value, trimmed and converted to the type of the one field that holds it. The pack's limits
 *       on {@code exit} or {@code status} judge success, not the agent.
 *   <li>When the command didn't complete (it couldn't start, timed out, couldn't reach its URL), or
 *       printed more than is kept, {@code outcome} and {@code outcomeMessage} still say so, and
 *       every other field is unavailable, with that as its reason.
 * </ul>
 */
final class Fill {
  /** A number as text: what a non-JSON output's first word must be to fill a number. */
  static final Pattern NUMBER =
      Pattern.compile("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?");

  /** The most of an output a reason quotes. */
  static final int QUOTED = 60;

  /** The named parts every command fills: how it went, and why. */
  static final String OUTCOME = "outcome";

  static final String OUTCOME_MESSAGE = "outcomeMessage";

  /** A {@code run} command's named parts: its exit code, and its standard output. */
  static final String EXIT = "exit";

  static final String OUTPUT = "output";

  /** An {@code http} request's named parts: its status, and its body. */
  static final String STATUS = "status";

  static final String BODY = "body";

  private Fill() {}

  /** What a named part is. */
  enum Part {
    OUTCOME,
    OUTCOME_MESSAGE,
    CODE,
    WHOLE
  }

  /** The named parts of a kind of command, by name, in their order. */
  static Map<String, Part> parts(Commands.Kind kind) {
    Map<String, Part> parts = new LinkedHashMap<>();
    parts.put(OUTCOME, Part.OUTCOME);
    parts.put(OUTCOME_MESSAGE, Part.OUTCOME_MESSAGE);
    if (kind == Commands.Kind.RUN) {
      parts.put(EXIT, Part.CODE);
      parts.put(OUTPUT, Part.WHOLE);
    } else if (kind == Commands.Kind.HTTP) {
      parts.put(STATUS, Part.CODE);
      parts.put(BODY, Part.WHOLE);
    }
    return parts;
  }

  /** The kind of a command. */
  static Commands.Kind kind(Command command) {
    if (command instanceof Command.Run) {
      return Commands.Kind.RUN;
    }
    return command instanceof Command.Http ? Commands.Kind.HTTP : Commands.Kind.READ;
  }

  /** An outcome's name, as the {@code outcome} part has it: {@code completed}, {@code timedOut}. */
  static String name(Spotter.Outcome outcome) {
    switch (outcome) {
      case OUTCOME_COMPLETED:
        return "completed";
      case OUTCOME_UNREACHABLE:
        return "unreachable";
      case OUTCOME_COULD_NOT_START:
        return "couldNotStart";
      case OUTCOME_TIMED_OUT:
        return "timedOut";
      case OUTCOME_CANCELLED:
        return "cancelled";
      case OUTCOME_LOST:
        return "lost";
      default:
        return "";
    }
  }

  /**
   * Why a command's output fills nothing but its outcome: it didn't complete, or printed more than
   * was kept; empty when it fills its fields.
   *
   * @param maxOutput the most of its output kept, to say so
   */
  static String reason(Commands.Result result, int maxOutput) {
    if (!result.completed()) {
      return result.message();
    }
    if (result.truncated()) {
      return "it printed more than the " + maxOutput / 1024 + " KiB kept";
    }
    return "";
  }

  /**
   * The fields, in their order, from one run of a command, by the rule.
   *
   * @param maxOutput the most of its output kept, to say so
   */
  static List<Spotter.FieldValue> fill(List<Field> fields, Commands.Result result, int maxOutput) {
    Map<String, Part> parts = parts(result.kind());
    String reason = reason(result, maxOutput);
    List<Field> own = new ArrayList<>();
    for (Field field : fields) {
      if (!parts.containsKey(field.name())) {
        own.add(field);
      }
    }
    List<Spotter.FieldValue> filled =
        reason.isEmpty() ? fields(own, result.text()) : unavailable(own, reason);
    List<Spotter.FieldValue> values = new ArrayList<>();
    int next = 0;
    for (Field field : fields) {
      Part part = parts.get(field.name());
      if (part == null) {
        values.add(filled.get(next++));
        continue;
      }
      switch (part) {
        case OUTCOME:
          values.add(Spotter.FieldValue.newInstance().setText(name(result.outcome())));
          break;
        case OUTCOME_MESSAGE:
          values.add(Spotter.FieldValue.newInstance().setText(result.message()));
          break;
        case CODE:
          values.add(
              reason.isEmpty()
                  ? Spotter.FieldValue.newInstance().setNumber(result.code())
                  : unavailable(reason));
          break;
        default:
          values.add(reason.isEmpty() ? whole(field, result.text()) : unavailable(reason));
          break;
      }
    }
    return values;
  }

  /** The whole output, as its field's type has it: as it is, untrimmed, for text. */
  private static Spotter.FieldValue whole(Field field, String output) {
    return field.type() == Spotter.FieldType.FIELD_TYPE_JSON
        ? Spotter.FieldValue.newInstance().setJson(output)
        : Spotter.FieldValue.newInstance().setText(output);
  }

  /** Fields from an output: a JSON object's keys, or else the one value. */
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
