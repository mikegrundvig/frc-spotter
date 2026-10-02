package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.probes.Download;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import com.michaelgrundvig.frc.spotter.probes.Step;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Entry;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Mapping;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Node;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Scalar;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Sequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a pack's {@code pack.yaml}, and the probes a computer's entry in the table writes, into
 * definitions with their placeholders still in: in the YAML the table is written in ({@link
 * YamlSubset}), every problem with its file and line. Each definition is checked as it's read, with
 * its placeholders filled in for the check, so a mistake shows at its line rather than when a
 * computer's definitions are compiled.
 */
final class PackYaml {
  /** A probe's own keys, and each kind's parameters. */
  static final Set<String> PROBE_KEYS =
      Set.of(
          "id",
          "kind",
          "every",
          "timeout",
          "watch",
          "each",
          "argv",
          "exit",
          "match",
          "url",
          "status",
          "field",
          "equals",
          "path",
          "test",
          "minBytes",
          "maxBytes",
          "sha256",
          "unit",
          "state",
          "minSpeedMbps",
          "metric",
          "min",
          "max");

  static final Set<String> STEP_KEYS = Set.of("name", "argv", "timeout");

  static final Set<String> DOWNLOAD_KEYS =
      Set.of("name", "argv", "contentType", "maxBytes", "timeout");

  static final Set<String> PACK_KEYS =
      Set.of("pack", "probes", "beforeShutdown", "journalUnits", "downloads");

  /** The keys whose values are numbers. */
  static final Set<String> NUMBERS =
      Set.of(
          "every",
          "timeout",
          "exit",
          "status",
          "minBytes",
          "maxBytes",
          "minSpeedMbps",
          "min",
          "max");

  /** The keys whose values are lists of text. */
  static final Set<String> LISTS = Set.of("argv", "watch", "journalUnits");

