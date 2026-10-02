package com.michaelgrundvig.frc.spotter.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.probes.Check;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import com.michaelgrundvig.frc.spotter.probes.Step;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import com.michaelgrundvig.frc.spotter.table.Pack;
import com.michaelgrundvig.frc.spotter.table.Packs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build's tasks on a repository made for each test: Spotter's built-in pack, and a team's own
 * pack for a service it runs (a systemd unit with a page that says it's healthy).
 */
class CoprocessorBuildTest {
  @TempDir Path root;

  /** Spotter's repository, whose built-in pack every test repository gets. */
  static final Path PROJECT = Path.of(System.getProperty("frc.projectDir", "../.."));

  /** A team's pack: its service's unit and health page, its step before a power-off. */
  static final String SERVICE_PACK =
      """
      pack: service
      journalUnits: [vision.service]
      probes:
        - id: service.unit
          kind: unit
          unit: vision.service
          every: 2
        - id: service.health
          kind: http
          url: http://localhost:5800/health
          every: 5
        - id: service.camera.{camera}
          each: camera
          kind: command
          argv: ['{pack}/bin/check-camera', '{computer}', '{camera}']
          every: 10
      beforeShutdown:
        - name: service.stop
          argv: [systemctl, stop, vision.service]
          timeout: 30
      downloads:
        - name: backup.zip
          argv: ['{pack}/bin/backup', '{computer}']
          contentType: application/zip
          maxBytes: 1048576
          timeout: 30
      """;

  private void write(String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  @BeforeEach
  void aRepository() throws IOException {
    String builtin = Pack.TEMPLATE_PACKS + "/" + Pack.BUILTIN + "/" + Pack.FILE;
    write(builtin, Files.readString(PROJECT.resolve(builtin), StandardCharsets.UTF_8));
    write(Pack.TEAM_PACKS + "/service/" + Pack.FILE, SERVICE_PACK);
    write(
        "coprocessors/coprocessors.yaml",
        """
        team: 1234
        computers:
          - name: vision-front
            address: 11
            image:
              board: orangepi-5
            cameras: [front-left, front-right]
            packs: [service]
            ports:
              front-left: platform-fc880000.usb-usb-0:1:1.0-video-index0
            probes:
              - id: front-left.fast
                kind: usb
                path: /dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0
                minSpeedMbps: 5000
                every: 5
          - name: vision-back
            address: 12
            image:
              board: orangepi-5
        """);
  }

  @Test
  void theTableIsCompiledWithEachComputersProbes() throws IOException {
    Path out = root.resolve("build/generated/coprocessor/coprocessor/table.json");
    int status =
        CoprocessorBuild.run(
            new String[] {"table", root.toString(), out.toString()}, System.out, System.err);
    assertThat(status).isZero();
    CompiledTable compiled = CompiledTable.parse(Files.readString(out, StandardCharsets.UTF_8));
    assertThat(compiled.table().team()).isEqualTo(1234);
    assertThat(compiled.probeSets()).containsKeys("vision-front", "vision-back");
    assertThat(compiled).isEqualTo(CoprocessorBuild.compile(root));
  }

  @Test
  void eachComputersProbesAreCompiledFromItsPacksAndTheTable() throws IOException {
    Path out = root.resolve("build/coprocessor/probes");
    ByteArrayOutputStream printed = new ByteArrayOutputStream();
    int status =
        CoprocessorBuild.run(
            new String[] {"probes", root.toString(), out.toString()},
            new PrintStream(printed, true, StandardCharsets.UTF_8),
            System.err);
    assertThat(status).isZero();
    ProbeSet front =
        ProbeSet.parse(Files.readString(out.resolve("vision-front.json"), StandardCharsets.UTF_8));
    assertThat(front.packs()).containsExactly("builtin", "service");
    assertThat(front.probes())
        .extracting(Probe::id)
        .contains(
            "builtin.thermal-margin",
            "service.unit",
            "service.camera.front-left",
            "service.camera.front-right",
            "camera.front-left",
            "front-left.fast")
        .endsWith("front-left.fast")
        // The built-in camera probe is for a camera whose port is known.
        .doesNotContain("camera.front-right");
    assertThat(((Check.Usb) front.probe("camera.front-left").orElseThrow().check()).path())
        .isEqualTo("/dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0");
    // Every placeholder is filled in: the pack's folder, the computer, each camera.
    Probe camera = front.probe("service.camera.front-right").orElseThrow();
    assertThat(((Check.Command) camera.check()).argv())
        .containsExactly(
            "/usr/lib/frc-spotter/packs/service/bin/check-camera", "vision-front", "front-right");
    assertThat(front.download("backup.zip").orElseThrow().argv()).contains("vision-front");
    assertThat(front.beforeShutdown()).extracting(Step::name).containsExactly("service.stop");
    assertThat(front.journalUnits()).containsExactly("vision.service");
    // The file's own SHA-256 is the hash the stamp carries.
    assertThat(printed.toString(StandardCharsets.UTF_8))
        .contains(front.hash() + "  vision-front.json")
        .contains("vision-back.json");
    assertThat(
            ProbeSet.sha256(
                Files.readString(out.resolve("vision-front.json"), StandardCharsets.UTF_8)))
        .isEqualTo(front.hash());
    ProbeSet back =
        ProbeSet.parse(Files.readString(out.resolve("vision-back.json"), StandardCharsets.UTF_8));
    assertThat(back.packs()).containsExactly("builtin");
    assertThat(back.beforeShutdown()).isEmpty();

    // Each computer's agent configuration, which its agent compiles the same definitions from.
    Path configs = root.resolve("build/coprocessor/agent");
    assertThat(
            CoprocessorBuild.run(
                new String[] {"agent-configs", root.toString(), configs.toString()},
                System.out,
                System.err))
        .isZero();
    AgentConfig config =
        AgentConfig.parse(
            Files.readString(configs.resolve("vision-front.json"), StandardCharsets.UTF_8), "x");
    assertThat(config.controller()).isEqualTo("10.12.34.2");
    assertThat(config.packs()).containsExactly("service");
    assertThat(config.cameras().get(0).port())
        .isEqualTo("/dev/v4l/by-path/platform-fc880000.usb-usb-0:1:1.0-video-index0");
    assertThat(config.cameras().get(1).port()).isEmpty();
    List<Pack> packs = new ArrayList<>();
    for (String folder : List.of("packs/builtin", "coprocessors/packs/service")) {
      Path json = root.resolve("build/" + folder + ".json");
      assertThat(
              CoprocessorBuild.run(
                  new String[] {
                    "pack", root.resolve(folder + "/pack.yaml").toString(), json.toString()
                  },
                  System.out,
                  System.err))
          .isZero();
      packs.add(Pack.fromJson(Json.parse(Files.readString(json)), folder));
    }
    assertThat(Packs.compile(config, packs)).isEqualTo(front);

    // The compiled table carries them, so the robot knows what each image defines.
    CompiledTable compiled = CoprocessorBuild.compile(root);
    assertThat(compiled.probeSet(compiled.table().computers().get(0))).isEqualTo(front);
  }

  @Test
  void packsAreFoundInFoldersOutsideTheRepositoryAndTheBuiltInOneInTheJar() throws IOException {
    // No built-in pack in the repository: the jar's is the same.
    ProbeSet withIt = CoprocessorBuild.compile(root).probeSets().get("vision-back");
    Files.delete(root.resolve("packs/builtin/pack.yaml"));
    assertThat(CoprocessorBuild.compile(root).probeSets().get("vision-back")).isEqualTo(withIt);

    // A builder's pack, in a folder of its own.
    Path builder = root.resolveSibling(root.getFileName() + "-builder");
    Files.createDirectories(builder.resolve("service"));
    Files.move(
        root.resolve(Pack.TEAM_PACKS + "/service/" + Pack.FILE),
        builder.resolve("service/" + Pack.FILE));
    assertThatThrownBy(() -> CoprocessorBuild.compile(root))
        .hasMessageContaining("no pack named \"service\": not at coprocessors/packs/service");
    Path out = root.resolve("build/table.json");
    assertThat(
            CoprocessorBuild.run(
                new String[] {
                  "table", "--packs", builder.toString(), root.toString(), out.toString()
                },
                System.out,
                System.err))
        .isZero();
    CompiledTable compiled = CompiledTable.parse(Files.readString(out));
    assertThat(compiled.probeSet(compiled.table().computers().get(0)).packs())
        .containsExactly("builtin", "service");
  }

  @Test
  void aBrokenPackOrProbeFailsTheBuildSayingWhere() throws IOException {
    write(
        "coprocessors/coprocessors.yaml",
        """
        team: 1234
        computers:
          - name: vision-front
            address: 11
            image:
              board: orangepi-5
            packs: [nothing-here]
        """);
    assertThatThrownBy(() -> CoprocessorBuild.compile(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no pack named \"nothing-here\"");
    write(
        "coprocessors/coprocessors.yaml",
        """
        team: 1234
        computers:
          - name: vision-front
            address: 11
            image:
              board: orangepi-5
            probes:
              - id: builtin.memory
                kind: unit
                unit: x.service
        """);
    assertThatThrownBy(() -> CoprocessorBuild.compile(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "coprocessors/coprocessors.yaml:8: probe builtin.memory is already defined at packs/builtin/pack.yaml:");
  }

  @Test
  void aBrokenTableFailsTheBuildSayingWhereAndAWrongCommandIsShownHowToRun() throws IOException {
    write("coprocessors/coprocessors.yaml", "team: 0\ncomputers:\n  - name: Bad\n");
    assertThatThrownBy(() -> CoprocessorBuild.compile(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("coprocessors.yaml:3: name \"Bad\" isn't a hostname");

    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream printOut =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    PrintStream printErr = new PrintStream(err, true, StandardCharsets.UTF_8);
    assertThat(
            CoprocessorBuild.run(
                new String[] {"table", root.toString(), root.resolve("out.json").toString()},
                printOut,
                printErr))
        .isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("isn't a hostname");
    assertThat(CoprocessorBuild.run(new String[] {"nope"}, printOut, printErr)).isEqualTo(2);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage:");
  }
}
