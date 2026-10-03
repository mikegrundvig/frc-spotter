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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signed writes, against a real agent that requires them: the manager signs each over its stream's
 * challenge with the robot's key pair, which tests make as they run (no key is ever committed).
 * Without the pair, reading still works, writes are refused, and an alert says why.
 */
class SigningTest {
  @TempDir Path dir;
  LocalAgent agent;
  KeyPair team;
  Path key;
  @Nullable Manager manager;

  @BeforeEach
  void aBoardThatRequiresSignatures() throws Exception {
    team = Keys.pair();
    Files.createDirectories(dir.resolve("controller"));
    key = Keys.write(team, dir.resolve("controller"));
    agent = new LocalAgent(dir.resolve("board"), "vision-front");
    agent
        .pack("team", ActionsTest.PACK)
        .script("team", "hello", ActionsTest.HELLO)
        .script("team", "wait", ActionsTest.WAIT)
        .script("team", "pager", ActionsTest.PAGER)
        .config("{\"trustedKeys\": [\"" + Keys.trusted(team) + "\"]}")
        .start();
  }

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    agent.close();
  }

  private Board connected(Settings settings) throws InterruptedException {
    Manager started =
        new Manager(Boards.robot(), List.of(agent.address()), settings, Recorder.NONE);
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(started, "described", () -> board.description().getRequiresSignatures());
    return board;
  }

  private Board connected(Path keyFile) throws InterruptedException {
    return connected(Settings.DEFAULTS.withKey(keyFile));
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
    assertThat(Objects.requireNonNull(manager).alerts()).isEmpty();
  }

  @Test
  void aPublicKeyElsewhereIsGivenItsPath() throws Exception {
    Path elsewhere = dir.resolve("team.pub");
    Files.move(dir.resolve("controller/spotter.pub"), elsewhere);
    Board board = connected(Settings.DEFAULTS.withKey(key).withPublicKey(elsewhere));
    assertThat(ActionsTest.done(board.run("team.hello", "Ada")).outcome())
        .isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
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
  void withoutTheKeyPairReadingWorksWritesAreRefusedAndAnAlertSaysWhy() throws Exception {
    Path none = dir.resolve("none.key");
    Board board = connected(none);
    Manager started = Objects.requireNonNull(manager);
    assertThat(started.alerts())
        .containsExactly(
            new Alert(
                Level.WARNING,
                "",
                "no Spotter key pair on this controller ("
                    + none
                    + ", "
                    + dir.resolve("spotter.pub")
                    + "): boards that require signatures will refuse its actions and pushes"));
    assertThat(board.log("team.log", LogQuery.latest(1)).get(10, TimeUnit.SECONDS).getEntries())
        .hasSize(1);
    Run refused = ActionsTest.done(board.run("team.hello", "Ada"));
    assertThat(refused.state()).isEqualTo(Run.State.REFUSED);
    assertThat(refused.why())
        .isEqualTo("it answered 401: this board requires signed writes, and this one isn't signed");
  }

  @Test
  void aMissingPublicKeyIsAsAMissingPair() throws Exception {
    Files.delete(dir.resolve("controller/spotter.pub"));
    connected(key);
    Manager started = Objects.requireNonNull(manager);
    assertThat(started.alerts()).hasSize(1);
    assertThat(started.alerts().get(0).text())
        .startsWith("no Spotter key pair on this controller (" + key + ", ");
  }

  @Test
  void aPairTheBoardDoesntTrustIsRefused() throws Exception {
    Files.createDirectories(dir.resolve("other"));
    Path other = Keys.write(Keys.pair(), dir.resolve("other"));
    Board board = connected(other);
    Run refused = ActionsTest.done(board.run("team.hello", "Ada"));
    assertThat(refused.why())
        .startsWith("it answered 401: key ")
        .endsWith(" isn't one this board trusts");
  }

  @Test
  void aPairThatCantBeReadOrDoesntMatchIsSaid() throws Exception {
    Path garbled = dir.resolve("garbled");
    Files.createDirectories(garbled);
    Files.writeString(
        garbled.resolve("spotter.key"),
        "-----BEGIN PRIVATE KEY-----\nnot a key\n-----END PRIVATE KEY-----\n");
    Files.copy(dir.resolve("controller/spotter.pub"), garbled.resolve("spotter.pub"));
    connected(garbled.resolve("spotter.key"));
    Manager started = Objects.requireNonNull(manager);
    assertThat(started.alerts()).hasSize(1);
    assertThat(started.alerts().get(0).text())
        .startsWith("the Spotter key pair can't be read (")
        .endsWith("): boards that require signatures will refuse its actions and pushes");

    // Another key's public key: no pair.
    Files.createDirectories(dir.resolve("mixed"));
    Path mixed = Keys.write(team, dir.resolve("mixed"));
    Files.writeString(
        dir.resolve("mixed/spotter.pub"),
        Keys.pem("PUBLIC KEY", Keys.pair().getPublic().getEncoded()));
    assertThatThrownBy(() -> Signer.read(mixed, dir.resolve("mixed/spotter.pub")))
        .hasMessage(dir.resolve("mixed/spotter.pub") + " isn't the public key of " + mixed);
    // A public key where the private key goes: not PEM of that kind.
    assertThatThrownBy(() -> Signer.read(dir.resolve("mixed/spotter.pub"), mixed))
        .hasMessage(dir.resolve("mixed/spotter.pub") + " isn't a private key in PEM");
  }

  @Test
  void aPairIsReadAsTheBoardListsItsPublicKey() throws Exception {
    Signer read = Signer.read(key, dir.resolve("controller/spotter.pub"));
    assertThat(read.publicKey()).isEqualTo(Keys.trusted(team)).startsWith("MCowBQYDK2VwAyEA");
    assertThat(read.id())
        .isEqualTo(
            HexFormat.of()
                .formatHex(Signer.sha256(team.getPublic().getEncoded()))
                .substring(0, 16));
    assertThatThrownBy(() -> Signer.read(dir.resolve("absent.key"), dir.resolve("absent.pub")))
        .isInstanceOf(NoSuchFileException.class);
  }

  @Test
  void aPairOpensslMadeIsRead() throws Exception {
    // As docs/robot.md says to make one; skipped where openssl isn't installed.
    Assumptions.assumeTrue(
        Files.isExecutable(Path.of("/usr/bin/openssl")), "openssl isn't installed");
    Path made = dir.resolve("openssl.key");
    Path pub = dir.resolve("openssl.pub");
    run("openssl", "genpkey", "-algorithm", "ed25519", "-out", made.toString());
    run("openssl", "pkey", "-in", made.toString(), "-pubout", "-out", pub.toString());
    String expected =
        Base64.getEncoder()
            .encodeToString(
                run("openssl", "pkey", "-in", made.toString(), "-pubout", "-outform", "DER"));
    assertThat(Signer.read(made, pub).publicKey()).isEqualTo(expected);
  }

  private static byte[] run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    byte[] out = process.getInputStream().readAllBytes();
    assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
    return out;
  }
}
