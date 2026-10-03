package com.michaelgrundvig.frc.spotter.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pack hash: of what the packs contain, whatever their copies' times and umasks. */
class PackHashTest {
  @TempDir Path dir;

  private Path packs(String name) throws IOException {
    Path folder = dir.resolve(name);
    write(folder.resolve("debian/pack.yaml"), "pack: debian\n", "rw-r--r--");
    write(folder.resolve("debian/cpu"), "#!/bin/sh\necho 1\n", "rwxr-xr-x");
    write(folder.resolve("vision/pack.yaml"), "pack: vision\n", "rw-r--r--");
    return folder;
  }

  private static void write(Path file, String text, String permissions) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
  }

  @Test
  void twoCopiesOfTheSamePacksHashAlikeWhateverTheirTimesAndUmasks() throws IOException {
    Path one = packs("one");
    Path two = packs("two");
    Files.setLastModifiedTime(two.resolve("debian/cpu"), FileTime.fromMillis(0));
    // Another umask: group-writable, and executable by its owner alone.
    Files.setPosixFilePermissions(
        two.resolve("debian/pack.yaml"), PosixFilePermissions.fromString("rw-rw-r--"));
    Files.setPosixFilePermissions(
        two.resolve("debian/cpu"), PosixFilePermissions.fromString("rwx------"));
    assertThat(PackHash.of(one)).hasSize(64).matches("[0-9a-f]+").isEqualTo(PackHash.of(two));
  }

  @Test
  void aChangeOfBytesPathOrExecutableChangesIt() throws IOException {
    String before = PackHash.of(packs("packs"));
    Path packs = dir.resolve("packs");
    Files.writeString(packs.resolve("debian/cpu"), "#!/bin/sh\necho 2\n");
    String bytes = PackHash.of(packs);
    assertThat(bytes).isNotEqualTo(before);
    Files.setPosixFilePermissions(
        packs.resolve("debian/cpu"), PosixFilePermissions.fromString("rw-r--r--"));
    String executable = PackHash.of(packs);
    assertThat(executable).isNotEqualTo(bytes);
    Files.move(packs.resolve("vision"), packs.resolve("vision2"));
    assertThat(PackHash.of(packs)).isNotEqualTo(executable);
  }

  @Test
  void aFileSplitDifferentlyStillDiffers() throws IOException {
    // Framed by path, permissions and length: "a" + "bc" never hashes as "ab" + "c".
    write(dir.resolve("x/p/a"), "1", "rw-r--r--");
    write(dir.resolve("x/p/b"), "23", "rw-r--r--");
    write(dir.resolve("y/p/a"), "12", "rw-r--r--");
    write(dir.resolve("y/p/b"), "3", "rw-r--r--");
    assertThat(PackHash.of(dir.resolve("x"))).isNotEqualTo(PackHash.of(dir.resolve("y")));
  }

  @Test
  void aLinkToTheFolderHashesAsTheFolder() throws IOException {
    Path packs = packs("bundle");
    Path link = dir.resolve("packs");
    Files.createSymbolicLink(link, dir.relativize(packs));
    assertThat(PackHash.of(link)).isEqualTo(PackHash.of(packs)).isNotEmpty();
  }

  @Test
  void noFilesHashToNothing() throws IOException {
    assertThat(PackHash.of(dir.resolve("missing"))).isEmpty();
    Files.createDirectories(dir.resolve("empty/debian"));
    assertThat(PackHash.of(dir.resolve("empty"))).isEmpty();
  }

  @Test
  void permissionsAreOneOfTwo() throws IOException {
    write(dir.resolve("group"), "", "rw-r-x---");
    write(dir.resolve("plain"), "", "rw-rw-rw-");
    assertThat(PackHash.permissions(dir.resolve("group"))).isEqualTo(PackHash.EXECUTABLE);
    assertThat(PackHash.permissions(dir.resolve("plain"))).isEqualTo(PackHash.PLAIN);
  }
}
