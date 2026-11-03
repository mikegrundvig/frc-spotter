package com.michaelgrundvig.frc.spotter.manager.packs;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * The team's packs, as the robot pushes them to a board: a folder of pack folders, read once as the
 * manager starts into a bundle ({@code spotter.proto}'s {@code PackBundle}: each file's path,
 * whether it's executable, and its bytes), and the bundle's hash. Each board gets exactly the packs
 * it names ({@link #select}): its own bundle, and its own hash, empty when it names none. The hash
 * is the bundle's own, so it's what the agent gets, writes, and hashes again from its disk.
 */
public final class TeamPacks {
  /** What makes a folder a pack: this file at its top. */
  static final String PACK_FILE = "pack.yaml";

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
   * The packs in a folder: each folder in it with a {@code pack.yaml} at its top, and its files.
   *
   * @throws IOException when a file can't be read
   */
  public static TeamPacks of(Path folder) throws IOException {
    return new TeamPacks(PackHash.bundle(folder));
  }

  /**
   * The packs a board gets: exactly those by these names (a pack's name is its folder's), and none
   * of the folder's other files. None when it names none, or none of them is a pack here.
   */
  public TeamPacks select(Collection<String> named) {
    Set<String> packs = names();
    Spotter.PackBundle chosen = Spotter.PackBundle.newInstance();
    for (Spotter.PackFile file : files.getFiles()) {
      String pack = pack(file.getPath());
      if (named.contains(pack) && packs.contains(pack)) {
        chosen.addFiles(file);
      }
    }
    return chosen.getFiles().length() == files.getFiles().length() ? this : new TeamPacks(chosen);
  }

  /** The pack a file's path is in: its first part; empty for a file at the folder's top. */
  private static String pack(String path) {
    int slash = path.indexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  /**
   * Why a board can't take them, past what a push may carry ({@link Protocol#MAX_FILES} files,
   * {@link Protocol#MAX_BUNDLE} bytes); empty when it can.
   */
  public String tooLarge() {
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

  /** The names of the packs among them: each folder with a {@code pack.yaml} at its top. */
  public Set<String> names() {
    Set<String> names = new TreeSet<>();
    for (Spotter.PackFile file : files.getFiles()) {
      String pack = pack(file.getPath());
      if (!pack.isEmpty() && file.getPath().equals(pack + "/" + PACK_FILE)) {
        names.add(pack);
      }
    }
    return names;
  }

  /** Their hash. */
  public String hash() {
    return hash;
  }

  /** The bundle, as {@code POST /v2/packs} sends it: protobuf. */
  public byte[] bundle() {
    return bundle;
  }
}
