package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One computer's configuration of its agent: the one file a team writes per computer ({@link
 * #PATH}, or {@link #DATA_PATH} where the root is read-only), and the only thing that makes one
 * computer's agent differ from another's. The agent package itself is the same everywhere.
 *
 * <p>The build writes it from the coprocessor table ({@code ./gradlew coprocessorAgentConfigs}),
 * and the robot's build compiles each computer's probes from the same file's contents ({@link
 * Packs#compile}), so the robot knows exactly what each agent checks.
 *
 * @param name the computer's name, as the robot knows it
 * @param controller the one address actions are taken from (the robot controller, 10.TE.AM.2): a
 *     check of the address, not authentication; empty for none, when nothing may act
 * @param port the port the agent serves on
 * @param packs the packs it runs besides the built-in one, in order
 * @param cameras its cameras, each with the USB port it's plugged into, in order
 * @param probes its own probes besides its packs', as written
 */
public record AgentConfig(
    String name,
    String controller,
    int port,
    List<String> packs,
    List<Camera> cameras,
    List<Pack.Written> probes) {
  /** Where a computer's configuration is, on a root that's written when the image is built. */
  public static final String PATH = "/etc/frc-spotter/agent.json";

  /** Where it is when the root is read-only and the team writes it later. */
  public static final String DATA_PATH = "/data/frc-spotter/agent.json";

  /** Where a camera's stable paths are: what {@link Camera#port} names in. */
  public static final String BY_PATH = "/dev/v4l/by-path/";

  private static final Pattern IPV4 =
      Pattern.compile(
          "((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");

  /** No configuration: the agent alone, with the built-in pack, taking no actions. */
  public static final AgentConfig NONE =
      new AgentConfig("", "", Table.DEFAULT_AGENT_PORT, List.of(), List.of(), List.of());

  /**
   * A camera, by its name and port.
   *
   * @param name its name, as the robot code and the vision software know it
   * @param port its {@code /dev/v4l/by-path/} entry, which names the USB port it's plugged into;
   *     empty when the table doesn't say
   */
  public record Camera(String name, String port) {
    public Camera {
      String problem = Computer.cameraProblem(name);
      if (problem != null) {
        throw new IllegalArgumentException(problem);
      }
      if (!port.isEmpty()) {
        port = port.startsWith("/") ? port : BY_PATH + port;
        com.michaelgrundvig.frc.spotter.probes.Check.checkPath(port, "camera " + name + "'s port");
        if (!port.startsWith(BY_PATH)) {
          throw new IllegalArgumentException(
              "camera " + name + "'s port " + port + " isn't a " + BY_PATH + " entry");
        }
      }
    }
  }

  public AgentConfig {
    packs = List.copyOf(packs);
    cameras = List.copyOf(cameras);
    probes = List.copyOf(probes);
    if (!name.isEmpty()) {
      String problem = Computer.nameProblem(name);
      if (problem != null) {
        throw new IllegalArgumentException(problem);
      }
    }
    if (!controller.isEmpty() && !IPV4.matcher(controller).matches()) {
      throw new IllegalArgumentException(
          "controller \"" + controller + "\" isn't an IPv4 address, such as 10.12.34.2");
    }
    String portProblem = Computer.portProblem(port);
    if (portProblem != null) {
      throw new IllegalArgumentException(portProblem);
    }
    for (String pack : packs) {
      String problem = Computer.packProblem(pack);
      if (problem != null) {
        throw new IllegalArgumentException(problem);
      }
    }
    Set<String> names = new HashSet<>();
    for (Camera camera : cameras) {
      if (!names.add(camera.name())) {
        throw new IllegalArgumentException("camera " + camera.name() + " is listed twice");
      }
    }
  }

  /** A computer's configuration, as the table describes it. */
  public static AgentConfig of(Table table, Computer computer) {
    List<Camera> cameras = new ArrayList<>();
    for (String camera : computer.cameras()) {
      cameras.add(new Camera(camera, computer.ports().getOrDefault(camera, "")));
    }
    return new AgentConfig(
        computer.name(),
        Table.ip(table.team(), AgentApi.CONTROLLER),
        computer.agentPort(),
        computer.packs(),
        cameras,
        computer.probes());
  }

  /** The cameras' names, in order. */
  public List<String> cameraNames() {
    return cameras.stream().map(Camera::name).toList();
  }

  /** The configuration as JSON: the file. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("controller", controller)
        .put("port", port)
        .put("packs", packs)
        .put(
            "cameras",
            JsonValue.array(
                cameras,
                camera ->
                    JsonValue.Obj.builder()
                        .put("name", camera.name())
                        .put("port", camera.port())
                        .build()))
        .put("probes", JsonValue.array(probes, Pack.Written::toJson))
        .build();
  }

  /** The file's text: {@link Json#pretty}. */
  public String text() {
    return Json.pretty(toJson());
  }

  /** A configuration from its JSON, checked; {@code source} names it in messages. */
  public static AgentConfig fromJson(JsonValue json, String source) {
    JsonValue.Obj o = json.asObject(source);
    return new AgentConfig(
        o.string("name", ""),
        o.string("controller", ""),
        o.integer("port", Table.DEFAULT_AGENT_PORT),
        o.strings("packs"),
        o.list(
            "cameras",
            camera -> {
              JsonValue.Obj c = camera.asObject("a camera");
              return new Camera(c.string("name", ""), c.string("port", ""));
            }),
        o.list("probes", probe -> Pack.Written.fromJson(probe, source)));
  }

  /** A configuration from the file's text, checked. */
  public static AgentConfig parse(String json, String source) {
    return fromJson(Json.parse(json), source);
  }
}
