package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The team's packs, as the robot pushes them: a folder of pack folders, its hash ({@link PackHash},
 * as the agent computes it), and the bundle that's pushed, a zip of its files, each marked
 * executable or not as the hash counts it. Hashed as the manager starts; bundled once, when a board
 * first needs it.
 */
final class TeamPacks {
  private final Path folder;
  private final String hash;
  private byte @Nullable [] bundle;

  private TeamPacks(Path folder, String hash) {
    this.folder = folder;
    this.hash = hash;
  }

  /**
   * The packs in a folder; none when it has no files, as no packs push nothing.
   *
   * @throws IOException when a file can't be read
   */
  static @Nullable TeamPacks of(Path folder) throws IOException {
    String hash = PackHash.of(folder);
    return hash.isEmpty() ? null : new TeamPacks(folder, hash);
  }

  /** Their hash. */
  String hash() {
    return hash;
  }

  /** The bundle: a zip of every file, by its path in the folder. */
  synchronized byte[] bundle() throws IOException {
    byte[] made = bundle;
    if (made == null) {
      made = zip(folder);
      bundle = made;
    }
    return made;
  }

  /**
   * A zip of a folder's files, by path, each with its permissions as the hash counts them ({@code
   * rwxr-xr-x} or {@code rw-r--r--}), which the agent writes them with. Java's zip file system
   * writes the permissions ({@code ZipOutputStream} can't); a zip's timestamps don't matter, as the
   * agent hashes what it unpacks.
   */
  static byte[] zip(Path folder) throws IOException {
    Path real = folder.toRealPath();
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(real)) {
      walk.filter(Files::isRegularFile).forEach(files::add);
    }
    files.sort(null);
    Path zip = Files.createTempFile("spotter-packs-", ".zip");
    try {
      Files.delete(zip);
      try (FileSystem bundle =
          FileSystems.newFileSystem(
              URI.create("jar:" + zip.toUri()),
              Map.of("create", "true", "enablePosixFileAttributes", "true"))) {
        for (Path file : files) {
          Path entry = bundle.getPath("/" + real.relativize(file).toString().replace('\\', '/'));
          Path parent = entry.getParent();
          if (parent != null) {
            Files.createDirectories(parent);
          }
          Files.copy(file, entry);
          boolean executable = PackHash.permissions(file).equals(PackHash.EXECUTABLE);
          Files.setPosixFilePermissions(
              entry, PosixFilePermissions.fromString(executable ? "rwxr-xr-x" : "rw-r--r--"));
        }
      }
      return Files.readAllBytes(zip);
    } finally {
      Files.deleteIfExists(zip);
    }
  }
}
