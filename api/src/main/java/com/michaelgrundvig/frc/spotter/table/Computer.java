package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One coprocessor in the table: a vision computer running PhotonVision.
 *
 * @param name its hostname, and its name everywhere else: lowercase letters, digits, and hyphens,
 *     starting with a letter, at most 63 characters
 * @param address the last number of its address, 10.TE.AM.x: 6 to 19, FRC's static range for
 *     devices on the robot (11 and up by convention)
 * @param board the board it runs on
 * @param cameras the PhotonVision camera names it runs (its role): the names robot code gives
 *     {@code PhotonCamera}
 * @param agentPort the port its health agent serves on
 * @param packs the packs it runs besides the built-in one, in order (coprocessor/README.md,
 *     "Packs")
 * @param probes the probes the table gives it besides its packs', as written
 * @param ports where each camera plugs in, by camera: its {@code /dev/v4l/by-path/} entry (with or
 *     without that folder); a camera without one has no port to check
 */
public record Computer(
    String name,
    int address,
    Board board,
    List<String> cameras,
    int agentPort,
    List<String> packs,
    List<Pack.Written> probes,
    Map<String, String> ports) {
  /** What a computer's name may be: a hostname's single label, lowercase. */
  public static final Pattern NAME = Pattern.compile("[a-z]([a-z0-9-]{0,61}[a-z0-9])?");

  /**
   * The lowest address a computer may have: 10.TE.AM.6. FRC's network gives static addresses .6 to
   * .19 to devices on the robot (WPILib's "IP Configurations"); .20 to .199 are the field's DHCP
   * pool, and .200 to .219 the radio's.
   */
  public static final int FIRST_ADDRESS = 6;

  /** The highest address a computer may have: 10.TE.AM.19. */
  public static final int LAST_ADDRESS = 19;

  /**
   * The first of PhotonVision's camera-stream ports: its streams start at 1181, two ports per
   * camera (PhotonVision's networking docs).
   */
  public static final int FIRST_STREAM_PORT = 1181;

  /** The last port kept for PhotonVision's camera streams: ten cameras' worth. */
  public static final int LAST_STREAM_PORT = 1200;

  /** The longest a camera's name may be. */
  public static final int MAX_CAMERA_NAME = 64;

  /** A computer with no packs or probes of its own. */
  public Computer(String name, int address, Board board, List<String> cameras, int agentPort) {
    this(name, address, board, cameras, agentPort, List.of(), List.of(), Map.of());
  }

  public Computer {
    cameras = List.copyOf(cameras);
    packs = List.copyOf(packs);
    probes = List.copyOf(probes);
    ports = Collections.unmodifiableMap(new LinkedHashMap<>(ports));
    List<String> problems = new ArrayList<>();
    for (Map.Entry<String, String> port : ports.entrySet()) {
      if (!cameras.contains(port.getKey())) {
        problems.add(
            "computer "
                + name
                + " gives a port for "
                + port.getKey()
                + ", which isn't one of its cameras");
      } else {
        try {
          new AgentConfig.Camera(port.getKey(), port.getValue());
        } catch (IllegalArgumentException e) {
          problems.add(e.getMessage());
        }
      }
    }
    for (String pack : packs) {
      addIfPresent(problems, packProblem(pack));
    }
    if (new HashSet<>(packs).size() != packs.size()) {
      problems.add("computer " + name + " lists a pack twice: " + packs);
    }
    addIfPresent(problems, nameProblem(name));
    addIfPresent(problems, addressProblem(address));
    for (String camera : cameras) {
      addIfPresent(problems, cameraProblem(camera));
    }
    if (new HashSet<>(cameras).size() != cameras.size()) {
      problems.add("computer " + name + " lists a camera twice: " + cameras);
    }
    addIfPresent(problems, portProblem(agentPort));
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", problems));
    }
  }

  /** What's wrong with a computer's name, or null if nothing. */
  public static @Nullable String nameProblem(String name) {
    if (NAME.matcher(name).matches()) {
      return null;
    }
    return "name \""
        + name
        + "\" isn't a hostname: use lowercase letters, digits, and hyphens, starting with a letter"
        + " and not ending with a hyphen, at most 63 characters";
  }

  /** What's wrong with a pack's name in a computer's list, or null if nothing. */
  public static @Nullable String packProblem(String pack) {
    if (pack.equals(Pack.BUILTIN) || pack.equals(Packs.TABLE)) {
      return "pack " + pack + " needn't be listed: every computer runs it";
    }
    if (!Pack.NAME.matcher(pack).matches()) {
      return "pack \"" + pack + "\" isn't a pack's name: lowercase letters, digits, and hyphens";
    }
    return null;
  }

  /** What's wrong with a computer's address, or null if nothing. */
  public static @Nullable String addressProblem(int address) {
    if (address >= FIRST_ADDRESS && address <= LAST_ADDRESS) {
      return null;
    }
    return "address "
        + address
        + " is out of range: the last number of 10.TE.AM.x, from "
        + FIRST_ADDRESS
        + " to "
        + LAST_ADDRESS
        + ", FRC's static range for devices on the robot (11 and up by convention)";
  }

  /** What's wrong with a camera's name, or null if nothing. */
  public static @Nullable String cameraProblem(String camera) {
    if (camera.isBlank()) {
      return "a camera's name is empty";
    }
    if (!camera.equals(camera.strip())) {
      return "camera \"" + camera + "\" starts or ends with a space";
    }
    if (camera.length() > MAX_CAMERA_NAME) {
      return "camera \"" + camera + "\" is longer than " + MAX_CAMERA_NAME + " characters";
    }
    if (camera.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
      return "camera \""
          + camera
          + "\" has a character other than a letter, digit, space, or punctuation (printable"
          + " ASCII)";
    }
    if (camera.indexOf('/') >= 0) {
      return "camera \""
          + camera
          + "\" has a '/'; PhotonVision publishes each camera under /photonvision/<camera>";
    }
    return null;
  }

  /**
   * What's wrong with an agent's port, or null if nothing. 5800 is PhotonVision's own page, 5810
   * NetworkTables', and 1181 to 1200 PhotonVision's camera streams.
   */
  public static @Nullable String portProblem(int port) {
    if (port < 1024 || port > 65535) {
      return "agentPort " + port + " is out of range: 1024 to 65535 (5801-5809 is the team range)";
    }
    if (port >= FIRST_STREAM_PORT && port <= LAST_STREAM_PORT) {
      return "agentPort "
          + port
          + " is taken: PhotonVision's camera streams use "
          + FIRST_STREAM_PORT
          + " and up, two ports per camera";
    }
    if (port == 5800 || port == 5810) {
      return "agentPort "
          + port
          + " is taken: "
          + (port == 5800 ? "PhotonVision's page" : "NetworkTables")
          + " uses it";
    }
    return null;
  }

  /** The computer as JSON, as the compiled table carries it. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("address", address)
        .put("board", board.id())
        .put("cameras", cameras)
        .put("agentPort", agentPort)
        .put("packs", packs)
        .put("probes", JsonValue.array(probes, Pack.Written::toJson))
        .put("ports", ports(ports))
        .build();
  }

  private static JsonValue.Obj ports(Map<String, String> ports) {
    JsonValue.Obj.Builder json = JsonValue.Obj.builder();
    ports.forEach(json::put);
    return json.build();
  }

  /** A computer from its JSON. */
  public static Computer fromJson(JsonValue json) {
    JsonValue.Obj object = json.asObject("computer");
    String boardId = object.string("board", "");
    Board board =
        Board.byId(boardId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "unknown board \"" + boardId + "\"; known: " + Board.ids()));
    return new Computer(
        object.string("name", ""),
        object.integer("address", 0),
        board,
        object.strings("cameras"),
        object.integer("agentPort", Table.DEFAULT_AGENT_PORT),
        object.strings("packs"),
        object.list("probes", probe -> Pack.Written.fromJson(probe, Table.PATH)),
        ports(object.objectOrEmpty("ports")));
  }

  private static Map<String, String> ports(JsonValue.Obj json) {
    Map<String, String> ports = new LinkedHashMap<>();
    for (String camera : json.members().keySet()) {
      ports.put(camera, json.string(camera, ""));
    }
    return ports;
  }

  private static void addIfPresent(List<String> problems, @Nullable String problem) {
    if (problem != null) {
      problems.add(problem);
    }
  }

  /** The cameras listed by more than one of these computers, each once. */
  static Set<String> sharedCameras(List<Computer> computers) {
    Set<String> seen = new HashSet<>();
    Set<String> shared = new HashSet<>();
    for (Computer computer : computers) {
      for (String camera : computer.cameras()) {
        if (!seen.add(camera)) {
          shared.add(camera);
        }
      }
    }
    return shared;
  }
}
