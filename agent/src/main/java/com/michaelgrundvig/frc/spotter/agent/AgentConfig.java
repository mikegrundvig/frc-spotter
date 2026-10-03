package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The board's own settings, in {@code /etc/frc-spotter/agent.json}, for whoever owns the board; the
 * agent needs none of them. A push can't change them: the file is the installer's.
 *
 * <pre>
 * {"port": 5809, "controller": "10.12.34.2", "bind": "10.12.34.11",
 *  "acceptPushes": false, "trustedKeys": ["MCowBQYDK2VwAyEA..."]}
 * </pre>
 *
 * @param port the port it serves on
 * @param controller the one address a write is taken from; empty to find it from the computer's own
 *     address (10.TE.AM.2)
 * @param bind the address it listens on; empty for every address
 * @param acceptPushes whether the robot may push packs to it; when not, it ignores pushed packs
 * @param trustedKeys the Ed25519 public keys a write must be signed by, each its X.509 encoding in
 *     base64; none for no signatures
 */
record AgentConfig(
    int port, String controller, String bind, boolean acceptPushes, List<String> trustedKeys) {
  /** Where the settings are. */
  static final String PATH = "/etc/frc-spotter/agent.json";

  /** The keys the file may have, and no others. */
  static final Set<String> KEYS =
      Set.of("port", "controller", "bind", "acceptPushes", "trustedKeys");

  /** An IPv4 address written out: four numbers from 0 to 255, without leading zeros. */
  static final Pattern IPV4 =
      Pattern.compile(
          "((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");

  /** Nothing set. */
  static final AgentConfig DEFAULT = new AgentConfig(Protocol.PORT, "", "", true, List.of());

  AgentConfig {
    trustedKeys = List.copyOf(trustedKeys);
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
    for (String key : trustedKeys) {
      publicKey(key);
    }
  }

  /** A trusted key's text as the key it is. */
  static PublicKey publicKey(String key) {
    try {
      return KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "trusted key \"" + key + "\" isn't an Ed25519 public key in base64 (X.509)", e);
    }
  }

  /**
   * The settings from the file's text, checked: a key it doesn't know is refused, so an agent.json
   * from before 0.4.0 says so rather than half working.
   */
  static AgentConfig parse(String json) {
    Map<String, Object> o = JsonText.object(json);
    Set<String> unknown = new TreeSet<>(o.keySet());
    unknown.removeAll(KEYS);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "it may set "
              + String.join(", ", new TreeSet<>(KEYS))
              + " only, not "
              + String.join(", ", unknown)
              + " (packs are folders in /etc/frc-spotter/packs/)");
    }
    List<String> keys = new ArrayList<>();
    Object listed = o.getOrDefault("trustedKeys", List.of());
    if (!(listed instanceof List)) {
      throw new IllegalArgumentException("trustedKeys must be a list of keys");
    }
    for (Object key : (List<?>) listed) {
      if (!(key instanceof String)) {
        throw new IllegalArgumentException("trustedKeys must be a list of keys, each text");
      }
      keys.add((String) key);
    }
    Object port = o.getOrDefault("port", (long) Protocol.PORT);
    if (!(port instanceof Long)) {
      throw new IllegalArgumentException("port must be a whole number");
    }
    Object accept = o.getOrDefault("acceptPushes", true);
    if (!(accept instanceof Boolean)) {
      throw new IllegalArgumentException("acceptPushes must be true or false");
    }
    return new AgentConfig(
        (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, (Long) port)),
        text(o, "controller"),
        text(o, "bind"),
        (Boolean) accept,
        keys);
  }

  private static String text(Map<String, Object> o, String key) {
    Object value = o.getOrDefault(key, "");
    if (!(value instanceof String)) {
      throw new IllegalArgumentException(key + " must be text");
    }
    return (String) value;
  }
}
