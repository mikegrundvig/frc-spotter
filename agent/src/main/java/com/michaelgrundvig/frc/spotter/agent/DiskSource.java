package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Disk;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The space on the root and on {@code /data} (where an image with a read-only root keeps what's
 * written while it runs), for those there: a full {@code /data} fails saves quietly.
 */
final class DiskSource {
  /** The filesystems reported, where they're mounted. */
  static final List<String> MOUNTS = List.of("/", "/data");

  private static final long MIB = 1024 * 1024;

  private final Host host;

  DiskSource(Host host) {
    this.host = host;
  }

  List<Disk> read() throws IOException {
    List<Disk> disks = new ArrayList<>();
    for (String mount : MOUNTS) {
      Path path = host.path(mount);
      if (!Files.isDirectory(path)) {
        continue;
      }
      FileStore store = Files.getFileStore(path);
      disks.add(
          new Disk(
              mount,
              store.getTotalSpace() / MIB,
              store.getUsableSpace() / MIB,
              store.isReadOnly()));
    }
    return disks;
  }
}
