package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The team's packs, as the robot pushes them: a folder of pack folders, read once as the manager
 * starts into the bundle that's pushed ({@code spotter.proto}'s {@code PackBundle}: each file's
 * path, whether it's executable, and its bytes), and the bundle's hash. The hash is the bundle's
 * own, so it's what the agent gets, writes, and hashes again from its disk; for a folder on Linux
 * it's also the folder's ({@link PackHash#of(Path)}).
 */
final class TeamPacks {
  private final String hash;
  private final byte[] bundle;

  private TeamPacks(Spotter.PackBundle bundle) {
    this.hash = PackHash.of(bundle);
    this.bundle = bundle.toByteArray();
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

  /** Their hash. */
  String hash() {
    return hash;
  }

  /** The bundle, as {@code POST /v2/packs} sends it: protobuf. */
  byte[] bundle() {
    return bundle;
  }
}
