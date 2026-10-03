package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Packs the robot pushes: a bundle of pack folders (a zip), put in place in one step. It's unpacked
 * into a folder of its own beside the others, its files written {@value PackHash#EXECUTABLE} when
 * the zip says any execute bit, {@value PackHash#PLAIN} otherwise, as the pack hash counts them;
 * then {@link Packs#PUSHED}, a link, is renamed over to point at it, which is atomic. A push that
 * fails partway (a full disk, say) leaves the packs as they were. The agent then exits, and systemd
 * starts it again, reading the new packs.
 */
final class Push {
  /** Where pushed bundles are unpacked, each in a folder of its own. */
  static final String BUNDLES = "/var/lib/frc-spotter/pushed";

  /** The largest bundle taken, and the most its files may hold, unpacked. */
  static final long MAX_BUNDLE = 64L * 1024 * 1024;

  /** The most files a bundle may hold. */
  static final int MAX_FILES = 4096;

  /** A bundle that can't be taken: why. */
  static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;

    Rejected(String reason) {
      super(reason);
    }
  }

  private final Host host;
  private final SecureRandom random = new SecureRandom();

  Push(Host host) {
    this.host = host;
  }

  /** A file to take a bundle into, beside the packs: the request's body, read to its end first. */
  Path bundle() throws IOException {
    Path bundles = host.path(BUNDLES);
    Files.createDirectories(bundles);
    return Files.createTempFile(bundles, ".bundle-", ".zip");
  }

  /**
   * Puts a bundle's packs in place, in one step.
   *
   * @throws Rejected when it isn't a zip the agent takes
   * @throws IOException when it can't be written
   */
  void apply(Path zip) throws Rejected, IOException {
    Path bundles = host.path(BUNDLES);
    byte[] some = new byte[8];
    random.nextBytes(some);
    String id = HexFormat.of().formatHex(some);
    Path unpacked = bundles.resolve(id);
    try {
      unpack(zip, unpacked);
      Path packs = host.path(Packs.PUSHED);
      if (Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) {
        // Packs put there by hand, before any push: kept aside, as a bundle of their own.
        Files.move(packs, bundles.resolve("before-" + id));
      }
      Path link = packs.resolveSibling(".packs-" + id);
      Files.createSymbolicLink(
          link, Objects.requireNonNull(packs.getParent(), "a link's folder").relativize(unpacked));
      Files.move(link, packs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Rejected | IOException | RuntimeException e) {
      delete(unpacked);
      throw e;
    } finally {
      Files.deleteIfExists(zip);
    }
    host.log("Packs pushed (" + PackHash.of(unpacked) + "): restarting to read them");
    tidy();
  }

  /** Removes the bundles the packs don't point at: older ones, and any left half unpacked. */
  void tidy() {
    Path bundles = host.path(BUNDLES);
    Path current = null;
    try {
      Path packs = host.path(Packs.PUSHED);
      if (Files.isSymbolicLink(packs)) {
        current = packs.toRealPath();
      }
    } catch (IOException e) {
      // No packs: every bundle goes.
    }
    try (DirectoryStream<Path> each = Files.newDirectoryStream(bundles)) {
      for (Path bundle : each) {
        if (current == null || !bundle.toRealPath().equals(current)) {
          delete(bundle);
        }
      }
    } catch (IOException e) {
      // Nothing to tidy.
    }
  }

  private static void unpack(Path zip, Path into) throws Rejected, IOException {
    FileSystem bundle;
    try {
      bundle =
          FileSystems.newFileSystem(
              URI.create("jar:" + zip.toUri()),
              // An entry the zip keeps no permissions for is a plain file, not an executable one.
              Map.of("enablePosixFileAttributes", "true", "defaultPermissions", "rw-r--r--"));
    } catch (IOException | RuntimeException e) {
      throw new Rejected("the bundle isn't a zip: " + e.getMessage());
    }
    try (bundle) {
      Files.createDirectories(into);
      Path root = bundle.getPath("/");
      List<Path> files = new ArrayList<>();
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).forEach(files::add);
      }
      if (files.size() > MAX_FILES) {
        throw new Rejected("the bundle holds " + files.size() + " files, more than " + MAX_FILES);
      }
      long total = 0;
      for (Path file : files) {
        String relative = root.relativize(file).toString();
        Path target = into.resolve(relative).normalize();
        if (relative.isEmpty() || !target.startsWith(into)) {
          throw new Rejected("the bundle names a path outside its folder: " + relative);
        }
        total += Files.size(file);
        if (total > MAX_BUNDLE) {
          throw new Rejected("the bundle holds more than " + MAX_BUNDLE / (1024 * 1024) + " MiB");
        }
        Files.createDirectories(target.getParent());
        Files.copy(file, target);
        Files.setPosixFilePermissions(
            target, PosixFilePermissions.fromString(executable(file) ? "rwxr-xr-x" : "rw-r--r--"));
      }
      try (Stream<Path> folders = Files.walk(into)) {
        for (Path folder : folders.filter(Files::isDirectory).toList()) {
          Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
      }
    }
  }

  /** Whether a zip's entry has any execute bit: none when the zip keeps no permissions. */
  private static boolean executable(Path entry) {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(entry);
      return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
          || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
          || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }

  private void delete(Path path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(path)) {
      for (Path each : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(each);
      }
    } catch (IOException e) {
      host.log("Couldn't remove " + path + ": " + e);
    }
  }
}
