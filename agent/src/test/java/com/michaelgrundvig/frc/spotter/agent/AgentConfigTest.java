package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code agent.json}: the board owner's five settings, and nothing else. */
class AgentConfigTest {
  /** An Ed25519 public key, as agent.json lists one. */
  static final String KEY = key();

  private static String key() {
    try {
      return Base64.getEncoder()
          .encodeToString(
              KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void everySettingIsRead() {
    AgentConfig config =
        AgentConfig.parse(
            "{\"port\": 5809, \"controller\": \"10.12.34.2\", \"bind\": \"10.12.34.11\","
                + " \"acceptPushes\": false, \"trustedKeys\": [\""
                + KEY
                + "\"]}");
    assertThat(config)
        .isEqualTo(new AgentConfig(5809, "10.12.34.2", 0, "10.12.34.11", false, List.of(KEY)));
    assertThat(config.namedController()).isEqualTo("10.12.34.2");
    assertThat(AgentConfig.parse("{}")).isEqualTo(AgentConfig.DEFAULT);
    assertThat(AgentConfig.DEFAULT.port()).isEqualTo(Protocol.PORT);
    assertThat(AgentConfig.DEFAULT.acceptPushes()).isTrue();
    assertThat(AgentConfig.publicKey(KEY).getAlgorithm()).isIn("Ed25519", "EdDSA");
  }

  @Test
  void aTeamNamesItsController() {
    assertThat(AgentConfig.parse("{\"team\": 1234}").namedController()).isEqualTo("10.12.34.2");
    assertThat(AgentConfig.parse("{\"team\": 254}").namedController()).isEqualTo("10.2.54.2");
    assertThat(AgentConfig.parse("{\"team\": 12345}").namedController()).isEqualTo("10.123.45.2");
    assertThat(AgentConfig.DEFAULT.namedController()).isEmpty();
    assertThatThrownBy(() -> AgentConfig.parse("{\"team\": 30000}"))
        .hasMessage("team 30000 is out of range: 1 to 25599");
    assertThatThrownBy(() -> AgentConfig.parse("{\"team\": \"2611\"}"))
        .hasMessage("team must be a whole number, such as 1234");
    assertThatThrownBy(() -> AgentConfig.parse("{\"team\": 1234, \"controller\": \"10.12.34.2\"}"))
        .hasMessageStartingWith("it names the controller or the team, not both");
  }

  @Test
  void anythingElseIsRefusedWithWhy() {
    assertThatThrownBy(() -> AgentConfig.parse("{\"name\": \"x\"}"))
        .hasMessageContaining("not name");
    assertThatThrownBy(() -> AgentConfig.parse("[]")).hasMessage("it isn't a JSON object");
    assertThatThrownBy(() -> AgentConfig.parse("{\"port\": 80}"))
        .hasMessageContaining("out of range");
    assertThatThrownBy(() -> AgentConfig.parse("{\"port\": \"5808\"}"))
        .hasMessage("port must be a whole number");
    assertThatThrownBy(() -> AgentConfig.parse("{\"port\": 5808.5}"))
        .hasMessage("port must be a whole number");
    assertThatThrownBy(() -> AgentConfig.parse("{\"controller\": \"robot.local\"}"))
        .hasMessageContaining("isn't an IPv4 address");
    assertThatThrownBy(() -> AgentConfig.parse("{\"bind\": 1}")).hasMessage("bind must be text");
    assertThatThrownBy(() -> AgentConfig.parse("{\"acceptPushes\": \"no\"}"))
        .hasMessage("acceptPushes must be true or false");
    assertThatThrownBy(() -> AgentConfig.parse("{\"trustedKeys\": \"x\"}"))
        .hasMessage("trustedKeys must be a list of keys");
    assertThatThrownBy(() -> AgentConfig.parse("{\"trustedKeys\": [1]}"))
        .hasMessage("trustedKeys must be a list of keys, each text");
    assertThatThrownBy(() -> AgentConfig.parse("{\"trustedKeys\": [\"MCowBQ\"]}"))
        .hasMessageContaining("isn't an Ed25519 public key");
  }
}
