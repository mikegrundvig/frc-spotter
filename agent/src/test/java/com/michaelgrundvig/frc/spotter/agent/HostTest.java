package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The board as the agent finds it on a real filesystem: its files' owners, and its addresses. */
class HostTest {
  @TempDir Path dir;

  @Test
  void aFilesOwnerAndModeAreTheFilesystemsOwn() throws Exception {
    List<String> log = new ArrayList<>();
    Host host = Host.system(dir, log::add);
    Path file = dir.resolve("etc/frc-spotter/packs/vision/pack.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "pack: vision\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    Host.Owner owner = host.owner("/etc/frc-spotter/packs/vision/pack.yaml");
    assertThat(owner.uid()).isEqualTo((Integer) Files.getAttribute(file, "unix:uid"));
    assertThat(owner.mode()).isEqualTo(0664);
    assertThat(owner.writableByOthers()).isTrue();
    assertThat(host.list("/etc/frc-spotter/packs")).containsExactly("vision");
    assertThat(host.list("/nothing")).isEmpty();
    assertThat(host.isFolder("/etc/frc-spotter/packs/vision")).isTrue();
    assertThat(host.line("/etc/frc-spotter/packs/vision/pack.yaml")).hasValue("pack: vision");
    assertThat(host.read("/nothing")).isEmpty();
    assertThat(host.monotonicNanos()).isPositive();
    host.log("logged");
    assertThat(log).containsExactly("logged");
  }

  @Test
  void itsAddressesLeaveOutLoopbackAndLinkLocal() throws Exception {
    assertThat(Host.systemAddresses())
        .hasSizeLessThanOrEqualTo(Host.MAX_ADDRESSES)
        .noneMatch(a -> a.startsWith("127.") || a.equals("::1") || a.startsWith("fe80:"))
        .noneMatch(a -> a.contains("%"));
  }
}
