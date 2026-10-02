package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.table.Table;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Which image this is: the stamp file the image's stamping wrote, with this boot's ID and the
 * network interface's MAC address added.
 */
final class StampSource {
  static final String BOOT_ID = "/proc/sys/kernel/random/boot_id";
  static final String NETWORK = "/sys/class/net";

  private final Host host;

  StampSource(Host host) {
    this.host = host;
  }

  /** The stamp file as written, and the port it says to serve on. */
  record StampFile(Stamp stamp, int agentPort) {
    static final StampFile NONE = new StampFile(Stamp.NONE, Table.DEFAULT_AGENT_PORT);
  }

  /** The stamp file; none (an empty stamp) when the image has none. */
  StampFile file() throws IOException {
    Optional<String> text = host.read(AgentApi.STAMP_FILE);
    if (text.isEmpty()) {
      return StampFile.NONE;
    }
    JsonValue.Obj json = Json.parse(text.get()).asObject(AgentApi.STAMP_FILE);
    return new StampFile(Stamp.fromJson(json), json.integer("agentPort", Table.DEFAULT_AGENT_PORT));
  }

  /** The stamp, with this boot and the MAC address. */
  Stamp stamp() throws IOException {
    return file().stamp().withRuntime(bootId(), mac());
  }

  /** This boot's ID. */
  String bootId() throws IOException {
    return host.line(BOOT_ID).orElse("");
  }

  /**
   * The MAC address of the wired interface: the first physical interface (one with a device behind
   * it) that's up, or failing that the first physical one.
   */
  String mac() throws IOException {
    String first = "";
    for (String name : host.list(NETWORK)) {
      String folder = NETWORK + "/" + name;
      if (name.equals("lo") || !host.exists(folder + "/device")) {
        continue;
      }
      String address = host.line(folder + "/address").orElse("").toLowerCase(Locale.ROOT);
      if (host.line(folder + "/operstate").orElse("").equals("up")) {
        return address;
      }
      if (first.isEmpty()) {
        first = address;
      }
    }
    return first;
  }
}
