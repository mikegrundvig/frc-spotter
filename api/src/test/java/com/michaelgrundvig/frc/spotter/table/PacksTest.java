package com.michaelgrundvig.frc.spotter.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.probes.Check;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Packs read from YAML with every problem at its line, the table's own probes and ports, the
 * agent's configuration, and one computer's definitions compiled from them all, placeholders
 * filled.
 */
class PacksTest {
  @TempDir Path dir;

  static final String PACK =
      """
      pack: lidar
      journalUnits: [lidar.service]
      probes:
        - id: lidar.unit
          kind: unit
          unit: lidar.service
          every: 2
        - id: lidar.port.{camera}
          each: camera
          kind: command
          argv: ['{pack}/bin/check', '{computer}', '{camera}', '{port}']
          match: '^ok (\\d+)$'
          timeout: 5
          every: 10
      beforeShutdown:
        - name: lidar.stop
          argv: [systemctl, stop, lidar.service]
          timeout: 20
      downloads:
        - name: scan.bin
          argv: ['{pack}/bin/scan']
          contentType: application/octet-stream
          maxBytes: 1048576
          timeout: 10
      """;

  static final AgentConfig CONFIG =
      new AgentConfig(
          "vision-front",
          "10.12.34.2",
          5808,
          List.of("lidar"),
          List.of(
              new AgentConfig.Camera("front-left", "platform-a-usb-0:1:1.0-video-index0"),
              new AgentConfig.Camera("front-right", "")),
          List.of());

  @Test
  void aPackCompilesWithItsPlaceholdersFilled() {
    Pack pack = Pack.parseYaml(PACK, "lidar/pack.yaml");
    assertThat(Pack.fromJson(Json.parse(Json.compact(pack.toJson())), "lidar/pack.json"))
        .isEqualTo(pack);
    ProbeSet set = Packs.compile(CONFIG, List.of(pack));
    assertThat(set.packs()).containsExactly("lidar");
    // The camera without a port has no probe that reads its port.
    assertThat(set.probes())
        .extracting(Probe::id)
        .containsExactly("lidar.unit", "lidar.port.front-left");
    assertThat(((Check.Command) set.probes().get(1).check()).argv())
        .containsExactly(
            "/usr/lib/frc-coprocessor/packs/lidar/bin/check",
            "vision-front",
            "front-left",
            "/dev/v4l/by-path/platform-a-usb-0:1:1.0-video-index0");
    assertThat(set.probes().get(1).pack()).isEqualTo("lidar");
    assertThat(set.beforeShutdown().get(0).argv())
        .containsExactly("systemctl", "stop", "lidar.service");
    assertThat(set.downloads().get(0).argv())
        .containsExactly("/usr/lib/frc-coprocessor/packs/lidar/bin/scan");
    assertThat(set.journalUnits()).containsExactly("lidar.service");
  }

  @Test
  void aPacksProblemsAreEachAtTheirLine() {
    String broken =
        """
        pack: Lidar
        extra: 1
        probes:
          - id: a
            kind: unit
            unit: a.service
            every: often
          - id: b
            kind: command
            argv: [sh, -c, reboot]
          - id: c
            each: lens
            kind: unit
            unit: c.service
          - id: d
            kind: unit
            unit: '{camera}.service'
          - just text
        downloads: nothing
        """;
    assertThatThrownBy(() -> Pack.parseYaml(broken, "p.yaml"))
        .isInstanceOf(TableException.class)
        .satisfies(
            e ->
                assertThat(((TableException) e).problems())
                    .anyMatch(p -> p.startsWith("p.yaml:1: pack Lidar isn't a pack's name"))
                    .anyMatch(p -> p.startsWith("p.yaml:2: unknown key \"extra\""))
                    .anyMatch(p -> p.startsWith("p.yaml:7: every must be a number"))
                    .anyMatch(p -> p.startsWith("p.yaml:8:") && p.contains("never a shell"))
                    .anyMatch(p -> p.startsWith("p.yaml:12: each may only be camera"))
                    .anyMatch(p -> p.startsWith("p.yaml:15:") && p.contains("{camera} is only for"))
                    .anyMatch(p -> p.startsWith("p.yaml:18: each item is a mapping"))
                    .anyMatch(p -> p.startsWith("p.yaml:19: expected a list")));
    assertThatThrownBy(() -> Pack.parseYaml("probes: []", "q.yaml"))
        .hasMessageContaining("q.yaml:1: pack: (its name) is missing");
    assertThatThrownBy(() -> Pack.parseYaml("- a", "r.yaml"))
        .hasMessageContaining("expected pack:");
    assertThatThrownBy(
            () ->
                Pack.parseYaml(
                    "pack: x\nprobes:\n  - id: a\n    kind: unit\n    unit: '{nothing}.service'\n",
                    "s.yaml"))
        .hasMessageContaining("{nothing} isn't a placeholder");
  }

