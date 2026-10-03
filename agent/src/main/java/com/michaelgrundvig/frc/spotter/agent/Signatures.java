package com.michaelgrundvig.frc.spotter.agent;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Signed writes, for a board that lists trusted keys in its {@code agent.json}: only the holder of
 * a trusted private key can change it. Reading stays open; signatures cover what changes a board
 * (starting an action, cancelling a run, pushing packs).
 *
 * <p>No replays, and no clocks needed. Each stream connection gets a fresh random challenge (its
 * response's {@code Spotter-Challenge}). A write signs, with Ed25519, the UTF-8 bytes of
 *
 * <pre>
 * challenge \n counter \n method \n path \n lowercase hex SHA-256 of the body
 * </pre>
 *
 * and says {@code Spotter-Signature: <key id> <counter> <signature>}, the signature in base64. The
 * key id is the first 16 hex characters of the SHA-256 of the key's X.509 encoding (its bytes as
 * {@code agent.json} lists them, in base64). The agent checks the signature against that key, that
 * the challenge is a current connection's, and that the counter (from 1) is higher than any it has
 * seen on that connection. A captured request is useless twice, and useless after a reconnect.
 */
final class Signatures {
  /** The length of a challenge, in random bytes. */
  static final int CHALLENGE_BYTES = 32;

  /** A refused write: why. */
  static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;

    Rejected(String reason) {
      super(reason);
    }
  }

  private final Map<String, PublicKey> keys = new LinkedHashMap<>();
  private final Map<String, Long> challenges = new LinkedHashMap<>();
  private final SecureRandom random = new SecureRandom();

  /**
   * @param trustedKeys the keys a write must be signed by, as {@code agent.json} lists them; none
   *     for no signatures
   */
  Signatures(List<String> trustedKeys) {
    for (String key : trustedKeys) {
      keys.put(keyId(key), AgentConfig.publicKey(key));
    }
  }

  /** A key's id: the first 16 hex characters of the SHA-256 of its X.509 encoding. */
  static String keyId(String key) {
    byte[] encoded = Base64.getDecoder().decode(key);
    return HexFormat.of().formatHex(sha256(encoded)).substring(0, 16);
  }

  /** Whether writes must be signed: the board lists trusted keys. */
  boolean required() {
    return !keys.isEmpty();
  }

  /** A new connection's challenge, current until it {@link #close}s. */
  synchronized String open() {
    byte[] bytes = new byte[CHALLENGE_BYTES];
    random.nextBytes(bytes);
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    challenges.put(challenge, 0L);
    return challenge;
  }

  /** A connection gone: its challenge is no longer current. */
  synchronized void close(String challenge) {
    challenges.remove(challenge);
  }

  /** What a write signs. */
  static byte[] signed(String challenge, long counter, String method, String path, byte[] hash) {
    return (challenge
            + "\n"
            + counter
            + "\n"
            + method
            + "\n"
            + path
            + "\n"
            + HexFormat.of().formatHex(hash))
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Checks a write's signature, and takes its counter.
   *
   * @param header its {@code Spotter-Signature}; null when it has none
   * @param bodyHash the SHA-256 of its body
   * @throws Rejected when it isn't signed, or not as it must be
   */
  synchronized void verify(String method, String path, byte[] bodyHash, @Nullable String header)
      throws Rejected {
    if (header == null || header.isBlank()) {
      throw new Rejected("this board requires signed writes, and this one isn't signed");
    }
    String[] parts = header.strip().split(" +");
    if (parts.length != 3) {
      throw new Rejected("Spotter-Signature is <key id> <counter> <signature>");
    }
    PublicKey key = keys.get(parts[0]);
    if (key == null) {
      throw new Rejected("key " + parts[0] + " isn't one this board trusts");
    }
    long counter;
    byte[] signature;
    try {
      counter = Long.parseLong(parts[1]);
      signature = Base64.getDecoder().decode(parts[2]);
    } catch (IllegalArgumentException e) {
      throw new Rejected("Spotter-Signature is <key id> <counter> <signature in base64>");
    }
    if (challenges.isEmpty()) {
      throw new Rejected("no stream is connected, so there's no challenge to sign");
    }
    for (Map.Entry<String, Long> challenge : challenges.entrySet()) {
      if (valid(key, signed(challenge.getKey(), counter, method, path, bodyHash), signature)) {
        if (counter <= challenge.getValue()) {
          throw new Rejected(
              "counter "
                  + counter
                  + " was used already on this connection (the last was "
                  + challenge.getValue()
                  + ")");
        }
        challenge.setValue(counter);
        return;
      }
    }
    throw new Rejected(
        "the signature doesn't match this request, signed by key "
            + parts[0]
            + " over a current connection's challenge");
  }

  private static boolean valid(PublicKey key, byte[] message, byte[] signature) {
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(key);
      verifier.update(message);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /** The SHA-256 of some bytes. */
  static byte[] sha256(byte[] bytes) {
    return digest().digest(bytes);
  }

  /** A SHA-256 digest, to hash a body as it's read. */
  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }
}
