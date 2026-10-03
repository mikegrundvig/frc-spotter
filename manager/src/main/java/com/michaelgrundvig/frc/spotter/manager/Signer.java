package com.michaelgrundvig.frc.spotter.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The robot's signing key pair, for boards that require signed writes (docs/agent.md, "Signed
 * writes"), as openssl makes it (docs/robot.md, "Signing"):
 *
 * <pre>
 * openssl genpkey -algorithm ed25519 -out spotter.key
 * openssl pkey -in spotter.key -pubout -out spotter.pub
 * </pre>
 *
 * The private key is PKCS#8 in PEM, the public key X.509 in PEM, each read with Java's own key
 * specs. The public key's X.509 encoding, in base64, is what a board lists in its {@code
 * agent.json}, and its id is what each signature names.
 *
 * <p>A write signs the UTF-8 bytes of the stream connection's challenge, the counter, the method,
 * the path, and the lowercase hex SHA-256 of the body, joined by {@code \n}, and says {@code
 * Spotter-Signature: <key id> <counter> <signature>}.
 */
final class Signer {
  private final PrivateKey key;
  private final String publicKey;
  private final String id;

  private Signer(PrivateKey key, PublicKey publicKey) {
    this.key = key;
    this.publicKey = Base64.getEncoder().encodeToString(publicKey.getEncoded());
    this.id = HexFormat.of().formatHex(sha256(publicKey.getEncoded())).substring(0, 16);
  }

  /**
   * The key pair in two files: the private key, and its public key.
   *
   * @throws NoSuchFileException when either isn't there
   * @throws IOException when either can't be read, isn't an Ed25519 key in PEM, or the public key
   *     isn't the private key's
   */
  static Signer read(Path privateFile, Path publicFile) throws IOException {
    PrivateKey key;
    PublicKey publicKey;
    try {
      KeyFactory keys = KeyFactory.getInstance("Ed25519");
      key = keys.generatePrivate(new PKCS8EncodedKeySpec(pem(privateFile, "PRIVATE KEY")));
      publicKey = keys.generatePublic(new X509EncodedKeySpec(pem(publicFile, "PUBLIC KEY")));
    } catch (GeneralSecurityException e) {
      throw new IOException("it isn't an Ed25519 key pair, as openssl writes one: " + e, e);
    }
    if (!pair(key, publicKey)) {
      throw new IOException(publicFile + " isn't the public key of " + privateFile);
    }
    return new Signer(key, publicKey);
  }

  /** A PEM file's bytes: what's between its {@code -----BEGIN <kind>-----} and its end. */
  private static byte[] pem(Path file, String kind) throws IOException {
    String text = Files.readString(file, StandardCharsets.US_ASCII);
    String begin = "-----BEGIN " + kind + "-----";
    String end = "-----END " + kind + "-----";
    int from = text.indexOf(begin);
    int to = text.indexOf(end);
    if (from < 0 || to < from) {
      throw new IOException(file + " isn't a " + kind.toLowerCase(Locale.ROOT) + " in PEM");
    }
    try {
      return Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to));
    } catch (IllegalArgumentException e) {
      throw new IOException(file + " isn't base64 between its PEM lines: " + e.getMessage(), e);
    }
  }

  /** Whether a public key is a private key's: what one signs, the other verifies. */
  private static boolean pair(PrivateKey key, PublicKey publicKey) {
    try {
      byte[] message = "spotter".getBytes(StandardCharsets.UTF_8);
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(key);
      signer.update(message);
      byte[] signature = signer.sign();
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(message);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /** Its public key, X.509 in base64: what a board's {@code agent.json} lists. */
  String publicKey() {
    return publicKey;
  }

  /** Its id, as each signature names it: the first 16 hex of the public key's SHA-256. */
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
}