  @Test
  void twoDefinitionsOfOneNameAreRefusedNamingBoth() {
    Pack pack = Pack.parseYaml(PACK, "lidar/pack.yaml");
    Pack again = new Pack("again", pack.probes(), List.of(), List.of(), List.of());
    assertThatThrownBy(() -> Packs.compile(CONFIG, List.of(pack, again)))
        .hasMessageContaining("probe lidar.unit is already defined at lidar/pack.yaml:4");
  }

  @Test
  void packsAreTheTeamsBeforeTheTemplates() throws IOException {
    write("packs/lidar/pack.yaml", PACK);
    assertThat(Pack.read(dir, "lidar").journalUnits()).containsExactly("lidar.service");
    write("coprocessors/packs/lidar/pack.yaml", "pack: lidar\njournalUnits: [mine.service]\n");
    assertThat(Pack.read(dir, "lidar").journalUnits()).containsExactly("mine.service");
    write("coprocessors/packs/other/pack.yaml", "pack: lidar\n");
    assertThatThrownBy(() -> Pack.read(dir, "other"))
        .hasMessageContaining("its pack is \"lidar\", not \"other\"");
    assertThatThrownBy(() -> Pack.read(dir, "none")).hasMessageContaining("no pack named \"none\"");
  }

  @Test
  void theTablesComputersNamePacksProbesAndPorts() {
    Table table =
        Table.parseYaml(
            """
            team: 1234
            computers:
              - name: vision-front
                address: 11
                cameras: [front-left, front right]
                packs: [vision]
                ports:
                  front-left: platform-a-usb-0:1:1.0-video-index0
                  front right: /dev/v4l/by-path/platform-b-usb-0:1:1.0-video-index0
                probes:
                  - id: own
                    kind: file
                    path: /etc/hostname
            """,
            "t.yaml");
    Computer computer = table.computers().get(0);
    assertThat(computer.packs()).containsExactly("vision");
    assertThat(computer.ports()).containsKeys("front-left", "front right");
    assertThat(computer.probes()).hasSize(1);
    assertThat(Table.fromJson(Json.parse(Json.compact(table.toJson())))).isEqualTo(table);
    AgentConfig config = AgentConfig.of(table, computer);
    assertThat(config.controller()).isEqualTo("10.12.34.2");
    assertThat(config.cameraNames()).containsExactly("front-left", "front right");
    assertThat(config.cameras().get(0).port())
        .isEqualTo("/dev/v4l/by-path/platform-a-usb-0:1:1.0-video-index0");
    assertThat(AgentConfig.parse(config.text(), "agent.json")).isEqualTo(config);
  }

