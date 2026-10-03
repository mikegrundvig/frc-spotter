package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The team's packs, as the robot pushes them to a board: a folder of pack folders, read once as the
 * manager starts into a bundle ({@code spotter.proto}'s {@code PackBundle}: each file's path,
 * whether it's executable, and its bytes), and the bundle's hash. Each board gets the packs that
 * are its ({@link #select}): its own bundle, and its own hash. The hash is the bundle's own, so
 * it's what the agent gets, writes, and hashes again from its disk; for a whole folder on Linux
 * it's also the folder's ({@link PackHash#of(Path)}).
 */
final class TeamPacks {
  private final Spotter.PackBundle files;
  private final String hash;
  private final byte[] bundle;
  private final long size;

  private TeamPacks(Spotter.PackBundle files) {
    this.files = files;
    this.hash = PackHash.of(files);
    this.bundle = files.toByteArray();
    long bytes = 0;
    for (Spotter.PackFile file : files.getFiles()) {
      bytes += file.getContent().length();
    }
    this.size = bytes;
  }

  /**
   * The packs in a folder; none when it has no files, as no packs push nothing.
   *
   * @throws IOException when a file can't be read
   */
  static @Nullable TeamPacks of(Path folder) throws IOException {
    Spotter.PackBundle bundle = PackHash.bundle(folder);
    return bundle.getFiles().length() == 0 ? null : new TeamPacks(bundle);
  }

  /**
   * The packs a board gets, by their names (a file's path's first part); a file outside any pack's
   * folder goes with every selection. None when nothing's left.
   */
  @Nullable TeamPacks select(Predicate<String> pack) {
    Spotter.PackBundle chosen = Spotter.PackBundle.newInstance();
    for (Spotter.PackFile file : files.getFiles()) {
      String path = file.getPath();
      int slash = path.indexOf('/');
      if (slash < 0 || pack.test(path.substring(0, slash))) {
        chosen.addFiles(file);
      }
    }
    if (chosen.getFiles().length() == 0) {
      return null;
    }
    return chosen.getFiles().length() == files.getFiles().length() ? this : new TeamPacks(chosen);
  }

  /**
   * Why a board can't take them, past what a push may carry ({@link Protocol#MAX_FILES} files,
   * {@link Protocol#MAX_BUNDLE} bytes); empty when it can.
   */
  String tooLarge() {
    int count = files.getFiles().length();
    if (count > Protocol.MAX_FILES) {
      return count + " files, more than the " + Protocol.MAX_FILES + " a push may carry";
    }
    if (size > Protocol.MAX_BUNDLE) {
      return (size >> 20)
          + " MiB, more than the "
          + (Protocol.MAX_BUNDLE >> 20)
          + " MiB a push may carry";
    }
    return "";
  }

  /** The names of the packs among them: each file's path's first part. */
  java.util.Set<String> names() {
    java.util.Set<String> names = new java.util.TreeSet<>();
    for (Spotter.PackFile file : files.getFiles()) {
      int slash = file.getPath().indexOf('/');
      if (slash > 0) {
        names.add(file.getPath().substring(0, slash));
      }
    }
    return names;
  }

  /** Their hash. */
  String hash() {
    return hash;
  }

  /** The bundle, as {@code POST /v2/packs} sends it: protobuf. */
  byte[] bundle() {
    return bundle;
  }
}
