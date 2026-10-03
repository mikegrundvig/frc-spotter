package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pushed packs: a bundle of pack folders ({@code PackBundle}), written with scripts executable,
 * swapped in at once; and every bundle that isn't one the agent takes refused, nothing changed.
 */
class PushTest {
  @TempDir Path dir;
  Fixture fixture;
  Push push;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
    push = new Push(fixture.host);
  }

  /** A robot's packs, as its deploy folder has them. */
  private Path packs(String name, String version) throws Exception {
    Path folder = dir.resolve(name);
    Files.createDirectories(folder.resolve("vision"));
    Files.writeString(
        folder.resolve("vision/pack.yaml"), "pack: vision\nversion: " + version + "\n");
    Files.writeString(folder.resolve("vision/check"), "#!/bin/sh\necho ok\n");
    Files.setPosixFilePermissions(
        folder.resolve("vision/check"), PosixFilePermissions.fromString("rwxrwxr-x"));
    return folder;
  }

  /** A folder's bundle, as the manager makes and sends it, in the file the request is read to. */
  private Path sent(Path packs) throws Exception {
    return sent(PackHash.bundle(packs));
  }

  private Path sent(Spotter.PackBundle bundle) throws Exception {
    Path body = push.bundle();
    Files.write(body, bundle.toByteArray());
    return body;
  }

  /** A bundle made by hand: a path and its text each, all plain files. */
  private static Spotter.PackBundle files(String... pathsAndTexts) {
    Spotter.PackBundle bundle = Spotter.PackBundle.newInstance();
    for (int i = 0; i < pathsAndTexts.length; i += 2) {
      bundle.addFiles(
          Spotter.PackFile.newInstance()
              .setPath(pathsAndTexts[i])
              .setContent(pathsAndTexts[i + 1].getBytes(StandardCharsets.UTF_8)));
    }
    return bundle;
  }

  @Test
  void aBundleIsPutInPlaceAsItsPacksWithTheirHash() throws Exception {
    Path robot = packs("robot", "1.0.0");
    push.apply(sent(robot));
    Path pushed = fixture.path(Packs.PUSHED);
    assertThat(Files.isSymbolicLink(pushed)).isTrue();
    assertThat(Files.readString(pushed.resolve("vision/pack.yaml"))).contains("1.0.0");
    assertThat(Files.isExecutable(pushed.resolve("vision/check"))).isTrue();
    assertThat(
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(pushed.resolve("vision/check"))))
        .isEqualTo("rwxr-xr-x");
    assertThat(
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(pushed.resolve("vision/pack.yaml"))))
        .isEqualTo("rw-r--r--");
    // The robot's hash of its folder, and of its bundle, is the board's of what was put in place.
    assertThat(PackHash.of(pushed))
        .isEqualTo(PackHash.of(robot))
        .isEqualTo(PackHash.of(PackHash.bundle(robot)));
    assertThat(Packs.load(fixture.host, true).pushedHash()).isEqualTo(PackHash.of(robot));
    assertThat(fixture.log()).contains("Packs pushed (" + PackHash.of(robot) + ")");

    // Pushed again: swapped in one step, the old bundle tidied away.
    Path next = packs("next", "2.0.0");
    push.apply(sent(next));
    assertThat(Files.readString(pushed.resolve("vision/pack.yaml"))).contains("2.0.0");
    try (Stream<Path> bundles = Files.list(fixture.path(Push.BUNDLES))) {
      assertThat(bundles).hasSize(1);
    }
  }

  @Test
  void packsPutThereByHandAreKeptAsideByTheFirstPush() throws Exception {
    fixture.pushed("old", "pack: old\n");
    push.apply(sent(packs("robot", "1.0.0")));
    assertThat(Files.exists(fixture.path(Packs.PUSHED + "/old"))).isFalse();
    assertThat(Files.exists(fixture.path(Packs.PUSHED + "/vision/pack.yaml"))).isTrue();
  }

  /** Applies a bundle that must be refused: why. */
  private String refused(Spotter.PackBundle bundle) throws Exception {
    Path body = sent(bundle);
    Push.Rejected rejected = catchThrowableOfType(Push.Rejected.class, () -> push.apply(body));
    assertThat(rejected).as("refused").isNotNull();
    assertThat(Files.exists(body)).isFalse();
    return String.valueOf(rejected.getMessage());
  }

  @Test
  void aBundleTheAgentDoesntTakeIsRefusedAndNothingChanges() throws Exception {
    push.apply(sent(packs("robot", "1.0.0")));
    String before = PackHash.of(fixture.path(Packs.PUSHED));

    Path notABundle = push.bundle();
    // Field 1, said to be 127 bytes long, and then nothing.
    Files.write(notABundle, new byte[] {0x0a, 0x7f});
    assertThat(catchThrowableOfType(Push.Rejected.class, () -> push.apply(notABundle)))
        .hasMessageStartingWith("the bundle isn't a PackBundle");

    for (String outside :
        new String[] {
          "../../../etc/evil", "/etc/evil", "vision/../../evil", "", "vision//check", "./x", "a\\b"
        }) {
      assertThat(refused(files(outside, "x")))
          .isEqualTo("the bundle names a path outside its folder: " + outside);
    }
    assertThat(Files.exists(fixture.path("/etc/evil"))).isFalse();
    assertThat(refused(files("vision/pack.yaml", "a", "vision/pack.yaml", "b")))
        .isEqualTo("the bundle names a file twice: vision/pack.yaml");
    assertThat(refused(files("vision", "a", "vision/pack.yaml", "b")))
        .isEqualTo("the bundle names a file twice: vision/pack.yaml");
    assertThat(PackHash.of(fixture.path(Packs.PUSHED))).isEqualTo(before);
  }

  @Test
  void aBundleOverItsLimitsIsRefused() throws Exception {
    Spotter.PackBundle many = Spotter.PackBundle.newInstance();
    for (int i = 0; i <= Push.MAX_FILES; i++) {
      many.addFiles(Spotter.PackFile.newInstance().setPath("vision/" + i));
    }
    assertThat(refused(many)).isEqualTo("the bundle holds 4097 files, more than 4096");
    byte[] half = new byte[(int) (Push.MAX_BUNDLE / 2)];
    Spotter.PackBundle big = Spotter.PackBundle.newInstance();
    for (String name : new String[] {"a", "b", "c"}) {
      big.addFiles(Spotter.PackFile.newInstance().setPath("vision/" + name).setContent(half));
    }
    assertThat(refused(big)).isEqualTo("the bundle holds more than 16 MiB");
  }

  @Test
  void tidyingRemovesWhatTheLinkDoesntPointAt() throws Exception {
    push.apply(sent(packs("robot", "1.0.0")));
    Files.createDirectories(fixture.path(Push.BUNDLES + "/half-unpacked"));
    Files.writeString(fixture.path(Push.BUNDLES + "/.bundle-1.pb"), "left");
    push.tidy();
    try (Stream<Path> bundles = Files.list(fixture.path(Push.BUNDLES))) {
      assertThat(bundles).hasSize(1);
    }
    assertThat(Files.exists(fixture.path(Packs.PUSHED + "/vision/pack.yaml"))).isTrue();
  }
}
