package com.michaelgrundvig.frc.spotter.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The hash of a folder of packs, which the robot's manager and the agent compute alike to tell
 * whether a board has the robot's packs: of what the packs contain, never of a zip. Each file's
 * path (relative to the folder, with {@code /}), its permissions, and its bytes, in the order of
 * their paths, through SHA-256, as lowercase hex. Building a bundle twice gives the same hash, a
 * zip's timestamps can't change it, and a half-written or altered folder doesn't match.
 *
 * <p>Permissions are one of two, {@value #EXECUTABLE} (any execute bit set) or {@value #PLAIN}, as
 * the agent writes a pushed pack's files: so a umask on either side can't make two copies of the
 * same packs differ. Folders count only by the files in them; a folder with no files at all hashes
 * to the empty text, as no packs do.
 */
public final class PackHash {
  /** The permissions an executable file is hashed with, and written with. */
  public static final String EXECUTABLE = "755";

  /** The permissions any other file is hashed with, and written with. */
  public static final String PLAIN = "644";

  private PackHash() {}

  /**
   * The hash of a folder of packs; empty when it has no files, or isn't there.
   *
   * @throws IOException when a file can't be read
   */
  public static String of(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return "";
    }
    // The folder itself may be a link (the agent swaps its pushed packs in by one): what it's a
    // link to is walked.
    Path real = folder.toRealPath();
    List<String> paths = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(real)) {
      walk.filter(Files::isRegularFile)
          .forEach(file -> paths.add(real.relativize(file).toString().replace('\\', '/')));
    }
    if (paths.isEmpty()) {
      return "";
    }
    paths.sort(null);
    MessageDigest digest = sha256();
    byte[] chunk = new byte[64 * 1024];
    for (String path : paths) {
      Path file = real.resolve(path);
      digest.update(path.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(permissions(file).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) 0);
      try (InputStream in = Files.newInputStream(file)) {
        int read;
        while ((read = in.read(chunk)) != -1) {
          digest.update(chunk, 0, read);
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * A file's permissions as the hash counts them: {@value #EXECUTABLE} when any execute bit is set,
   * else {@value #PLAIN}; {@value #PLAIN} on a filesystem without POSIX permissions.
   */
  public static String permissions(Path file) throws IOException {
    PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
    if (posix == null) {
      return PLAIN;
    }
    Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
    return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
            || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
            || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
        ? EXECUTABLE
        : PLAIN;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }
}
