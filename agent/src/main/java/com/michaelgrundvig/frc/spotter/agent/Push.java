package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.michaelgrundvig.frc.spotter.protocol.WireCheck;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import us.hebi.quickbuf.ProtoSource;
import us.hebi.quickbuf.RepeatedByte;
import us.hebi.quickbuf.RepeatedMessage;

/**
 * Packs the robot pushes: a bundle of pack folders ({@code spotter.proto}'s {@code PackBundle}:
 * each file's path, whether it's executable, and its bytes, exactly what the pack hash covers), put
 * in place in one step. Its files are written into a folder of their own beside the others, {@value
 * PackHash#EXECUTABLE} or {@value PackHash#PLAIN} as the bundle says; then {@link Packs#PUSHED}, a
 * link, is renamed over to point at it, which is atomic. A push that fails partway (a full disk,
 * say) leaves the packs as they were. The agent then exits, and systemd starts it again, reading
 * the new packs.
 */
final class Push {
  /** Where pushed bundles are unpacked, each in a folder of its own. */
  static final String BUNDLES = "/var/lib/frc-spotter/pushed";

  /**
   * The largest bundle taken, and the most its files may hold: packs are scripts and settings, and
   * a bundle is read whole into the agent's 64 MiB heap.
   */
  static final long MAX_BUNDLE = 16L * 1024 * 1024;

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
    return Files.createTempFile(bundles, ".bundle-", ".pb");
  }

  /**
   * Puts a bundle's packs in place, in one step.
   *
   * @throws Rejected when it isn't a bundle the agent takes
   * @throws IOException when it can't be written
   */
  void apply(Path received) throws Rejected, IOException {
    Path bundles = host.path(BUNDLES);
    byte[] some = new byte[8];
    random.nextBytes(some);
    String id = HexFormat.of().formatHex(some);
    Path unpacked = bundles.resolve(id);
    try {
      unpack(received, unpacked);
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
      Files.deleteIfExists(received);
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

  /**
   * Writes a bundle's files into a folder: at most {@link #MAX_FILES} of them, holding at most
   * {@link #MAX_BUNDLE} bytes; each path relative, inside the folder, and named once; each file
   * {@value PackHash#EXECUTABLE} or {@value PackHash#PLAIN}, as the bundle says.
   */
  private static void unpack(Path file, Path into) throws Rejected, IOException {
    // Checked before it's parsed (WireCheck), so no length it declares is allocated past its size.
    try (InputStream in = Files.newInputStream(file)) {
      WireCheck.check(WireCheck.PACK_BUNDLE, ProtoSource.newInstance(in), (int) Files.size(file));
    } catch (WireCheck.Malformed e) {
      throw new Rejected("the bundle isn't a PackBundle that can be read safely: " + e.getMessage());
    }
    Spotter.PackBundle bundle;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      bundle = Spotter.PackBundle.parseFrom(ProtoSource.newInstance(in));
    } catch (IOException | RuntimeException e) {
      throw new Rejected("the bundle isn't a PackBundle: " + e.getMessage());
    }
    RepeatedMessage<Spotter.PackFile> files = bundle.getFiles();
    if (files.length() > MAX_FILES) {
      throw new Rejected("the bundle holds " + files.length() + " files, more than " + MAX_FILES);
    }
    long total = 0;
    Set<Path> written = new HashSet<>();
    Files.createDirectories(into);
    for (Spotter.PackFile each : files) {
      String relative = each.getPath();
      Path target = target(into, relative);
      if (!written.add(target)) {
        throw new Rejected("the bundle names a file twice: " + relative);
      }
      RepeatedByte content = each.getContent();
      total += content.length();
      if (total > MAX_BUNDLE) {
        throw new Rejected("the bundle holds more than " + MAX_BUNDLE / (1024 * 1024) + " MiB");
      }
      try {
        Files.createDirectories(Objects.requireNonNull(target.getParent(), "a file's folder"));
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
          out.write(content.array(), 0, content.length());
        }
      } catch (FileAlreadyExistsException e) {
        // A folder and a file of the same path, or one of them a link: either way, named twice.
        throw new Rejected("the bundle names a file twice: " + relative);
      }
      Files.setPosixFilePermissions(
          target,
          PosixFilePermissions.fromString(each.getExecutable() ? "rwxr-xr-x" : "rw-r--r--"));
    }
    try (Stream<Path> folders = Files.walk(into)) {
      for (Path folder : folders.filter(Files::isDirectory).toList()) {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
      }
    }
  }

  /**
   * Where a bundle's file goes: its path relative, {@code /}-separated, each part a name (not
   * empty, {@code .} or {@code ..}), inside the folder.
   */
  static Path target(Path into, String relative) throws Rejected {
    boolean names = !relative.isEmpty() && !relative.startsWith("/") && relative.indexOf('\\') < 0;
    for (String part : relative.split("/", -1)) {
      names &= !part.isEmpty() && !part.equals(".") && !part.equals("..") && part.indexOf(0) < 0;
    }
    Path target;
    try {
      target = into.resolve(relative).normalize();
    } catch (InvalidPathException e) {
      names = false;
      target = into;
    }
    if (!names || !target.startsWith(into) || target.equals(into)) {
      throw new Rejected("the bundle names a path outside its folder: " + relative);
    }
    return target;
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