  private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]{0,15})(\\.[0-9]{1,9})?");

  private final String source;
  private final List<String> problems = new ArrayList<>();

  private PackYaml(String source) {
    this.source = source;
  }

  static Pack pack(String yaml, String source) {
    PackYaml reader = new PackYaml(source);
    Node root = YamlSubset.parse(yaml, source);
    if (!(root instanceof Mapping mapping)) {
      throw new TableException(
          List.of(source + ":" + root.line() + ": expected pack: and probes:"));
    }
    reader.unknownKeys(mapping, PACK_KEYS, "a pack");
    String name = "";
    Entry named = mapping.entries().get("pack");
    if (named == null || !(named.value() instanceof Scalar scalar) || scalar.empty()) {
      reader.problems.add(source + ":" + mapping.line() + ": pack: (its name) is missing");
    } else if (!Pack.NAME.matcher(scalar.text()).matches() || scalar.text().equals("table")) {
      reader.problems.add(
          source
              + ":"
              + named.line()
              + ": pack "
              + scalar.text()
              + " isn't a pack's name: lowercase letters, digits, and hyphens (not \"table\")");
    } else {
      name = scalar.text();
    }
    List<Pack.Written> probes = reader.items(mapping, "probes", PROBE_KEYS, Kind.PROBE, name);
    List<Pack.Written> steps = reader.items(mapping, "beforeShutdown", STEP_KEYS, Kind.STEP, name);
    List<Pack.Written> downloads =
        reader.items(mapping, "downloads", DOWNLOAD_KEYS, Kind.DOWNLOAD, name);
    List<String> units = reader.texts(mapping, "journalUnits");
    if (!reader.problems.isEmpty()) {
      throw new TableException(reader.problems);
    }
    return new Pack(name, probes, steps, units, downloads);
  }

  /**
   * The probes a computer's entry in the table writes ({@code probes:}), each checked; problems are
   * added to {@code problems}.
   */
  static List<Pack.Written> tableProbes(Entry entry, String source, List<String> problems) {
    PackYaml reader = new PackYaml(source);
    List<Pack.Written> written = reader.items(entry, PROBE_KEYS, Kind.PROBE, Packs.TABLE);
    problems.addAll(reader.problems);
    return written;
  }

  private enum Kind {
    PROBE,
    STEP,
    DOWNLOAD
  }

  private List<Pack.Written> items(
      Mapping mapping, String key, Set<String> keys, Kind kind, String pack) {
    Entry entry = mapping.entries().get(key);
    return entry == null ? List.of() : items(entry, keys, kind, pack);
  }

  private List<Pack.Written> items(Entry entry, Set<String> keys, Kind kind, String pack) {
    if (entry.value() instanceof Scalar scalar && scalar.empty()) {
      return List.of();
    }
    if (!(entry.value() instanceof Sequence sequence)) {
      problems.add(at(entry.line(), "expected a list (- id: ...)"));
      return List.of();
    }
    List<Pack.Written> written = new ArrayList<>();
    for (Node item : sequence.items()) {
      if (!(item instanceof Mapping fields)) {
        problems.add(at(item.line(), "each item is a mapping of keys"));
        continue;
      }
      int before = problems.size();
      unknownKeys(fields, keys, "this item");
      JsonValue.Obj.Builder json = JsonValue.Obj.builder();
      boolean eachCamera = false;
      for (Map.Entry<String, Entry> field : fields.entries().entrySet()) {
        String name = field.getKey();
        Node value = field.getValue().value();
        int line = field.getValue().line();
        if (!keys.contains(name)) {
          continue;
        }
        if (name.equals("each")) {
          if (value instanceof Scalar each && each.text().equals("camera")) {
            eachCamera = true;
          } else {
            problems.add(
                at(line, "each may only be camera: once for each of the computer's cameras"));
          }
          continue;
        }
        JsonValue converted = value(name, value, line);
        if (converted != null) {
          json.put(name, converted);
        }
      }
      if (problems.size() > before) {
        continue;
      }
      Pack.Written one = new Pack.Written(json.build(), source + ":" + fields.line(), eachCamera);
      String problem = trial(one, kind, pack.isEmpty() ? "pack" : pack);
      if (problem != null) {
        problems.add(at(fields.line(), problem));
        continue;
      }
      written.add(one);
    }
    return written;
  }

  /** A definition checked as it would be compiled, with sample values for its placeholders. */
  private static @Nullable String trial(Pack.Written written, Kind kind, String pack) {
    try {
      JsonValue.Obj json =
          Packs.resolve(
              written.json(),
              Packs.placeholders(
                  pack,
                  "computer",
                  written.eachCamera()
                      ? new AgentConfig.Camera("camera", "platform-usb-0:1:1.0-video-index0")
                      : null));
      switch (kind) {
        case PROBE -> Probe.fromJson(Packs.withPack(json, pack));
        case STEP -> Step.fromJson(json);
        case DOWNLOAD -> Download.fromJson(json);
      }
      return null;
    } catch (IllegalArgumentException | JsonException e) {
      return e.getMessage();
    }
  }

  private @Nullable JsonValue value(String name, Node value, int line) {
    if (LISTS.contains(name)) {
      if (!(value instanceof Sequence sequence)) {
        problems.add(at(line, name + " must be a list: [a, b]"));
        return null;
      }
      List<String> items = new ArrayList<>();
      for (Node item : sequence.items()) {
        if (!(item instanceof Scalar scalar) || scalar.empty()) {
          problems.add(at(item.line(), name + "'s items are single values"));
          return null;
        }
        items.add(scalar.text());
      }
      return JsonValue.strings(items);
    }
    if (!(value instanceof Scalar scalar) || scalar.empty()) {
      problems.add(at(line, name + " must be a single value"));
      return null;
    }
    if (NUMBERS.contains(name)) {
      if (scalar.quoted() || !NUMBER.matcher(scalar.text()).matches()) {
        problems.add(at(line, name + " must be a number, not \"" + scalar.text() + "\""));
        return null;
      }
      return new JsonValue.Num(scalar.text());
    }
    return JsonValue.of(scalar.text());
  }

  private List<String> texts(Mapping mapping, String key) {
    Entry entry = mapping.entries().get(key);
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return List.of();
    }
    JsonValue list = value(key, entry.value(), entry.line());
    return list instanceof JsonValue.Arr array
        ? array.items().stream().map(item -> item.asString(key)).toList()
        : List.of();
  }

  private void unknownKeys(Mapping mapping, Set<String> known, String what) {
    for (Map.Entry<String, Entry> entry : mapping.entries().entrySet()) {
      if (!known.contains(entry.getKey())) {
        problems.add(
            at(
                entry.getValue().line(),
                "unknown key \""
                    + entry.getKey()
                    + "\" in "
                    + what
                    + "; known: "
                    + String.join(", ", new TreeSet<>(known))));
      }
    }
  }

  private String at(int line, String message) {
    return source + ":" + line + ": " + message;
  }
}
