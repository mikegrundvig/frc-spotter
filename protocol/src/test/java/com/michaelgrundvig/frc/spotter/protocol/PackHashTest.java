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
import us.hebi.quickbuf.ProtoMessage;

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
        packs.resolve("vision/pack.yaml"), PosixFilePermissions.fromString("rwxr-xr-x"));
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

  @Test
  void aBundleOfAFolderHashesAsTheFolderDoes() throws Exception {
    Path folder = packs("robot");
    Spotter.PackBundle bundle = PackHash.bundle(folder);
    assertThat(bundle.getFiles())
        .extracting(Spotter.PackFile::getPath)
        .containsExactly("debian/cpu", "debian/pack.yaml", "vision/pack.yaml");
    assertThat(bundle.getFiles().get(0).getExecutable()).isTrue();
    assertThat(bundle.getFiles().get(1).getExecutable()).isFalse();
    assertThat(new String(bundle.getFiles().get(1).getContent().toArray(), StandardCharsets.UTF_8))
        .isEqualTo("pack: debian\n");
    assertThat(PackHash.of(bundle)).isEqualTo(PackHash.of(folder)).hasSize(64);
    // Over the wire and back, as the agent gets it: the same hash.
    Spotter.PackBundle sent =
        ProtoMessage.mergeFrom(Spotter.PackBundle.newInstance(), bundle.toByteArray());
    assertThat(PackHash.of(sent)).isEqualTo(PackHash.of(folder));
    // In any order: the hash sorts by path.
    Spotter.PackBundle reversed = Spotter.PackBundle.newInstance();
    for (int i = bundle.getFiles().length() - 1; i >= 0; i--) {
      reversed.addFiles(bundle.getFiles().get(i));
    }
    assertThat(PackHash.of(reversed)).isEqualTo(PackHash.of(folder));
    // A change to a file's executable flag changes it.
    reversed.getMutableFiles().get(0).setExecutable(!reversed.getFiles().get(0).getExecutable());
    assertThat(PackHash.of(reversed)).isNotEqualTo(PackHash.of(folder));
  }

  @Test
  void aScriptThatNamesItsInterpreterIsExecutableWithoutItsBit() throws IOException {
    // As the robot's deploy may copy it: its bytes, and no execute bit.
    write(dir.resolve("deployed/debian/cpu"), "#!/bin/sh\necho 1\n", "rw-r--r--");
    write(dir.resolve("deployed/debian/pack.yaml"), "pack: debian\n", "rw-r--r--");
    write(dir.resolve("deployed/debian/data"), "#", "rw-r--r--");
    Spotter.PackBundle bundle = PackHash.bundle(dir.resolve("deployed"));
    assertThat(bundle.getFiles())
        .extracting(Spotter.PackFile::getPath, Spotter.PackFile::getExecutable)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("debian/cpu", true),
            org.assertj.core.groups.Tuple.tuple("debian/data", false),
            org.assertj.core.groups.Tuple.tuple("debian/pack.yaml", false));
    assertThat(PackHash.permissions(dir.resolve("deployed/debian/cpu")))
        .isEqualTo(PackHash.EXECUTABLE);
    // So the folder, the bundle, and the agent's copy of it (written 755) hash alike.
    assertThat(PackHash.of(bundle)).isEqualTo(PackHash.of(dir.resolve("deployed")));
  }

  @Test
  void aBundleWithNoFilesHashesToNothing() throws IOException {
    assertThat(PackHash.of(Spotter.PackBundle.newInstance())).isEmpty();
    assertThat(PackHash.bundle(dir.resolve("missing")).getFiles()).isEmpty();
  }
}
