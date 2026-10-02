package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * A team's coprocessors: {@code coprocessors/coprocessors.yaml}, the one place they're described.
 * Each computer's agent configuration, the robot program's list of what to watch, and what an image
 * builder makes all come from it. Every table is checked when it's made, however it's made: names
 * and addresses are unique, and each value is in range. An image builder checks its own settings
 * ({@link #image}, {@link Computer#image}) on top.
 *
 * @param team the team number; computers are at 10.TE.AM.x
 * @param agentPort the port the agents serve on, unless a computer says otherwise
 * @param computers the coprocessors, in the order the file lists them
 * @param image what the image builder is told for every computer (which recipe, say), by name
 */
public record Table(int team, int agentPort, List<Computer> computers, Map<String, String> image) {
  /** The agents' port unless the table says otherwise: in FRC's team range, 5800-5810. */
  public static final int DEFAULT_AGENT_PORT = 5808;

  /** The highest team number an address can carry: 10.255.99.x. */
  public static final int MAX_TEAM = 25599;

  /** Where a team's repository keeps its table, from the repository's root. */
  public static final String PATH = "coprocessors/coprocessors.yaml";

  /** A table with no image settings of its own. */
  public Table(int team, int agentPort, List<Computer> computers) {
    this(team, agentPort, computers, Map.of());
  }

  public Table {
    computers = List.copyOf(computers);
    image = Collections.unmodifiableMap(new TreeMap<>(image));
    List<String> problems = new ArrayList<>();
    image.forEach(
        (key, value) -> {
          String problem = Computer.imageProblem(key, value);
          if (problem != null) {
            problems.add(problem);
          }
        });
    String teamProblem = teamProblem(team);
    if (teamProblem != null) {
      problems.add(teamProblem);
    }
    String portProblem = Computer.portProblem(agentPort);
    if (portProblem != null) {
      problems.add(portProblem);
    }
    Set<String> names = new HashSet<>();
    Set<Integer> addresses = new HashSet<>();
    for (Computer computer : computers) {
      if (!names.add(computer.name())) {
        problems.add("two computers are named " + computer.name());
      }
      if (!addresses.add(computer.address())) {
        problems.add("two computers have address " + computer.address());
      }
    }
    if (!problems.isEmpty()) {
      throw new TableException(problems);
    }
  }

  /** What's wrong with a team number, or null if nothing. */
  public static @Nullable String teamProblem(int team) {
    if (team >= 0 && team <= MAX_TEAM) {
      return null;
    }
    return "team " + team + " is out of range: 0 to " + MAX_TEAM;
  }

  /** The computer with this name, if the table has one. */
  public Optional<Computer> computer(String name) {
    return computers.stream().filter(computer -> computer.name().equals(name)).findFirst();
  }

  /** A computer's address: {@code 10.12.34.11} for team 1234's .11. */
  public String ip(Computer computer) {
    return ip(team, computer.address());
  }

  /** A team's address ending in {@code last}: 10.TE.AM.last. */
  public static String ip(int team, int last) {
    return "10." + team / 100 + "." + team % 100 + "." + last;
  }

  /** Reads and checks a table from YAML; problems name the file and line. */
  public static Table parseYaml(String yaml, String source) {
    return TableYaml.parse(yaml, source);
  }

  /** Reads and checks the table in a file. */
  public static Table readYaml(Path file) throws IOException {
    return parseYaml(Files.readString(file, StandardCharsets.UTF_8), file.toString());
  }

  /** The table as JSON, as it's compiled into the robot program. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("team", team)
        .put("agentPort", agentPort)
        .put("computers", JsonValue.array(computers, Computer::toJson))
        .put("image", Computer.texts(image))
        .build();
  }

  /** A table from its JSON, checked as any table is. */
  public static Table fromJson(JsonValue json) {
    JsonValue.Obj object = json.asObject("table");
    return new Table(
        object.integer("team", 0),
        object.integer("agentPort", DEFAULT_AGENT_PORT),
        object.list("computers", Computer::fromJson),
        Computer.texts(object.objectOrEmpty("image")));
  }
}
