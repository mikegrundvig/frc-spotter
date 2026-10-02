package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 */
public record Computer(String name, int address, Board board, List<String> cameras, int agentPort) {
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

  public Computer {
    cameras = List.copyOf(cameras);
    List<String> problems = new ArrayList<>();
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
        .build();
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
        object.integer("agentPort", Table.DEFAULT_AGENT_PORT));
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
