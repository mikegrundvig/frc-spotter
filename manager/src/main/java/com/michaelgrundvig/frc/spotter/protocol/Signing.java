package com.michaelgrundvig.frc.spotter.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Signed writes, as both sides compute them (docs/agent.md, "Signed writes"): what a write signs,
 * and the id a signature names its key by. The robot's manager signs with these and a board's agent
 * checks with them, so the two can't drift; the signature itself is Ed25519, the JDK's own.
 */
public final class Signing {
  private Signing() {}

  /**
   * A key's id: the first 16 hex characters of the SHA-256 of its X.509 encoding (the bytes between
   * {@code spotter.pub}'s PEM lines).
   */
  public static String keyId(byte[] x509) {
    return HexFormat.of().formatHex(sha256(x509)).substring(0, 16);
  }

  /**
   * What a write signs: its stream connection's challenge, its counter on that connection, its
   * method, its path (with no query), and the lowercase hex SHA-256 of its body, one to a line, in
   * UTF-8.
   *
   * @param bodyHash the SHA-256 of its body ({@link #sha256}); of no bytes when it has none
   */
  public static byte[] message(
      String challenge, long counter, String method, String path, byte[] bodyHash) {
    return (challenge
            + "\n"
            + counter
            + "\n"
            + method
            + "\n"
            + path
            + "\n"
            + HexFormat.of().formatHex(bodyHash))
        .getBytes(StandardCharsets.UTF_8);
  }

  /** The SHA-256 of some bytes. */
  public static byte[] sha256(byte[] bytes) {
    return digest().digest(bytes);
  }

  /** A SHA-256 digest, to hash a body as it's read. */
  public static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }
}
