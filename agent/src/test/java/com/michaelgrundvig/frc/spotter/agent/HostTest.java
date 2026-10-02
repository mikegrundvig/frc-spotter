package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The computer as the agent finds it on a board: its real files' owners, and its addresses. */
@EnabledOnOs(OS.LINUX)
class HostTest {
  @TempDir Path dir;

  @Test
  void aFilesOwnerAndModeAreTheFilesystems() throws IOException {
    Path file = dir.resolve("etc/frc-spotter/packs/x.yaml");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "pack: x\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    List<String> log = new ArrayList<>();
    Host host = Host.system(dir, new Fixture.FakeCommands(), log::add);
    Host.Owner owner = host.owner("/etc/frc-spotter/packs/x.yaml");
    assertThat(owner.uid()).isEqualTo((Integer) Files.getAttribute(file, "unix:uid"));
    assertThat(owner.mode()).isEqualTo(0664);
    assertThat(owner.writableByOthers()).isTrue();
    assertThat(new Host.Owner(0, 0644).writableByOthers()).isFalse();
    assertThat(new Host.Owner(0, 0646).writableByOthers()).isTrue();
  }

  @Test
  void theSystemsAddressesLeaveOutLoopbackAndLinkLocalIpv6() throws IOException {
    List<String> addresses = Host.systemAddresses();
    assertThat(addresses)
        .hasSizeLessThanOrEqualTo(Host.MAX_ADDRESSES)
        .noneMatch(address -> address.startsWith("127.") || address.equals("0:0:0:0:0:0:0:1"))
        .noneMatch(address -> address.startsWith("fe80:") || address.contains("%"));
    // IPv4 first.
    int firstV6 = addresses.size();
    for (int i = 0; i < addresses.size(); i++) {
      if (addresses.get(i).contains(":")) {
        firstV6 = Math.min(firstV6, i);
      }
    }
    assertThat(addresses.subList(firstV6, addresses.size())).allMatch(a -> a.contains(":"));
  }
}
