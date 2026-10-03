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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import us.hebi.quickbuf.RepeatedByte;

/**
 * The hash of a folder of packs, which the robot's manager and the agent compute alike to tell
 * whether a board has the robot's packs: of what the packs contain. Each file's path (relative to
 * the folder, with {@code /}), its permissions, its length and its bytes, in the order of their
 * paths, through SHA-256, as lowercase hex. Copying the packs gives the same hash, file times can't
 * change it, and a half-written or altered folder doesn't match.
 *
 * <p>Permissions are one of two, {@value #EXECUTABLE} or {@value #PLAIN}, as the agent writes a
 * pushed pack's files: so a umask on either side can't make two copies of the same packs differ. A
 * file is executable when any execute bit is set, or when it starts with {@code #!}: a deploy that
 * copies files without their modes (as the robot's may) still pushes a script that runs. Folders
 * count only by the files in them; a folder with no files at all hashes to the empty text, as no
 * packs do.
 *
 * <p>A pushed bundle ({@code PackBundle}) hashes the same way from its files, without touching a
 * disk: what the robot sends, and what the agent writes and hashes again from its disk, agree.
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
    List<String> paths = files(folder);
    if (paths.isEmpty()) {
      return "";
    }
    Path real = folder.toRealPath();
    MessageDigest digest = sha256();
    byte[] chunk = new byte[64 * 1024];
    for (String path : paths) {
      Path file = real.resolve(path);
      header(digest, path, permissions(file), Files.size(file));
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
   * The hash of a bundle of packs, from its files, as {@link #of(Path)} hashes them on a disk: each
   * file's path, {@value #EXECUTABLE} or {@value #PLAIN}, its length and its bytes, in the order of
   * their paths. Empty when it has no files.
   */
  public static String of(Spotter.PackBundle bundle) {
    List<Spotter.PackFile> files = new ArrayList<>();
    for (Spotter.PackFile file : bundle.getFiles()) {
      files.add(file);
    }
    if (files.isEmpty()) {
      return "";
    }
    files.sort(Comparator.comparing(Spotter.PackFile::getPath));
    MessageDigest digest = sha256();
    for (Spotter.PackFile file : files) {
      RepeatedByte content = file.getContent();
      header(digest, file.getPath(), file.getExecutable() ? EXECUTABLE : PLAIN, content.length());
      digest.update(content.array(), 0, content.length());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * A folder of packs as a bundle, as the robot pushes it: each file by its path in the folder
   * (with {@code /}), whether it's executable ({@link #executable}), and its bytes, in the order of
   * their paths. Empty when the folder has no files, or isn't there.
   *
   * @throws IOException when a file can't be read
   */
  public static Spotter.PackBundle bundle(Path folder) throws IOException {
    Spotter.PackBundle bundle = Spotter.PackBundle.newInstance();
    List<String> paths = files(folder);
    if (paths.isEmpty()) {
      return bundle;
    }
    Path real = folder.toRealPath();
    for (String path : paths) {
      Path file = real.resolve(path);
      byte[] content = Files.readAllBytes(file);
      bundle.addFiles(
          Spotter.PackFile.newInstance()
              .setPath(path)
              .setExecutable(anyExecuteBit(file) || script(content))
              .setContent(content));
    }
    return bundle;
  }

  /** A folder's files, by path in it with {@code /}, sorted; none when it isn't there. */
  private static List<String> files(Path folder) throws IOException {
    List<String> paths = new ArrayList<>();
    if (!Files.isDirectory(folder)) {
      return paths;
    }
    // The folder itself may be a link (the agent swaps its pushed packs in by one): what it's a
    // link to is walked.
    Path real = folder.toRealPath();
    try (Stream<Path> walk = Files.walk(real)) {
      walk.filter(Files::isRegularFile)
          .forEach(file -> paths.add(real.relativize(file).toString().replace('\\', '/')));
    }
    paths.sort(null);
    return paths;
  }

  /** What's hashed of a file before its bytes: its path, permissions and length. */
  private static void header(MessageDigest digest, String path, String permissions, long length) {
    digest.update(path.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(permissions.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
    digest.update(Long.toString(length).getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }

  /**
   * A file's permissions as the hash counts them: {@value #EXECUTABLE} when it's {@link
   * #executable}, else {@value #PLAIN}.
   */
  public static String permissions(Path file) throws IOException {
    return executable(file) ? EXECUTABLE : PLAIN;
  }

  /**
   * Whether a pack's file is executable, as a push carries it: any execute bit is set, or it starts
   * with {@code #!} (a script that names its interpreter), so a script whose execute bit a copy
   * dropped still runs once pushed. On a filesystem without POSIX permissions (Windows), only the
   * {@code #!} counts.
   */
  public static boolean executable(Path file) throws IOException {
    if (anyExecuteBit(file)) {
      return true;
    }
    try (InputStream in = Files.newInputStream(file)) {
      return script(in.readNBytes(2));
    }
  }

  private static boolean anyExecuteBit(Path file) throws IOException {
    PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
    if (posix == null) {
      return false;
    }
    Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
    return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
        || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
        || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
  }

  /** Whether a file's bytes start with {@code #!}. */
  private static boolean script(byte[] content) {
    return content.length >= 2 && content[0] == '#' && content[1] == '!';
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }
}
