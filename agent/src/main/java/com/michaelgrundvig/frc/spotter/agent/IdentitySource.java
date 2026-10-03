package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which computer this is, read from the computer itself, with nothing configured: its hostname, its
 * addresses, the wired interface's MAC address, this boot, and {@code /etc/os-release} as it is.
 */
final class IdentitySource {
  static final String HOSTNAME = "/proc/sys/kernel/hostname";
  static final String BOOT_ID = "/proc/sys/kernel/random/boot_id";
  static final String NETWORK = "/sys/class/net";

  /** Where os-release(5) is, and where to look when it isn't. */
  static final String OS_RELEASE = "/etc/os-release";

  static final String OS_RELEASE_FALLBACK = "/usr/lib/os-release";

  /** The most keys of os-release passed on, and the longest value: a real one has a dozen. */
  static final int MAX_OS_RELEASE_KEYS = 64;

  static final int MAX_OS_RELEASE_VALUE = 512;

  /** An os-release key: a shell variable's name. */
  private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final Host host;

  IdentitySource(Host host) {
    this.host = host;
  }

  /** Which computer this is, now. */
  Spotter.Identity read() throws IOException {
    Spotter.Identity identity =
        Spotter.Identity.newInstance().setHostname(hostname()).setMac(mac()).setBootId(bootId());
    for (String address : host.addresses()) {
      identity.addAddresses(address);
    }
    osRelease()
        .forEach(
            (key, value) ->
                identity.addOsRelease(
                    Spotter.Identity.OsReleaseEntry.newInstance().setKey(key).setValue(value)));
    return identity;
  }

  /** Its hostname, which is the manager's name for it; empty when it can't be read. */
  String hostname() throws IOException {
    return host.line(HOSTNAME).orElse("");
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

  /**
   * {@code /etc/os-release} (else {@code /usr/lib/os-release}, as os-release(5) says), every key
   * with its value unquoted; empty when the computer has neither.
   */
  Map<String, String> osRelease() throws IOException {
    Optional<String> text = host.read(OS_RELEASE);
    if (text.isEmpty()) {
      text = host.read(OS_RELEASE_FALLBACK);
    }
    return text.map(IdentitySource::parseOsRelease).orElse(Map.of());
  }

  /**
   * os-release's lines, read as os-release(5) writes them: {@code KEY=value}, the value maybe in
   * single or double quotes (where a backslash escapes {@code $ " \ `}), comments and blank lines
   * skipped. A line that isn't an assignment is skipped too. Nothing is read into a value.
   */
  static Map<String, String> parseOsRelease(String text) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String raw : text.split("\n")) {
      String line = raw.strip();
      int equals = line.indexOf('=');
      if (line.isEmpty() || line.startsWith("#") || equals <= 0) {
        continue;
      }
      String key = line.substring(0, equals);
      if (!KEY.matcher(key).matches()) {
        continue;
      }
      String value = unquote(line.substring(equals + 1));
      if (values.size() < MAX_OS_RELEASE_KEYS || values.containsKey(key)) {
        values.put(
            key,
            value.length() <= MAX_OS_RELEASE_VALUE
                ? value
                : value.substring(0, MAX_OS_RELEASE_VALUE));
      }
    }
    return values;
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
      return value.substring(1, value.length() - 1);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      String inner = value.substring(1, value.length() - 1);
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < inner.length(); i++) {
        char c = inner.charAt(i);
        if (c == '\\' && i + 1 < inner.length() && "$\"\\`".indexOf(inner.charAt(i + 1)) >= 0) {
          c = inner.charAt(++i);
        }
        out.append(c);
      }
      return out.toString();
    }
    return value;
  }
}
