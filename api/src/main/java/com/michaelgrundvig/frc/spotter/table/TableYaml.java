package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.table.YamlSubset.Entry;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Mapping;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Node;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Scalar;
import com.michaelgrundvig.frc.spotter.table.YamlSubset.Sequence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads coprocessors.yaml into a {@link Table}, reporting every problem at once, each with its file
 * and line, so one run of the build lists everything to fix.
 */
final class TableYaml {
  private static final Set<String> TABLE_KEYS = Set.of("team", "agentPort", "computers", "image");
  private static final Set<String> COMPUTER_KEYS =
      Set.of("name", "address", "cameras", "agentPort", "packs", "probes", "ports", "image");

  /** A whole number as the table writes one: no leading zeros, no sign but minus. */
  private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]{0,8})");

  /**
   * What YAML (1.2, as yq reads it) takes for something other than text when it isn't quoted: null,
   * true or false, or a number. A name written so is refused unless quoted, so this reader and yq
   * never disagree about it.
   */
  private static final Pattern NOT_TEXT =
      Pattern.compile(
          "~|null|Null|NULL|true|True|TRUE|false|False|FALSE"
              + "|[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+"
              + "|[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?"
              + "|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)");

  private final String source;
  private final List<String> problems = new ArrayList<>();

  private TableYaml(String source) {
    this.source = source;
  }

  static Table parse(String yaml, String source) {
    return new TableYaml(source).table(YamlSubset.parse(yaml, source));
  }

  private Table table(Node root) {
    if (!(root instanceof Mapping mapping)) {
      throw new TableException(
          List.of(at(root.line(), "expected team, agentPort, and computers at the top")));
    }
    unknownKeys(mapping, TABLE_KEYS, "the table");
    Integer team = integer(mapping, "team", null);
    if (team != null) {
      check(mapping.entries().get("team"), Table.teamProblem(team));
    }
    Integer agentPort = integer(mapping, "agentPort", Table.DEFAULT_AGENT_PORT);
    if (agentPort != null) {
      check(mapping.entries().get("agentPort"), Computer.portProblem(agentPort));
    }
    // A computer without a port of its own takes the table's, once that's known to be usable.
    int defaultPort =
        agentPort != null && Computer.portProblem(agentPort) == null
            ? agentPort
            : Table.DEFAULT_AGENT_PORT;
    List<Computer> computers = new ArrayList<>();
    Map<String, Integer> nameLines = new HashMap<>();
    Map<Integer, Integer> addressLines = new HashMap<>();
    Map<String, String> image = image(mapping);
    Entry list = mapping.entries().get("computers");
    if (list == null) {
      problems.add(at(mapping.line(), "computers is missing (computers: [] for none)"));
    } else if (list.value() instanceof Sequence sequence) {
      for (Node item : sequence.items()) {
        Computer computer = computer(item, defaultPort);
        if (computer == null) {
          continue;
        }
        Mapping fields = (Mapping) item;
        Integer earlier = nameLines.putIfAbsent(computer.name(), fields.line());
        if (earlier != null) {
          problems.add(
              at(fields.line(), "name " + computer.name() + " is taken (line " + earlier + ")"));
        }
        earlier = addressLines.putIfAbsent(computer.address(), fields.line());
        if (earlier != null) {
          problems.add(
              at(
                  fields.line(),
                  "address " + computer.address() + " is taken (line " + earlier + ")"));
        }
        computers.add(computer);
      }
    } else if (!(list.value() instanceof Scalar scalar && scalar.empty())) {
      problems.add(at(list.line(), "computers must be a list (- name: ...)"));
    }
    if (!problems.isEmpty() || team == null || agentPort == null) {
      throw new TableException(problems);
    }
    return new Table(team, agentPort, computers, image);
  }

  private @Nullable Computer computer(Node item, int defaultPort) {
    if (!(item instanceof Mapping mapping)) {
      problems.add(at(item.line(), "each computer is a mapping: - name: ..., address: ..."));
      return null;
    }
    int before = problems.size();
    unknownKeys(mapping, COMPUTER_KEYS, "a computer");
    String name = name(mapping, "name");
    if (name != null) {
      check(mapping.entries().get("name"), Computer.nameProblem(name));
    }
    Integer address = integer(mapping, "address", null);
    if (address != null) {
      check(mapping.entries().get("address"), Computer.addressProblem(address));
    }
    List<String> cameras = cameras(mapping);
    Integer port = integer(mapping, "agentPort", defaultPort);
    if (port != null && mapping.entries().containsKey("agentPort")) {
      check(mapping.entries().get("agentPort"), Computer.portProblem(port));
    }
    List<String> packs = packs(mapping);
    Map<String, String> ports = ports(mapping, cameras);
    Entry probesEntry = mapping.entries().get("probes");
    List<Pack.Written> probes =
        probesEntry == null ? List.of() : PackYaml.tableProbes(probesEntry, source, problems);
    Map<String, String> image = image(mapping);
    if (problems.size() > before || name == null || address == null || port == null) {
      return null;
    }
    return new Computer(name, address, cameras, port, packs, probes, ports, image);
  }

  /**
   * What the image builder is told: a mapping of names to single values, which Spotter only carries
   * (the builder checks them).
   */
  private Map<String, String> image(Mapping mapping) {
    Entry entry = mapping.entries().get("image");
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return Map.of();
    }
    if (!(entry.value() instanceof Mapping settings)) {
      problems.add(at(entry.line(), "image is a mapping: each setting, then its value"));
      return Map.of();
    }
    Map<String, String> found = new java.util.TreeMap<>();
    for (Map.Entry<String, Entry> setting : settings.entries().entrySet()) {
      int line = setting.getValue().line();
      if (!(setting.getValue().value() instanceof Scalar value) || value.empty()) {
        problems.add(at(line, "image setting " + setting.getKey() + " is a single value"));
        continue;
      }
      String problem = Computer.imageProblem(setting.getKey(), value.text());
      if (problem != null) {
        problems.add(at(line, problem));
      } else {
        found.put(setting.getKey(), value.text());
      }
    }
    return found;
  }

  /** Where each camera plugs in: a mapping of camera to its by-path entry. */
  private Map<String, String> ports(Mapping mapping, List<String> cameras) {
    Entry entry = mapping.entries().get("ports");
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return Map.of();
    }
    if (!(entry.value() instanceof Mapping ports)) {
      problems.add(
          at(entry.line(), "ports is a mapping: each camera, then its /dev/v4l/by-path/ entry"));
      return Map.of();
    }
    Map<String, String> found = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, Entry> port : ports.entries().entrySet()) {
      int line = port.getValue().line();
      if (!(port.getValue().value() instanceof Scalar value) || value.empty()) {
        problems.add(at(line, "camera " + port.getKey() + "'s port is a single value"));
        continue;
      }
      if (!cameras.contains(port.getKey())) {
        problems.add(at(line, port.getKey() + " isn't one of this computer's cameras"));
        continue;
      }
      try {
        new AgentConfig.Camera(port.getKey(), value.text());
        found.put(port.getKey(), value.text());
      } catch (IllegalArgumentException e) {
        problems.add(at(line, String.valueOf(e.getMessage())));
      }
    }
    return found;
  }

  private List<String> packs(Mapping mapping) {
    Entry entry = mapping.entries().get("packs");
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return List.of();
    }
    if (!(entry.value() instanceof Sequence sequence)) {
      problems.add(at(entry.line(), "packs must be a list: [my-pack]"));
      return List.of();
    }
    List<String> packs = new ArrayList<>();
    for (Node item : sequence.items()) {
      if (!(item instanceof Scalar scalar) || scalar.empty()) {
        problems.add(at(item.line(), "each pack is a name"));
        continue;
      }
      String problem = Computer.packProblem(scalar.text());
      if (problem != null) {
        problems.add(at(item.line(), problem));
      } else if (packs.contains(scalar.text())) {
        problems.add(at(item.line(), "pack " + scalar.text() + " is listed twice"));
      } else {
        packs.add(scalar.text());
      }
    }
    return packs;
  }

  private List<String> cameras(Mapping mapping) {
    Entry entry = mapping.entries().get("cameras");
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      return List.of();
    }
    if (!(entry.value() instanceof Sequence sequence)) {
      problems.add(at(entry.line(), "cameras must be a list: [front-left, front-right]"));
      return List.of();
    }
    List<String> cameras = new ArrayList<>();
    for (Node item : sequence.items()) {
      if (!(item instanceof Scalar scalar) || scalar.empty()) {
        problems.add(at(item.line(), "each camera is a name"));
        continue;
      }
      String problem =
          notText(scalar)
              ? notTextProblem("camera", scalar.text())
              : Computer.cameraProblem(scalar.text());
      if (problem != null) {
        problems.add(at(item.line(), problem));
      } else if (cameras.contains(scalar.text())) {
        problems.add(at(item.line(), "camera " + scalar.text() + " is listed twice"));
      } else {
        cameras.add(scalar.text());
      }
    }
    return cameras;
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
                    + String.join(", ", known.stream().sorted().toList())));
      }
    }
  }

  private @Nullable String text(Mapping mapping, String key) {
    Entry entry = mapping.entries().get(key);
    if (entry == null || entry.value() instanceof Scalar scalar && scalar.empty()) {
      problems.add(at(entry == null ? mapping.line() : entry.line(), key + " is missing"));
      return null;
    }
    if (!(entry.value() instanceof Scalar scalar)) {
      problems.add(at(entry.line(), key + " must be a single value"));
      return null;
    }
    return scalar.text();
  }

  /** The key's text, refused when YAML would read it as something else unless it's quoted. */
  private @Nullable String name(Mapping mapping, String key) {
    String text = text(mapping, key);
    Entry entry = mapping.entries().get(key);
    if (text != null && entry != null && notText((Scalar) entry.value())) {
      problems.add(at(entry.line(), notTextProblem(key, text)));
      return null;
    }
    return text;
  }

  private static boolean notText(Scalar scalar) {
    return !scalar.quoted() && NOT_TEXT.matcher(scalar.text()).matches();
  }

  private static String notTextProblem(String what, String text) {
    return what
        + " "
        + text
        + " isn't text to YAML (it's null, true or false, or a number): put it"
        + " in quotes";
  }

  /** The key's whole number; {@code fallback} when it's absent (null fallback: required). */
  private @Nullable Integer integer(Mapping mapping, String key, @Nullable Integer fallback) {
    Entry entry = mapping.entries().get(key);
    if (entry == null && fallback != null) {
      return fallback;
    }
    String text = text(mapping, key);
    if (text == null || entry == null) {
      return null;
    }
    if (((Scalar) entry.value()).quoted() || !INTEGER.matcher(text).matches()) {
      problems.add(at(entry.line(), key + " must be a whole number, not \"" + text + "\""));
      return null;
    }
    return Integer.parseInt(text);
  }

  private void check(@Nullable Entry entry, @Nullable String problem) {
    if (problem != null) {
      problems.add(at(Optional.ofNullable(entry).map(Entry::line).orElse(1), problem));
    }
  }

  private String at(int line, String message) {
    return source + ":" + line + ": " + message;
  }
}
