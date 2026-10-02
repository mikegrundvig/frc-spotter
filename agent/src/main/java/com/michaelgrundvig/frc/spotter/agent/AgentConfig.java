package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What {@code /etc/frc-spotter/agent.json} overrides, for an unusual setup: the port, the
 * controller's address, and the address it listens on. The agent needs none of it: its name is its
 * hostname, the controller is 10.TE.AM.2 on its own 10.TE.AM.x network, and what it checks is its
 * packs.
 *
 * <pre>
 * {"port": 5809, "controller": "10.12.34.2", "bind": "10.12.34.11"}
 * </pre>
 *
 * @param port the port it serves on
 * @param controller the one address a shutdown is taken from; empty to find it from the computer's
 *     own address
 * @param bind the address it listens on; empty for every address
 */
record AgentConfig(int port, String controller, String bind) {
  /** Where the overrides are. */
  static final String PATH = "/etc/frc-spotter/agent.json";

  /** The keys the file may have, and no others. */
  static final Set<String> KEYS = Set.of("port", "controller", "bind");

  /** An IPv4 address written out: four numbers from 0 to 255, without leading zeros. */
  static final Pattern IPV4 =
      Pattern.compile(
          "((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");

  /** Nothing overridden. */
  static final AgentConfig DEFAULT = new AgentConfig(AgentApi.PORT, "", "");

  AgentConfig {
    if (port < 1024 || port > 65535) {
      throw new IllegalArgumentException(
          "port " + port + " is out of range: 1024 to 65535 (5800-5810 is FRC's team range)");
    }
    for (String address : List.of(controller, bind)) {
      if (!address.isEmpty() && !IPV4.matcher(address).matches()) {
        throw new IllegalArgumentException(
            "\"" + address + "\" isn't an IPv4 address written out, such as 10.12.34.2");
      }
    }
  }

  /**
   * The overrides from the file's text, checked: a key it doesn't know is refused, so an agent.json
   * from before 0.3.0 (with its packs and cameras) says so rather than half working.
   */
  static AgentConfig parse(String json) {
    JsonValue.Obj o = Json.parse(json).asObject(PATH);
    Set<String> unknown = new TreeSet<>(o.members().keySet());
    unknown.removeAll(KEYS);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "it may set port, controller, and bind only, not "
              + String.join(", ", unknown)
              + " (packs are files in /etc/frc-spotter/packs/)");
    }
    return new AgentConfig(
        o.integer("port", AgentApi.PORT), o.string("controller", ""), o.string("bind", ""));
  }
}
