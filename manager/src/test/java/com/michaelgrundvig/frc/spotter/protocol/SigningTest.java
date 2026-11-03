package com.michaelgrundvig.frc.spotter.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Signed writes' format, as docs/agent.md gives it. */
class SigningTest {
  @Test
  void aWriteSignsItsChallengeCounterMethodPathAndBodyHashOneToALine() {
    byte[] hash = Signing.sha256("bundle".getBytes(StandardCharsets.UTF_8));
    assertThat(
            new String(
                Signing.message("abc", 7, "POST", "/v2/packs", hash), StandardCharsets.UTF_8))
        .isEqualTo("abc\n7\nPOST\n/v2/packs\n" + HexFormat.of().formatHex(hash));
    // No body: the hash of no bytes.
    assertThat(HexFormat.of().formatHex(Signing.sha256(new byte[0])))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void aKeysIdIsTheFirst16HexOfItsEncodingsSha256() {
    byte[] encoded = "an X.509 encoding".getBytes(StandardCharsets.UTF_8);
    assertThat(Signing.keyId(encoded))
        .hasSize(16)
        .isEqualTo(HexFormat.of().formatHex(Signing.sha256(encoded)).substring(0, 16));
  }
}
