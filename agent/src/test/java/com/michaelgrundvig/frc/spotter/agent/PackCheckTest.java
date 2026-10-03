package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Packs checked off any board, by the agent's own reader, and the mistakes it takes but flags. */
class PackCheckTest {
  @TempDir Path dir;

  private Path pack(String name, String yaml) throws Exception {
    Path folder = dir.resolve("packs").resolve(name);
    Files.createDirectories(folder);
    Files.writeString(folder.resolve(Pack.FILE), yaml);
    return folder;
  }

  @Test
  void aGoodPackPassesWithItsNotes() throws Exception {
    Path folder =
        pack(
            "web",
            "pack: web\ncollectors:\n  - id: page\n    http: {get: \"http://localhost:5800/\"}\n"
                + "    every: 1s\n    fields:\n      status: {type: number}\n");
    PackCheck.Report report = PackCheck.check(List.of(folder));
    assertThat(report.passed()).isTrue();
    assertThat(report.packs()).isEqualTo(1);
    assertThat(report.notes())
        .containsExactly(
            folder.toString().replace('\\', '/')
                + "/pack.yaml:7: collector page's field status is its command's own exit code or"
                + " HTTP status, not a key of its output",
            PackCheck.TRUST_SKIPPED);
  }

  @Test
  void aProgramOfThePacksOwnMustRunOncePushed() throws Exception {
    Path folder = pack("p", "pack: p\n");
    Files.writeString(folder.resolve("plain"), "echo 1\n");
    Files.writeString(folder.resolve("script"), "#!/bin/sh\necho 1\n");
    Files.writeString(folder.resolve("binary"), "\u007fELF");
    Files.setPosixFilePermissions(
        folder.resolve("binary"), PosixFilePermissions.fromString("rwxr-xr-x"));
    assertThat(PackCheck.program(folder, "plain")).contains("neither an execute bit nor a #!");
    assertThat(PackCheck.program(folder, "script")).isNull();
    assertThat(PackCheck.program(folder, "binary")).isNull();
    assertThat(PackCheck.program(folder, "nothing"))
        .isEqualTo("./nothing isn't in the pack's folder");
  }

  @Test
  void aFolderOfPacksIsHeldToABoardsBounds() throws Exception {
    StringBuilder actions = new StringBuilder("actions:\n");
    for (int i = 0; i < 32; i++) {
      actions.append("  - {id: a").append(i).append(", run: [true]}\n");
    }
    for (int i = 0; i < 4; i++) {
      pack("a" + i, "pack: a" + i + "\n" + actions);
    }
    PackCheck.Report report = PackCheck.check(List.of(dir.resolve("packs")));
    assertThat(report.passed()).isFalse();
    assertThat(report.problems())
        .containsExactly(
            dir.resolve("packs").resolve("a3").toString().replace('\\', '/')
                + ": ignored, as its 32 actions would pass the 128 a board may have (98 so far)");
  }
}
