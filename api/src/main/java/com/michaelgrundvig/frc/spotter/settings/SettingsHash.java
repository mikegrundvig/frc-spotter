package com.michaelgrundvig.frc.spotter.settings;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The hash of a set of settings: what the robot's build compiles in for each computer's committed
 * settings, and what each agent computes from PhotonVision's live database, so the robot can say
 * when they differ.
 *
 * <p>It's the SHA-256, in lowercase hex, of {@link Json#hashable} (sorted, no spaces, every number
 * in one form) of
 *
 * <pre>{@code {"userVersion": <n>, "tables": {<table>: {<key>: <columns>}}}}</pre>
 *
 * after {@link #RULES} have taken out what PhotonVision changes by itself. So the hash doesn't
 * change when the settings are only rewritten (members reordered, {@code 1.0} written {@code 1}),
 * read from files or from the database, or touched by PhotonVision as it runs; it changes when a
 * setting does.
 */
public final class SettingsHash {
  /** What a rule does to the values it names. */
  public enum Action {
    /** Leaves the value out of the hash. */
    EXCLUDE,
    /** Hashes an array's items in a fixed order, so a reordering isn't a change. */
    UNORDERED
  }

  /** When a rule applies, judged by the object holding the value at the end of its path. */
  public enum When {
    /** Always. */
    ALWAYS,
    /**
     * Only for a USB camera that PhotonVision matches by its port: the object's {@code type} is
     * {@code PVUsbCameraInfo}, and its {@code otherPaths} have a by-path entry. Otherwise (a CSI or
     * file camera, or a USB camera without one) its path is what identifies it, and counts.
     */
    USB_BY_PATH
  }

  /**
   * A rule for values PhotonVision changes by itself.
   *
   * @param table the table it applies to
   * @param column the column whose JSON it applies to
   * @param path the members to follow from the column's value; {@code *} is any member or item
   * @param action what it does to the value at the end of the path
   * @param when when it applies
   * @param why what changes the value, which is why the hash can't count it
   */
  public record Rule(
      String table, String column, List<String> path, Action action, When when, String why) {
    public Rule {
      path = List.copyOf(path);
      if (path.isEmpty()) {
        throw new IllegalArgumentException("a rule needs a path");
      }
    }
  }

  /**
   * The values PhotonVision changes by itself, which the hash leaves out (or reads in a fixed
   * order). Unverified: these come from reading PhotonVision's source, and a bench test on a real
   * board (the hash holding steady across reboots, replugs, and pipeline switches) is what confirms
   * the list. Each rule says why; keep it that way, so the list can be checked.
   */
  public static final List<Rule> RULES =
      List.of(
          new Rule(
              "cameras",
              "config_json",
              List.of("currentPipelineIndex"),
              Action.EXCLUDE,
              When.ALWAYS,
              "the robot program switches pipelines and driver mode as it runs, and PhotonVision"
                  + " saves the choice"),
          new Rule(
              "cameras",
              "config_json",
              List.of("streamIndex"),
              Action.EXCLUDE,
              When.ALWAYS,
              "PhotonVision assigns each camera's stream ports as it starts"),
          new Rule(
              "cameras",
              "config_json",
              List.of("matchedCameraInfo", "dev"),
              Action.EXCLUDE,
              When.USB_BY_PATH,
              "the /dev/videoN number the kernel gave a USB camera this boot"),
          new Rule(
              "cameras",
              "config_json",
              List.of("matchedCameraInfo", "path"),
              Action.EXCLUDE,
              When.USB_BY_PATH,
              "the /dev/videoN device a USB camera had this boot; PhotonVision matches it by the"
                  + " by-path entry in otherPaths instead"),
          new Rule(
              "cameras",
              "config_json",
              List.of("matchedCameraInfo", "otherPaths"),
              Action.UNORDERED,
              When.ALWAYS,
              "the camera's other device paths, in an order PhotonVision notes can change at"
                  + " random; which ones they are (the USB port) still counts"));

  private SettingsHash() {}

  /** The settings' hash: 64 lowercase hex digits. */
  public static String of(Settings settings) {
    Digest digest = new Digest();
    settings.rows().forEach(digest::add);
    return digest.finish(settings.userVersion());
  }

  /**
   * Computes the hash a row at a time, so no more than one row need be in memory: the same text
   * {@link Json#hashable} writes for {@link #hashed}, fed to SHA-256 as it's written. Rows must
   * come in order of table, then key, as {@link Settings} keeps them.
   */
  public static final class Digest {
    private final MessageDigest sha256 = newSha256();
    private @Nullable SettingsRow last;
    private boolean finished;

    /** Adds the next row. */
    public void add(SettingsRow row) {
      if (finished) {
        throw new IllegalStateException("the hash is already finished");
      }
      SettingsRow previous = last;
      if (previous != null && Settings.ORDER.compare(previous, row) >= 0) {
        throw new IllegalArgumentException(
            "rows must come in order of table, then key: " + row.table() + "/" + row.key());
      }
      StringBuilder text = new StringBuilder();
      if (previous == null) {
        text.append("{\"tables\":{");
      } else if (previous.table().equals(row.table())) {
        text.append(',');
      } else {
        text.append("},");
      }
      if (previous == null || !previous.table().equals(row.table())) {
        text.append(Json.hashable(JsonValue.of(row.table()))).append(":{");
      }
      text.append(Json.hashable(JsonValue.of(row.key()))).append(':');
      update(text);
      // Written straight into the hash, a few kilobytes at a time: a calibration's row is
      // megabytes of text.
      try {
        Json.writeHashable(applyRules(row, RULES), utf8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      last = row;
    }

    /** The hash of the rows added, with the schema version. */
    public String finish(int userVersion) {
      finished = true;
      StringBuilder text = new StringBuilder();
      text.append(last == null ? "{\"tables\":{" : "}");
      text.append("},\"userVersion\":").append(Json.hashable(JsonValue.of(userVersion)));
      text.append('}');
      update(text);
      return HexFormat.of().formatHex(sha256.digest());
    }

    private void update(StringBuilder text) {
      sha256.update(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    // Text appended here goes into the hash as UTF-8. The writer never splits a character's two
    // halves between appends, and never writes half of one alone.
    private final Appendable utf8 =
        new Appendable() {
          @Override
          public Appendable append(CharSequence text) {
            sha256.update(text.toString().getBytes(StandardCharsets.UTF_8));
            return this;
          }

          @Override
          public Appendable append(CharSequence text, int start, int end) {
            return append(text.subSequence(start, end));
          }

          @Override
          public Appendable append(char c) {
            return append(String.valueOf(c));
          }
        };
  }

  /** What's hashed: the settings with the rules applied, before it's written down. */
  static JsonValue hashed(Settings settings, List<Rule> rules) {
    Map<String, JsonValue.Obj.Builder> tables = new LinkedHashMap<>();
    for (SettingsRow row : settings.rows()) {
      tables
          .computeIfAbsent(row.table(), table -> JsonValue.Obj.builder())
          .put(row.key(), applyRules(row, rules));
    }
    JsonValue.Obj.Builder tablesJson = JsonValue.Obj.builder();
    tables.forEach((table, rows) -> tablesJson.put(table, rows.build()));
    return JsonValue.Obj.builder()
        .put("userVersion", settings.userVersion())
        .put("tables", tablesJson.build())
        .build();
  }

  /** A row's columns with the rules applied. */
  static JsonValue applyRules(SettingsRow row, List<Rule> rules) {
    JsonValue columns = row.columns();
    for (Rule rule : rules) {
      if (rule.table().equals(row.table())) {
        List<String> path = new ArrayList<>();
        path.add(rule.column());
        path.addAll(rule.path());
        columns = apply(columns, path, rule.action(), rule.when());
      }
    }
    return columns;
  }

  private static JsonValue apply(JsonValue value, List<String> path, Action action, When when) {
    String head = path.get(0);
    List<String> rest = path.subList(1, path.size());
    if (value instanceof JsonValue.Obj object && (!rest.isEmpty() || applies(when, object))) {
      Map<String, JsonValue> members = new LinkedHashMap<>();
      object
          .members()
          .forEach(
              (name, member) -> {
                if (!head.equals("*") && !head.equals(name)) {
                  members.put(name, member);
                } else if (!rest.isEmpty()) {
                  members.put(name, apply(member, rest, action, when));
                } else if (action == Action.UNORDERED) {
                  members.put(name, unordered(member));
                }
              });
      return new JsonValue.Obj(members);
    }
    if (value instanceof JsonValue.Arr array && head.equals("*")) {
      if (rest.isEmpty()) {
        return action == Action.EXCLUDE ? new JsonValue.Arr(List.of()) : array;
      }
      return new JsonValue.Arr(
          array.items().stream().map(item -> apply(item, rest, action, when)).toList());
    }
    return value;
  }

  private static boolean applies(When when, JsonValue.Obj holder) {
    return switch (when) {
      case ALWAYS -> true;
      case USB_BY_PATH ->
          holder.get("type") instanceof JsonValue.Str type
              && type.value().equals("PVUsbCameraInfo")
              && holder.get("otherPaths") instanceof JsonValue.Arr others
              && others.items().stream()
                  .anyMatch(
                      other ->
                          other instanceof JsonValue.Str path
                              && path.value().contains("/by-path/"));
    };
  }

  private static JsonValue unordered(JsonValue value) {
    if (!(value instanceof JsonValue.Arr array)) {
      return value;
    }
    List<JsonValue> items = new ArrayList<>(array.items());
    items.sort(Comparator.comparing(Json::hashable));
    return new JsonValue.Arr(items);
  }

  static MessageDigest newSha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }
}
