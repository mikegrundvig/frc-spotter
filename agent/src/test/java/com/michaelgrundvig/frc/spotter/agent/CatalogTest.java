package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalog's packs (the repository's packs/), installed as a board's installer puts them: each
 * loads with no problem, its programs are there and executable, and it has the values, logs and
 * actions its README names. What they measure is the container tests' (CatalogContainerTest).
 */
class CatalogTest {
  /** The catalog, as the build gives it. */
  static final Path CATALOG = Path.of(System.getProperty("spotter.catalog", "../packs"));

  @TempDir Path dir;
  Fixture fixture;

  @BeforeEach
  void aBoard() throws IOException {
    fixture = new Fixture(dir);
  }

  /** Copies a catalog pack's own files (no build output) into the board's installed packs. */
  private void install(String name) {
    Path from = CATALOG.resolve(name);
    Path to = fixture.path(Packs.INSTALLED + "/" + name);
    try (Stream<Path> files = Files.walk(from)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path relative = from.relativize(file);
        if (relative.startsWith("build")) {
          continue;
        }
        Path target = to.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(file, target);
        target.toFile().setExecutable(Files.isExecutable(file), false);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Spotter.Description described(String... names) {
    for (String name : names) {
      install(name);
    }
    Agent agent = fixture.agent();
    Spotter.Description description = agent.description();
    agent.close();
    return description;
  }

  private static List<String> ids(Spotter.Description description) {
    List<String> ids = new ArrayList<>();
    for (Spotter.FieldDeclaration value : description.getValues()) {
      ids.add(value.getId());
    }
    return ids;
  }

  @Test
  void everyCatalogPackLoadsWithNoProblem() {
    Spotter.Description description = described("debian", "photonvision", "raspberry-pi");
    assertThat(description.getProblems()).isEmpty();
    assertThat(description.getPacks())
        .extracting(Spotter.Pack::getName)
        .containsExactly("debian", "photonvision", "raspberry-pi");
  }

  @Test
  void debianHasItsValuesAndItsJournal() {
    Spotter.Description description = described("debian");
    assertThat(ids(description))
        .containsExactly(
            "debian.cpu.busiest",
            "debian.cpu.capped",
            "debian.thermal.hottest",
            "debian.thermal.margin",
            "debian.memory.available",
            "debian.disk.root-free",
            "debian.network.up",
            "debian.network.speed",
            "debian.network.drops",
            "debian.usb.devices",
            "debian.usb.disconnects",
            "debian.clock.synced",
            "debian.clock.offset",
            "debian.drive.wear",
            "debian.drive.unsafe-shutdowns",
            "debian.uptime.seconds");
    assertThat(description.getLogs())
        .extracting(Spotter.LogDeclaration::getId)
        .contains("debian.journal");
  }

  @Test
  void photonvisionHasItsValuesLogAndActions() {
    Spotter.Description description = described("photonvision");
    assertThat(ids(description))
        .containsExactly(
            "photonvision.service.running",
            "photonvision.web.status",
            "photonvision.settings.hash");
    assertThat(description.getLogs())
        .extracting(Spotter.LogDeclaration::getId)
        .containsExactly("photonvision.log");
    assertThat(description.getActions())
        .extracting(Spotter.ActionDeclaration::getId)
        .contains("photonvision.restart", "photonvision.export")
        .doesNotContain("photonvision.layout");
  }

  @Test
  void raspberryPiHasItsPower() {
    assertThat(ids(described("raspberry-pi")))
        .containsExactly("raspberry-pi.power.episodes", "raspberry-pi.power.summary");
  }

  @Test
  void theDesignsDebianPackIsTheCatalogs() throws IOException {
    // The catalog's debian pack is the design's example, as written; its scripts are the catalog's.
    assertThat(Files.readString(CATALOG.resolve("debian/pack.yaml")))
        .isEqualTo(PackReaderTest.example("debian"));
  }

  @Test
  void everyProgramAPackRunsIsThereAndExecutable() throws IOException {
    for (String[] program :
        new String[][] {
          {"debian", "cpu", "thermal", "memory", "disk", "network", "usb", "clock", "journal"},
          {"debian", "drive-health"},
          {"photonvision", "journal"},
          {"raspberry-pi", "undervoltage"}
        }) {
      for (int i = 1; i < program.length; i++) {
        Path file = CATALOG.resolve(program[0]).resolve(program[i]);
        assertThat(Files.isExecutable(file)).as(file.toString()).isTrue();
        assertThat(Files.readString(file)).as(file.toString()).startsWith("#!/bin/sh\n");
      }
    }
    // The photonvision pack's journal is the debian pack's, copied.
    assertThat(Files.readString(CATALOG.resolve("photonvision/journal")))
        .isEqualTo(Files.readString(CATALOG.resolve("debian/journal")));
  }
}
