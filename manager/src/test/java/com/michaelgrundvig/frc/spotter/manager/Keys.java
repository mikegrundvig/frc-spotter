package com.michaelgrundvig.frc.spotter.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Throwaway Ed25519 key pairs for tests, made as the tests run (none is ever committed), and
 * written as openssl writes them: the private key PKCS#8 in PEM, the public key X.509 in PEM.
 */
final class Keys {
  private Keys() {}

  /** A new pair, at random. */
  static KeyPair pair() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("every Java since 15 has Ed25519", e);
    }
  }

  /**
   * Writes a pair as {@code spotter.key} and {@code spotter.pub} in a folder: the private key's.
   */
  static Path write(KeyPair pair, Path folder) throws IOException {
    Path key = folder.resolve("spotter.key");
    Files.writeString(key, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    Files.writeString(
        folder.resolve("spotter.pub"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
    return key;
  }

  /** A public key as a board's {@code agent.json} lists it: X.509, in base64. */
  static String trusted(KeyPair pair) {
    return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }

  static String pem(String kind, byte[] der) {
    return "-----BEGIN "
        + kind
        + "-----\n"
        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
        + "\n-----END "
        + kind
        + "-----\n";
  }
}
