package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.agent.LocalAgent;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signed writes, against a real agent that requires them: the manager signs each over its stream's
 * challenge with the robot's key, which tests generate at runtime (no key is ever committed).
 * Without a key, reading still works, writes are refused, and an alert says why.
 */
class SigningTest {
  @TempDir Path dir;
  LocalAgent agent;
  Signer team;
  Path key;
  @Nullable Manager manager;

  @BeforeEach
  void aBoardThatRequiresSignatures() throws Exception {
    team = Signer.generate();
    key = dir.resolve("spotter.key");
    team.write(key);
    agent = new LocalAgent(dir.resolve("board"), "vision-front");
    agent
        .pack("team", ActionsTest.PACK)
        .script("team", "hello", ActionsTest.HELLO)
        .script("team", "wait", ActionsTest.WAIT)
        .script("team", "pager", ActionsTest.PAGER)
        .config("{\"trustedKeys\": [\"" + team.publicKey() + "\"]}")
        .start();
  }

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    agent.close();
  }

  private Board connected(Path keyFile) throws InterruptedException {
    Manager started =
        new Manager(
            Boards.robot(),
            List.of(agent.address()),
            Settings.DEFAULTS.withKey(keyFile),
            Recorder.NONE);
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(started, "described", () -> board.description().getRequiresSignatures());
    return board;
  }

  @Test
  void writesAreSignedWithTheRobotsKey() throws Exception {
    Board board = connected(key);
    Run hello = ActionsTest.done(board.run("team.hello", "Ada"));
    assertThat(hello.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    // Each write signs a higher counter: a second, and a cancel.
    Run wait = board.run("team.wait");
    for (int i = 0; i < 100 && wait.log().isEmpty(); i++) {
      Thread.sleep(50);
    }
    wait.cancel().get(10, TimeUnit.SECONDS);
    assertThat(ActionsTest.done(wait).outcome()).isEqualTo(Spotter.Outcome.OUTCOME_CANCELLED);
    assertThat(java.util.Objects.requireNonNull(manager).alerts()).isEmpty();
  }

  @Test
  void aPushIsSignedOverTheStreamItOpensForIt() throws Exception {
    Path packs = dir.resolve("deploy/spotter-packs");
    Files.createDirectories(packs.resolve("team"));
    Files.writeString(packs.resolve("team/pack.yaml"), "pack: team\nversion: 2.0.0\n");
    Manager started =
        new Manager(
            Boards.robot(),
            List.of(agent.address()),
            Settings.DEFAULTS.withKey(key).withPacks(packs),
            Recorder.NONE);
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(
        started,
        "the pushed pack",
        () ->
            board.description().getPacks().length() > 0
                && board.description().getPacks().get(0).getVersion().equals("2.0.0"));
    assertThat(agent.exits()).isEqualTo(1);
    assertThat(started.alerts()).isEmpty();
  }

  @Test
  void withoutAKeyReadingWorksWritesAreRefusedAndAnAlertSaysWhy() throws Exception {
    Path none = dir.resolve("none.key");
    Board board = connected(none);
    Manager started = java.util.Objects.requireNonNull(manager);
    assertThat(started.alerts())
        .containsExactly(
            new Alert(
                Level.WARNING,
                "",
                "no Spotter key on this controller ("
                    + none
                    + "): boards that require signatures will refuse its actions and pushes"));
    assertThat(board.log("team.log", LogQuery.latest(1)).get(10, TimeUnit.SECONDS).getEntries())
        .hasSize(1);
    Run refused = ActionsTest.done(board.run("team.hello", "Ada"));
    assertThat(refused.state()).isEqualTo(Run.State.REFUSED);
    assertThat(refused.why())
        .isEqualTo("it answered 401: this board requires signed writes, and this one isn't signed");
  }

  @Test
  void aKeyTheBoardDoesntTrustIsRefused() throws Exception {
    Path other = dir.resolve("other.key");
    Signer.generate().write(other);
    Board board = connected(other);
    Run refused = ActionsTest.done(board.run("team.hello", "Ada"));
    assertThat(refused.why())
        .startsWith("it answered 401: key ")
        .endsWith(" isn't one this board trusts");
  }

  @Test
  void aKeyThatCantBeReadIsSaid() throws Exception {
    Path garbled = dir.resolve("garbled.key");
    Files.writeString(
        garbled, "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n");
    connected(garbled);
    Manager started = java.util.Objects.requireNonNull(manager);
    assertThat(started.alerts()).hasSize(1);
    assertThat(started.alerts().get(0).text())
        .startsWith(
            "the Spotter key " + garbled + " can't be read (it isn't an Ed25519 private key")
        .endsWith("): boards that require signatures will refuse its actions and pushes");
  }

  @Test
  void aKeysPublicKeyIsWorkedOutFromItAsTheBoardListsIt() throws Exception {
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String expected = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    assertThat(Signer.publicKey(pair.getPrivate())).isEqualTo(expected);
    assertThat(expected).startsWith("MCowBQYDK2VwAyEA");

    // As openssl writes it: PEM, read back to the same key.
    Path file = dir.resolve("written.key");
    team.write(file);
    assertThat(Files.readString(file)).startsWith("-----BEGIN PRIVATE KEY-----\n");
    Signer read = Signer.read(file);
    assertThat(read.publicKey()).isEqualTo(team.publicKey());
    assertThat(read.id()).isEqualTo(team.id()).hasSize(16).matches("[0-9a-f]{16}");
    // Its base64 alone works too.
    Path bare = dir.resolve("bare.key");
    Files.writeString(
        bare, Files.readString(file).replaceAll("-----[A-Z ]+-----", "").replace("\n", ""));
    assertThat(Signer.read(bare).publicKey()).isEqualTo(team.publicKey());
    assertThatThrownBy(() -> Signer.read(dir.resolve("absent.key")))
        .isInstanceOf(NoSuchFileException.class);
  }
}
