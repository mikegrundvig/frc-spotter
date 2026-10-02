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
  private static final Set<String> TABLE_KEYS = Set.of("team", "agentPort", "computers");
  private static final Set<String> COMPUTER_KEYS =
      Set.of("name", "address", "board", "cameras", "agentPort");

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
    Map<String, Integer> cameraLines = new HashMap<>();
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
        for (String camera : computer.cameras()) {
          earlier = cameraLines.putIfAbsent(camera, fields.line());
          if (earlier != null) {
            problems.add(
                at(
                    fields.line(),
                    "camera "
                        + camera
                        + " is already listed by the computer on line "
                        + earlier
                        + "; PhotonVision camera names must be unique on the robot"));
          }
        }
        computers.add(computer);
      }
    } else if (!(list.value() instanceof Scalar scalar && scalar.empty())) {
      problems.add(at(list.line(), "computers must be a list (- name: ...)"));
    }
    if (!problems.isEmpty() || team == null || agentPort == null) {
      throw new TableException(problems);
    }
    return new Table(team, agentPort, computers);
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
    Board board = null;
    String boardId = name(mapping, "board");
    if (boardId != null) {
      board = Board.byId(boardId).orElse(null);
      if (board == null) {
        problems.add(
            at(
                lineOf(mapping, "board"),
                "unknown board \"" + boardId + "\"; known: " + Board.ids()));
      }
    }
    List<String> cameras = cameras(mapping);
    Integer port = integer(mapping, "agentPort", defaultPort);
    if (port != null && mapping.entries().containsKey("agentPort")) {
      check(mapping.entries().get("agentPort"), Computer.portProblem(port));
    }
    if (problems.size() > before
        || name == null
        || address == null
        || board == null
        || port == null) {
      return null;
    }
    return new Computer(name, address, board, cameras, port);
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

  private static int lineOf(Mapping mapping, String key) {
    Entry entry = mapping.entries().get(key);
    return entry == null ? mapping.line() : entry.line();
  }

  private String at(int line, String message) {
    return source + ":" + line + ": " + message;
  }
}
