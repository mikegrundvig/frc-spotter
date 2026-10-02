package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.probes.Pack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spotter's catalog of packs ({@code packs/}), read as the agent reads them, so the catalog can't
 * drift from the format: every file a pack, headed by what it checks and where to copy it, and all
 * of them copied onto one computer together without a clash.
 */
class CatalogTest {
  @TempDir Path dir;

  static List<Path> catalog() throws IOException {
    try (Stream<Path> files = Files.list(Fixture.PROJECT.resolve("packs"))) {
      return files.filter(file -> file.toString().endsWith(Pack.SUFFIX)).sorted().toList();
    }
  }

  @Test
  void theCatalogHasItsPacks() throws IOException {
    assertThat(catalog())
        .extracting(file -> file.getFileName().toString())
        .contains("health.yaml", "my-program.yaml", "orangepi-rk3588.yaml", "photonvision.yaml");
  }

  @Test
  void everyPackIsReadByTheAgentsOwnReaderAndSaysWhereItGoes() throws IOException {
    for (Path file : catalog()) {
      String name = file.getFileName().toString();
      String yaml = Files.readString(file, StandardCharsets.UTF_8);
      Pack pack = Pack.parseYaml(yaml, "packs/" + name);
      assertThat(name).isEqualTo(pack.name() + Pack.SUFFIX);
      assertThat(pack.probes()).as(name).isNotEmpty();
      assertThat(pack.probes()).as(name).allMatch(probe -> probe.everySeconds() > 0);
      assertThat(yaml).as(name).startsWith("# ");
      String header = yaml.substring(0, yaml.indexOf("\npack:"));
      assertThat(header).as(name).contains(Pack.DIRECTORY + "/" + name);
    }
  }

  @Test
  void theWholeCatalogRunsTogetherOnOneComputer() throws IOException {
    Fixture fixture = new Fixture(dir);
    fixture.commands.answer(List.of("journalctl"), List.of());
    for (Path file : catalog()) {
      String name = file.getFileName().toString();
      fixture.pack(
          name.substring(0, name.length() - Pack.SUFFIX.length()),
          Files.readString(file, StandardCharsets.UTF_8));
    }
    Configuration configuration = Configuration.read(fixture.host);
    assertThat(configuration.problems()).isEmpty();
    assertThat(configuration.probes().packs()).hasSize(catalog().size());
  }

  @Test
  void healthHoldsAnyBoardsOwnMeasurementsToLimits() throws IOException {
    Fixture fixture = new Fixture(dir);
    fixture.commands.answer(List.of("journalctl"), List.of());
    fixture.pack(
        "health",
        Files.readString(Fixture.PROJECT.resolve("packs/health.yaml"), StandardCharsets.UTF_8));
    Agent agent = fixture.agent();
    agent.probes().runAll();
    fixture.micros.addAndGet(1_000_000);
    List<ProbeResult> results = agent.health().probes();
    assertThat(results)
        .extracting(ProbeResult::id, ProbeResult::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("health.thermal-margin", ProbeResult.PASS),
            // The fixture's big cores are held to 2016 of 2400 MHz.
            org.assertj.core.groups.Tuple.tuple("health.cpu-capped", ProbeResult.FAIL),
            org.assertj.core.groups.Tuple.tuple("health.memory", ProbeResult.PASS),
            // The fixture's root is a folder on the computer running the tests: some disk's space.
            org.assertj.core.groups.Tuple.tuple(
                "health.root-free",
                results.get(3).status().equals(ProbeResult.FAIL)
                    ? ProbeResult.FAIL
                    : ProbeResult.PASS));
    assertThat(results.get(0).value()).isEqualTo("12.2");
  }
}
