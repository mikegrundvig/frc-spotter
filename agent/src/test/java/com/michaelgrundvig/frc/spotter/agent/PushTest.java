package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pushed packs: a zip of pack folders, unpacked with scripts executable, swapped in at once. */
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

  /** A bundle of a folder of packs, as the manager zips one: with each file's permissions. */
  private Path zip(Path packs) throws Exception {
    Path zip = push.bundle();
    Files.delete(zip);
    try (FileSystem bundle =
        FileSystems.newFileSystem(
            URI.create("jar:" + zip.toUri()),
            Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
      try (Stream<Path> files = Files.walk(packs)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          Path entry = bundle.getPath("/" + packs.relativize(file));
          Files.createDirectories(entry.getParent());
          Files.copy(file, entry);
          Files.setPosixFilePermissions(entry, Files.getPosixFilePermissions(file));
        }
      }
    }
    return zip;
  }

  @Test
  void aBundleIsPutInPlaceAsItsPacksWithTheirHash() throws Exception {
    Path robot = packs("robot", "1.0.0");
    push.apply(zip(robot));
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
    // The robot's hash of its folder is the board's of what was put in place.
    assertThat(PackHash.of(pushed)).isEqualTo(PackHash.of(robot));
    assertThat(Packs.load(fixture.host, true).pushedHash()).isEqualTo(PackHash.of(robot));
    assertThat(fixture.log()).contains("Packs pushed (" + PackHash.of(robot) + ")");

    // Pushed again: swapped in one step, the old bundle tidied away.
    Path next = packs("next", "2.0.0");
    push.apply(zip(next));
    assertThat(Files.readString(pushed.resolve("vision/pack.yaml"))).contains("2.0.0");
    try (Stream<Path> bundles = Files.list(fixture.path(Push.BUNDLES))) {
      assertThat(bundles).hasSize(1);
    }
  }

  @Test
  void packsPutThereByHandAreKeptAsideByTheFirstPush() throws Exception {
    fixture.pushed("old", "pack: old\n");
    push.apply(zip(packs("robot", "1.0.0")));
    assertThat(Files.exists(fixture.path(Packs.PUSHED + "/old"))).isFalse();
    assertThat(Files.exists(fixture.path(Packs.PUSHED + "/vision/pack.yaml"))).isTrue();
  }

  @Test
  void aBundleThatIsntAZipOrLeavesItsFolderIsRefusedAndNothingChanges() throws Exception {
    push.apply(zip(packs("robot", "1.0.0")));
    String before = PackHash.of(fixture.path(Packs.PUSHED));
    Path notZip = push.bundle();
    Files.writeString(notZip, "not a zip");
    assertThat(catchThrowableOfType(Push.Rejected.class, () -> push.apply(notZip)))
        .hasMessageStartingWith("the bundle isn't a zip");
    Path escaping = push.bundle();
    try (OutputStream out = Files.newOutputStream(escaping);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("../../../etc/evil"));
      zip.write("x".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    Push.Rejected rejected = catchThrowableOfType(Push.Rejected.class, () -> push.apply(escaping));
    assertThat(rejected).isNotNull();
    assertThat(Files.exists(fixture.path("/etc/evil"))).isFalse();
    assertThat(PackHash.of(fixture.path(Packs.PUSHED))).isEqualTo(before);
    assertThat(Files.exists(notZip)).isFalse();
  }

  @Test
  void tidyingRemovesWhatTheLinkDoesntPointAt() throws Exception {
    push.apply(zip(packs("robot", "1.0.0")));
    Files.createDirectories(fixture.path(Push.BUNDLES + "/half-unpacked"));
    Files.writeString(fixture.path(Push.BUNDLES + "/.bundle-1.zip"), "left");
    push.tidy();
    try (Stream<Path> bundles = Files.list(fixture.path(Push.BUNDLES))) {
      assertThat(bundles).hasSize(1);
    }
    assertThat(Files.exists(fixture.path(Packs.PUSHED + "/vision/pack.yaml"))).isTrue();
  }
}