  @Test
  void theTablesPacksAndPortsAreChecked() {
    String table =
        """
        team: 1234
        computers:
          - name: vision-front
            address: 11
            agentPort: 5808
            cameras: [front-left]
            packs: [builtin, Vision, vision, vision]
            ports:
              back-left: platform-a-video-index0
              front-left: /dev/video0
            probes:
              - id: own
                kind: telepathy
        """;
    assertThatThrownBy(() -> Table.parseYaml(table, "t.yaml"))
        .satisfies(
            e ->
                assertThat(((TableException) e).problems())
                    .anyMatch(p -> p.startsWith("t.yaml:7: pack builtin needn't be listed"))
                    .anyMatch(p -> p.startsWith("t.yaml:7: pack \"Vision\" isn't a pack's name"))
                    .anyMatch(p -> p.startsWith("t.yaml:7: pack vision is listed twice"))
                    .anyMatch(p -> p.startsWith("t.yaml:9: back-left isn't one of"))
                    .anyMatch(
                        p -> p.startsWith("t.yaml:10:") && p.contains("isn't a /dev/v4l/by-path/"))
                    .anyMatch(p -> p.startsWith("t.yaml:12:") && p.contains("kind \"telepathy\"")));
    assertThatThrownBy(
            () ->
                Table.parseYaml(
                    "team: 1\ncomputers:\n  - name: a\n    address: 11\n    agentPort: 5808\n    packs: x\n    ports: [a]\n",
                    "u.yaml"))
        .satisfies(
            e ->
                assertThat(((TableException) e).problems())
                    .anyMatch(p -> p.startsWith("u.yaml:6: packs must be a list"))
                    .anyMatch(p -> p.startsWith("u.yaml:7: ports is a mapping")));
  }

  @Test
  void anAgentConfigurationIsChecked() {
    assertThatThrownBy(() -> new AgentConfig("Bad", "", 5808, List.of(), List.of(), List.of()))
        .hasMessageContaining("isn't a hostname");
    assertThatThrownBy(
            () -> new AgentConfig("a", "robot.local", 5808, List.of(), List.of(), List.of()))
        .hasMessageContaining("isn't an IPv4 address");
    assertThatThrownBy(() -> new AgentConfig("a", "", 80, List.of(), List.of(), List.of()))
        .hasMessageContaining("is out of range");
    assertThatThrownBy(() -> new AgentConfig("a", "", 5808, List.of("table"), List.of(), List.of()))
        .hasMessageContaining("needn't be listed");
    AgentConfig.Camera camera = new AgentConfig.Camera("x", "");
    assertThatThrownBy(
            () -> new AgentConfig("a", "", 5808, List.of(), List.of(camera, camera), List.of()))
        .hasMessageContaining("listed twice");
    assertThatThrownBy(() -> new AgentConfig.Camera("", "")).hasMessageContaining("empty");
    assertThat(AgentConfig.NONE.packs()).isEmpty();
    assertThat(AgentConfig.parse("{}", "x").port()).isEqualTo(5808);
  }

  @Test
  void aStampWhoseProbesDifferFromThisBuildsIsAWarning() {
    Computer computer = new Computer("vision-front", 11, List.of(), 5808);
    Table table = new Table(1234, 5808, List.of(computer));
    ProbeSet set =
        Packs.compile(AgentConfig.of(table, computer), List.of(Pack.parseYaml(PACK, "p")));
    CompiledTable compiled = new CompiledTable(table, "", Map.of(), Map.of("vision-front", set));
    Stamp stamp =
        new Stamp("vision-front", 1234, "10.12.34.11", "r", "", "", Map.of(), "", "", "")
            .withProbesHash("other");
    assertThat(compiled.compare(computer, stamp))
        .anyMatch(
            m ->
                m.field().equals("probesHash")
                    && m.severity() == CompiledTable.Severity.WARNING
                    && m.message().contains("the probe definitions' hash"));
    assertThat(compiled.compare(computer, stamp.withProbesHash(set.hash())))
        .noneMatch(m -> m.field().equals("probesHash"));
    assertThat(compiled.probeSet(computer)).isEqualTo(set);
    assertThat(CompiledTable.parse(Json.compact(compiled.toJson()))).isEqualTo(compiled);
    // A table compiled without probe definitions doesn't judge them.
    assertThat(new CompiledTable(table, "", Map.of(), Map.of()).compare(computer, stamp))
        .noneMatch(m -> m.field().equals("probesHash"));
  }

  private void write(String path, String text) throws IOException {
    Path file = dir.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }
}
