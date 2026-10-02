package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.probes.Download;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import com.michaelgrundvig.frc.spotter.probes.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Compiles one computer's definitions ({@link ProbeSet}) from the packs it names and the probes the
 * table gives it, filling in the placeholders: where the image puts the agent and each pack, the
 * computer's name, and (for a definition written {@code each: camera}) each of its cameras. The
 * result has no placeholder left: every program, path, and URL in it is fixed.
 */
public final class Packs {
  /** Where the agent's package installs the agent and its Java runtime. */
  public static final String AGENT_DIR = "/usr/lib/frc-coprocessor-agent";

  /** Where packs are installed, each in a folder of its name, holding its {@code pack.json}. */
  public static final String PACKS_DIR = "/usr/lib/frc-coprocessor/packs";

  /** The pack the table's own probes count as. */
  public static final String TABLE = "table";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z]+)\\}");

  private Packs() {}

  /**
   * A computer's definitions: the built-in pack's, then each pack it names in order, then its own
   * probes. The agent compiles its own this way when it starts, from its configuration and the
   * packs installed, and the robot's build the same way from the table, so they agree.
   *
   * @param computer the computer's configuration
   * @param packs its packs, in order, the built-in one first
   * @throws TableException listing every problem: two definitions of one name, a placeholder
   *     without a value, a definition that fails its checks
   */
  public static ProbeSet compile(AgentConfig computer, List<Pack> packs) {
    List<String> problems = new ArrayList<>();
    Map<String, String> probeSources = new LinkedHashMap<>();
    List<Probe> probes = new ArrayList<>();
    List<Step> steps = new ArrayList<>();
    List<Download> downloads = new ArrayList<>();
    Set<String> units = new LinkedHashSet<>();
    List<String> names = new ArrayList<>();
    List<Pack> all = new ArrayList<>(packs);
    all.add(new Pack(TABLE, computer.probes(), List.of(), List.of(), List.of()));
    for (Pack pack : all) {
      if (!pack.name().equals(TABLE)) {
        names.add(pack.name());
      }
      for (Pack.Written written : pack.probes()) {
        for (JsonValue.Obj json : expand(written, pack.name(), computer, problems)) {
          try {
            Probe probe = Probe.fromJson(withPack(json, pack.name()));
            String earlier = probeSources.putIfAbsent(probe.id(), written.source());
            if (earlier != null) {
              problems.add(
                  written.source() + ": probe " + probe.id() + " is already defined at " + earlier);
            } else {
              probes.add(probe);
            }
          } catch (IllegalArgumentException | JsonException e) {
            problems.add(written.source() + ": " + e.getMessage());
          }
        }
      }
      for (Pack.Written written : pack.beforeShutdown()) {
        for (JsonValue.Obj json : expand(written, pack.name(), computer, problems)) {
          try {
            steps.add(Step.fromJson(json));
          } catch (IllegalArgumentException | JsonException e) {
            problems.add(written.source() + ": " + e.getMessage());
          }
        }
      }
      for (Pack.Written written : pack.downloads()) {
        for (JsonValue.Obj json : expand(written, pack.name(), computer, problems)) {
          try {
            downloads.add(Download.fromJson(json));
          } catch (IllegalArgumentException | JsonException e) {
            problems.add(written.source() + ": " + e.getMessage());
          }
        }
      }
      units.addAll(pack.journalUnits());
    }
    if (!problems.isEmpty()) {
      throw new TableException(problems);
    }
    try {
      return new ProbeSet(computer.name(), names, probes, steps, List.copyOf(units), downloads);
    } catch (IllegalArgumentException e) {
      throw new TableException(List.of("computer " + computer.name() + ": " + e.getMessage()));
    }
  }

  /** A definition, once, or once for each of the computer's cameras, its placeholders filled. */
  private static List<JsonValue.Obj> expand(
      Pack.Written written, String pack, AgentConfig computer, List<String> problems) {
    List<JsonValue.Obj> out = new ArrayList<>();
    try {
      if (written.eachCamera()) {
        boolean needsPort = Json.compact(written.json()).contains("{port}");
        for (AgentConfig.Camera camera : computer.cameras()) {
          // A camera with no port has no port to check: the probes that read one leave it out.
          if (needsPort && camera.port().isEmpty()) {
            continue;
          }
          out.add(resolve(written.json(), placeholders(pack, computer.name(), camera)));
        }
      } else {
        out.add(resolve(written.json(), placeholders(pack, computer.name(), null)));
      }
    } catch (IllegalArgumentException e) {
      problems.add(written.source() + ": " + e.getMessage());
    }
    return out;
  }

  /**
   * The placeholders' values: {@code {agent}}, {@code {runtime}} (the agent's Java runtime), {@code
   * {pack}} (the pack's folder), {@code {computer}}, and for a definition written for each camera,
   * {@code {camera}} (its name) and {@code {port}} (its by-path entry).
   */
  static Map<String, String> placeholders(
      String pack, String computer, AgentConfig.@Nullable Camera camera) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("agent", AGENT_DIR);
    values.put("runtime", AGENT_DIR + "/runtime");
    values.put("pack", PACKS_DIR + "/" + pack);
    values.put("computer", computer);
    if (camera != null) {
      values.put("camera", camera.name());
      values.put("port", camera.port());
    }
    return values;
  }

  /** A definition with every placeholder in its text filled in. */
  static JsonValue.Obj resolve(JsonValue.Obj json, Map<String, String> values) {
    JsonValue.Obj.Builder out = JsonValue.Obj.builder();
    json.members().forEach((name, value) -> out.put(name, resolve(value, values)));
    return out.build();
  }

  private static JsonValue resolve(JsonValue value, Map<String, String> values) {
    if (value instanceof JsonValue.Str string) {
      return JsonValue.of(fill(string.value(), values));
    }
    if (value instanceof JsonValue.Arr array) {
      return new JsonValue.Arr(array.items().stream().map(item -> resolve(item, values)).toList());
    }
    return value;
  }

  private static String fill(String text, Map<String, String> values) {
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null) {
        throw new IllegalArgumentException(
            matcher.group()
                + (matcher.group(1).equals("camera") || matcher.group(1).equals("port")
                    ? " is only for a definition written each: camera"
                    : " isn't a placeholder: "
                        + String.join(
                            ", ", values.keySet().stream().map(v -> "{" + v + "}").toList())));
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  /** A probe's JSON with the pack it came from. */
  static JsonValue.Obj withPack(JsonValue.Obj json, String pack) {
    JsonValue.Obj.Builder out = JsonValue.Obj.builder();
    json.members().forEach(out::put);
    out.put("pack", pack);
    return out.build();
  }
}
