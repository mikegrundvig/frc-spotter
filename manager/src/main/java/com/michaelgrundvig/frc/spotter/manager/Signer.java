package com.michaelgrundvig.frc.spotter.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The robot's signing key, for boards that require signed writes (docs/agent.md, "Signed writes"):
 * an Ed25519 private key, PKCS#8, in PEM ({@code -----BEGIN PRIVATE KEY-----}, as {@code openssl
 * genpkey -algorithm ed25519} writes it) or as its base64 alone. Its public key, which a board
 * lists in its {@code agent.json} and whose id each signature names, is worked out from it.
 *
 * <p>A write signs the UTF-8 bytes of the stream connection's challenge, the counter, the method,
 * the path, and the lowercase hex SHA-256 of the body, joined by {@code \n}, and says {@code
 * Spotter-Signature: <key id> <counter> <signature>}.
 */
final class Signer {
  private final PrivateKey key;
  private final String publicKey;
  private final String id;

  private Signer(PrivateKey key, String publicKey) {
    this.key = key;
    this.publicKey = publicKey;
    this.id = keyId(publicKey);
  }

  /**
   * The key in a file.
   *
   * @throws NoSuchFileException when there's no file
   * @throws IOException when it can't be read, or isn't an Ed25519 private key
   */
  static Signer read(Path file) throws IOException {
    String text = Files.readString(file, StandardCharsets.US_ASCII);
    String base64 = text.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
    try {
      PrivateKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
      return new Signer(key, publicKey(key));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IOException("it isn't an Ed25519 private key, PKCS#8 in PEM: " + e.getMessage(), e);
    }
  }

  /** A new key, at random: for tests, which generate throwaway keys and commit none. */
  static Signer generate() {
    try {
      KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      return new Signer(
          pair.getPrivate(), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("every Java since 15 has Ed25519", e);
    }
  }

  /** Writes the private key to a file, PKCS#8 in PEM: for tests. */
  void write(Path file) throws IOException {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
    Files.writeString(
        file, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
  }

  /**
   * An Ed25519 private key's public key, X.509 in base64, as {@code agent.json} lists it. Java has
   * no call for it, so it's worked out as the key's own generation does: Ed25519's private key is
   * 32 random bytes, and a generator given those bytes as its randomness makes that key's pair.
   */
  static String publicKey(PrivateKey key) throws GeneralSecurityException {
    byte[] seed =
        ((EdECPrivateKey) key)
            .getBytes()
            .orElseThrow(() -> new GeneralSecurityException("the key's bytes can't be read"));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
    generator.initialize(NamedParameterSpec.ED25519, new Seed(seed));
    KeyPair pair = generator.generateKeyPair();
    byte[] made = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElse(new byte[0]);
    if (!Arrays.equals(made, seed)) {
      throw new GeneralSecurityException("this Java's Ed25519 can't work out a public key");
    }
    return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }

  /** A key's id: the first 16 hex characters of the SHA-256 of its X.509 encoding. */
  static String keyId(String publicKey) {
    return HexFormat.of().formatHex(sha256(Base64.getDecoder().decode(publicKey))).substring(0, 16);
  }

  /** Its public key, X.509 in base64: what a board's {@code agent.json} lists. */
  String publicKey() {
    return publicKey;
  }

  /** Its id, as each signature names it. */
  String id() {
    return id;
  }

  /** A write's {@code Spotter-Signature}. */
  String header(String challenge, long counter, String method, String path, byte[] body) {
    String signed =
        challenge
            + "\n"
            + counter
            + "\n"
            + method
            + "\n"
            + path
            + "\n"
            + HexFormat.of().formatHex(sha256(body));
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(signed.getBytes(StandardCharsets.UTF_8));
      return id + " " + counter + " " + Base64.getEncoder().encodeToString(signer.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("an Ed25519 key that can't sign", e);
    }
  }

  static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }

  /** Randomness that's a key's own bytes, once. */
  private static final class Seed extends SecureRandom {
    private static final long serialVersionUID = 1L;
    private final byte[] seed;

    Seed(byte[] seed) {
      this.seed = seed.clone();
    }

    @Override
    public void nextBytes(byte[] bytes) {
      if (bytes.length != seed.length) {
        throw new IllegalStateException("asked for " + bytes.length + " bytes, not a key's 32");
      }
      System.arraycopy(seed, 0, bytes, 0, seed.length);
    }
  }
}
