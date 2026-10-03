package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Signed writes: a challenge per connection, a rising counter, and the team's key. */
class SignaturesTest {
  /** A key pair, and what a robot's manager does with it. */
  static final class Signer {
    final KeyPair keys;
    final String publicKey;
    final String keyId;

    Signer() throws Exception {
      keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
      keyId = Signatures.keyId(publicKey);
    }

    String sign(String challenge, long counter, String method, String path, byte[] body)
        throws Exception {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(keys.getPrivate());
      signer.update(Signatures.signed(challenge, counter, method, path, Signatures.sha256(body)));
      return keyId + " " + counter + " " + Base64.getEncoder().encodeToString(signer.sign());
    }
  }

  private static final byte[] BODY = "front camera".getBytes(StandardCharsets.UTF_8);
  private static final String PATH = "/v2/actions/team.ok";

  @Test
  void aWriteSignedOverTheChallengeWithARisingCounterIsTaken() throws Exception {
    Signer team = new Signer();
    Signatures signatures = new Signatures(List.of(team.publicKey));
    assertThat(signatures.required()).isTrue();
    assertThat(team.keyId).matches("[0-9a-f]{16}");
    String challenge = signatures.open();
    assertThat(Base64.getUrlDecoder().decode(challenge)).hasSize(Signatures.CHALLENGE_BYTES);
    byte[] hash = Signatures.sha256(BODY);
    signatures.verify("POST", PATH, hash, team.sign(challenge, 1, "POST", PATH, BODY));
    signatures.verify("POST", PATH, hash, team.sign(challenge, 5, "POST", PATH, BODY));
    // Replayed, or not higher: refused.
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () ->
                    signatures.verify(
                        "POST", PATH, hash, team.sign(challenge, 5, "POST", PATH, BODY))))
        .hasMessage("counter 5 was used already on this connection (the last was 5)");
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () ->
                    signatures.verify(
                        "POST", PATH, hash, team.sign(challenge, 3, "POST", PATH, BODY))))
        .hasMessageStartingWith("counter 3 was used already");
  }

  @Test
  void aWriteOfAnotherBodyMethodPathOrKeyIsRefused() throws Exception {
    Signer team = new Signer();
    Signer other = new Signer();
    Signatures signatures = new Signatures(List.of(team.publicKey));
    String challenge = signatures.open();
    byte[] hash = Signatures.sha256(BODY);
    String signed = team.sign(challenge, 1, "POST", PATH, BODY);
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () -> signatures.verify("POST", PATH, Signatures.sha256(new byte[0]), signed)))
        .hasMessageStartingWith("the signature doesn't match this request");
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () -> signatures.verify("POST", "/v2/actions/core.reboot", hash, signed)))
        .hasMessageStartingWith("the signature doesn't match");
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () ->
                    signatures.verify(
                        "POST", PATH, hash, other.sign(challenge, 1, "POST", PATH, BODY))))
        .hasMessage("key " + other.keyId + " isn't one this board trusts");
    // The right key's id, another key's signature.
    String forged = team.keyId + " 1 " + other.sign(challenge, 1, "POST", PATH, BODY).split(" ")[2];
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class, () -> signatures.verify("POST", PATH, hash, forged)))
        .hasMessageStartingWith("the signature doesn't match");
  }

  @Test
  void anUnsignedOrMalformedWriteIsRefusedWithWhy() throws Exception {
    Signer team = new Signer();
    Signatures signatures = new Signatures(List.of(team.publicKey));
    signatures.open();
    byte[] hash = Signatures.sha256(BODY);
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class, () -> signatures.verify("POST", PATH, hash, null)))
        .hasMessage("this board requires signed writes, and this one isn't signed");
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class, () -> signatures.verify("POST", PATH, hash, "a b")))
        .hasMessage("Spotter-Signature is <key id> <counter> <signature>");
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () -> signatures.verify("POST", PATH, hash, team.keyId + " one two")))
        .hasMessageContaining("<signature in base64>");
  }

  @Test
  void aChallengeIsUselessAfterItsConnectionCloses() throws Exception {
    Signer team = new Signer();
    Signatures signatures = new Signatures(List.of(team.publicKey));
    byte[] hash = Signatures.sha256(BODY);
    String old = signatures.open();
    signatures.close(old);
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () -> signatures.verify("POST", PATH, hash, team.sign(old, 1, "POST", PATH, BODY))))
        .hasMessage("no stream is connected, so there's no challenge to sign");
    String fresh = signatures.open();
    assertThat(fresh).isNotEqualTo(old);
    assertThat(
            catchThrowableOfType(
                Signatures.Rejected.class,
                () -> signatures.verify("POST", PATH, hash, team.sign(old, 1, "POST", PATH, BODY))))
        .hasMessageStartingWith("the signature doesn't match");
    assertThatCode(
            () -> signatures.verify("POST", PATH, hash, team.sign(fresh, 1, "POST", PATH, BODY)))
        .doesNotThrowAnyException();
    assertThat(new Signatures(List.of()).required()).isFalse();
  }
}
