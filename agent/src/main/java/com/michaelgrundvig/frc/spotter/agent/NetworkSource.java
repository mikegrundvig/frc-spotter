package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.NetworkLink;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Each network interface's link but loopback's, from {@code /sys/class/net}: its state, the speed
 * it negotiated, how often its carrier changed, and its errors, all as the kernel counts them since
 * boot. A virtual interface (a container's, a bridge) has no speed; that's -1, not a problem.
 */
final class NetworkSource {
  static final String NETWORK = "/sys/class/net";

  /** The most interfaces reported: a coprocessor has one or two. */
  static final int MAX_LINKS = 16;

  private final Host host;

  NetworkSource(Host host) {
    this.host = host;
  }

  List<NetworkLink> read() throws IOException {
    List<NetworkLink> links = new ArrayList<>();
    for (String name : host.list(NETWORK)) {
      if (name.equals("lo") || links.size() == MAX_LINKS) {
        continue;
      }
      String folder = NETWORK + "/" + name + "/";
      if (!host.exists(folder + "operstate")) {
        continue; // not an interface (bonding_masters, say)
      }
      int speed = (int) number(folder + "speed").orElse(-1L).longValue();
      links.add(
          new NetworkLink(
              name,
              host.exists(folder + "device"),
              host.line(folder + "operstate").orElse("unknown"),
              speed < 0 ? -1 : speed,
              number(folder + "carrier_changes").orElse(0L),
              number(folder + "statistics/rx_errors").orElse(0L),
              number(folder + "statistics/tx_errors").orElse(0L)));
    }
    return links;
  }

  /**
   * A number the kernel writes, or none: a file a driver doesn't give, or one it refuses to read (a
   * link that's down answers {@code speed} with an error).
   */
  private Optional<Long> number(String file) {
    try {
      return host.line(file).map(Long::parseLong);
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }
}
