package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Entry;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Mapping;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Node;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Scalar;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Sequence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads a pack's YAML ({@link YamlSubset}) into its probes, every problem with its file and line.
 * Each probe is checked as it's read, so a mistake shows at its line; a key nobody reads is a
 * problem, so a misspelling can't pass silently.
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

  static final Set<String> PACK_KEYS = Set.of("pack", "probes", "journalUnits");

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
      throw new PackException(List.of(source + ":" + root.line() + ": expected pack: and probes:"));
    }
    reader.unknownKeys(mapping, PACK_KEYS, "a pack");
    String name = "";
    Entry named = mapping.entries().get("pack");
    if (named == null || !(named.value() instanceof Scalar scalar) || scalar.empty()) {
      reader.problems.add(source + ":" + mapping.line() + ": pack: (its name) is missing");
    } else if (!Pack.NAME.matcher(scalar.text()).matches()) {
      reader.problems.add(
          source
              + ":"
              + named.line()
              + ": pack "
              + scalar.text()
              + " isn't a pack's name: lowercase letters, digits, and hyphens");
    } else {
      name = scalar.text();
    }
    List<Probe> probes = reader.probes(mapping, name.isEmpty() ? "pack" : name);
    List<String> units = reader.units(mapping);
    if (!reader.problems.isEmpty()) {
      throw new PackException(reader.problems);
    }
    return new Pack(name, units, probes);
  }

  private List<Probe> probes(Mapping mapping, String pack) {
    Entry entry = mapping.entries().get("probes");
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return List.of();
    }
    if (!(entry.value() instanceof Sequence sequence)) {
      problems.add(at(entry.line(), "expected a list (- id: ...)"));
      return List.of();
    }
    List<Probe> probes = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Node item : sequence.items()) {
      if (!(item instanceof Mapping fields)) {
        problems.add(at(item.line(), "each probe is a mapping of keys"));
        continue;
      }
      int before = problems.size();
      unknownKeys(fields, PROBE_KEYS, "a probe");
      JsonValue.Obj.Builder json = JsonValue.Obj.builder();
      for (Map.Entry<String, Entry> field : fields.entries().entrySet()) {
        String name = field.getKey();
        if (PROBE_KEYS.contains(name)) {
          JsonValue converted = value(name, field.getValue().value(), field.getValue().line());
          if (converted != null) {
            json.put(name, converted);
          }
        }
      }
      if (problems.size() > before) {
        continue;
      }
      try {
        Probe probe = Probe.fromJson(json.put("pack", pack).build());
        if (!ids.add(probe.id())) {
          problems.add(at(fields.line(), "probe " + probe.id() + " is defined twice"));
          continue;
        }
        probes.add(probe);
      } catch (IllegalArgumentException | JsonException e) {
        problems.add(at(fields.line(), String.valueOf(e.getMessage())));
      }
    }
    return probes;
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

  private List<String> units(Mapping mapping) {
    Entry entry = mapping.entries().get("journalUnits");
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return List.of();
    }
    JsonValue list = value("journalUnits", entry.value(), entry.line());
    if (!(list instanceof JsonValue.Arr array)) {
      return List.of();
    }
    List<String> units = new ArrayList<>();
    for (JsonValue item : array.items()) {
      String unit = item.asString("journalUnits");
      if (!ProbeSet.UNIT.matcher(unit).matches()) {
        problems.add(at(entry.line(), "journal unit \"" + unit + "\" isn't a unit's name"));
      } else if (units.contains(unit)) {
        problems.add(at(entry.line(), "journal unit " + unit + " is listed twice"));
      } else {
        units.add(unit);
      }
    }
    return units;
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
