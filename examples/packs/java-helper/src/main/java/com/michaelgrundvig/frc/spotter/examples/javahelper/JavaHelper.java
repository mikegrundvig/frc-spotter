package com.michaelgrundvig.frc.spotter.examples.javahelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The example pack's helper: the one program its probes name, each use a fixed command line. What
 * it finds it prints as one line; why it failed goes to standard error, which the agent puts in the
 * probe's detail.
 *
 * <ul>
 *   <li>{@code java}: the Java it runs on, as {@code java <feature version>};
 *   <li>{@code sha256 FILE}: the file's SHA-256, in lowercase hex.
 * </ul>
 *
 * <p>Exit status: 0 when it found what it was asked for; 2 for a wrong command line; 4 when it
 * failed.
 */
public final class JavaHelper {
  static final int FOUND = 0;
  static final int USAGE = 2;
  static final int FAILED = 4;

  private JavaHelper() {}

  /** Runs one command; see the class. */
  public static void main(String[] args) {
    System.exit(run(List.of(args), System.out, System.err));
  }

  static int run(List<String> args, PrintStream out, PrintStream err) {
    if (args.size() == 1 && args.get(0).equals("java")) {
      out.println("java " + Runtime.version().feature());
      return FOUND;
    }
    if (args.size() == 2 && args.get(0).equals("sha256")) {
      try {
        out.println(sha256(Path.of(args.get(1))));
        return FOUND;
      } catch (IOException e) {
        err.println("can't read " + args.get(1) + ": " + e.getMessage());
        return FAILED;
      }
    }
    err.println("Usage: java-helper java | sha256 FILE");
    return USAGE;
  }

  static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) {
        digest.update(chunk, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }
}
