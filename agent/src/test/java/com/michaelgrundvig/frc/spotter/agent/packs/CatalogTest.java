package com.michaelgrundvig.frc.spotter.agent.packs;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.agent.Agent;
import com.michaelgrundvig.frc.spotter.agent.Fixture;
import com.michaelgrundvig.frc.spotter.manager.CatalogPacks;
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
    Spotter.Description description = described("debian", "raspberry-pi");
    assertThat(description.getProblems()).isEmpty();
    assertThat(description.getPacks())
        .extracting(Spotter.Pack::getName)
        .containsExactly("debian", "raspberry-pi");
  }

  @Test
  void debianHasItsValuesAndItsJournalByTheNamesTheManagerShips() {
    Spotter.Description description = described("debian");
    assertThat(ids(description))
        .containsExactly(
            CatalogPacks.Debian.CPU_BUSIEST,
            CatalogPacks.Debian.CPU_CAPPED,
            CatalogPacks.Debian.THERMAL_HOTTEST,
            CatalogPacks.Debian.THERMAL_MARGIN,
            CatalogPacks.Debian.MEMORY_AVAILABLE,
            CatalogPacks.Debian.DISK_ROOT_FREE,
            CatalogPacks.Debian.NETWORK_UP,
            CatalogPacks.Debian.NETWORK_SPEED,
            CatalogPacks.Debian.NETWORK_DROPS,
            CatalogPacks.Debian.USB_DEVICES,
            CatalogPacks.Debian.USB_DISCONNECTS,
            CatalogPacks.Debian.CLOCK_SYNCED,
            CatalogPacks.Debian.CLOCK_OFFSET,
            CatalogPacks.Debian.DRIVE_WEAR,
            CatalogPacks.Debian.DRIVE_UNSAFE_SHUTDOWNS,
            CatalogPacks.Debian.UPTIME_SECONDS);
    assertThat(description.getLogs())
        .extracting(Spotter.LogDeclaration::getId)
        .containsExactly(CatalogPacks.Debian.JOURNAL);
  }

  @Test
  void raspberryPiHasItsPowerByTheNamesTheManagerShips() {
    assertThat(ids(described("raspberry-pi")))
        .containsExactly(
            CatalogPacks.RaspberryPi.POWER_EPISODES, CatalogPacks.RaspberryPi.POWER_SUMMARY);
  }

  @Test
  void everyNameTheManagerShipsIsOneItsPackHas() throws Exception {
    // Each row: the pack, and the manager's names for it.
    record Row(String pack, Class<?> names) {}
    for (Row row :
        List.of(
            new Row("debian", CatalogPacks.Debian.class),
            new Row("raspberry-pi", CatalogPacks.RaspberryPi.class))) {
      fixture = new Fixture(dir.resolve(row.pack()));
      Spotter.Description description = described(row.pack());
      List<String> has = new ArrayList<>(ids(description));
      for (Spotter.LogDeclaration log : description.getLogs()) {
        has.add(log.getId());
      }
      List<String> shipped = new ArrayList<>();
      for (java.lang.reflect.Field name : row.names().getFields()) {
        shipped.add((String) name.get(null));
      }
      assertThat(shipped).as(row.pack()).isNotEmpty();
      assertThat(has).as(row.pack()).containsExactlyInAnyOrderElementsOf(shipped);
    }
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
          {"raspberry-pi", "undervoltage"}
        }) {
      for (int i = 1; i < program.length; i++) {
        Path file = CATALOG.resolve(program[0]).resolve(program[i]);
        assertThat(Files.isExecutable(file)).as(file.toString()).isTrue();
        assertThat(Files.readString(file)).as(file.toString()).startsWith("#!/bin/sh\n");
      }
    }
  }
}
