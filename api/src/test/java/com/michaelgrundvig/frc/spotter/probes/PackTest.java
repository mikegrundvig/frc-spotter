package com.michaelgrundvig.frc.spotter.probes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A pack read from its YAML, as the agent reads it, with every problem at its line. */
class PackTest {
  static final String PACK =
      """
      # What it checks, and where to copy it.
      pack: lidar
      journalUnits: [lidar.service]
      probes:
        - id: lidar.unit
          kind: unit
          unit: lidar.service
          every: 2
        - id: lidar.check
          kind: command
          argv: [python3, /opt/team/lidar-check.py, '{literal}']
          match: '^ok (\\d+)$'
          timeout: 5
          every: 10
          watch: [/data/lidar/settings.json]
        - id: lidar.settings
          kind: file
          path: /data/lidar/settings.json
          test: json
          field: scan.rate
      """;

  @Test
  void aPackIsItsProbesAndUnitsAsWritten() {
    Pack pack = Pack.parseYaml(PACK, "lidar.yaml");
    assertThat(pack.name()).isEqualTo("lidar");
    assertThat(pack.journalUnits()).containsExactly("lidar.service");
    assertThat(pack.probes())
        .extracting(Probe::id)
        .containsExactly("lidar.unit", "lidar.check", "lidar.settings");
    assertThat(pack.probes()).allMatch(probe -> probe.pack().equals("lidar"));
    Probe check = pack.probes().get(1);
    // Nothing in a pack is filled in: what it says is what runs.
    assertThat(((Check.Command) check.check()).argv())
        .containsExactly("python3", "/opt/team/lidar-check.py", "{literal}");
    assertThat(check.everySeconds()).isEqualTo(10);
    assertThat(check.timeoutSeconds()).isEqualTo(5);
    assertThat(check.watch()).containsExactly("/data/lidar/settings.json");
    assertThat(pack.probes().get(2).onDemand()).isTrue();
  }

  @Test
  void anEmptyPackIsAPack() {
    assertThat(Pack.parseYaml("pack: nothing\n", "n.yaml").probes()).isEmpty();
    assertThat(Pack.parseYaml("pack: nothing\nprobes:\njournalUnits:\n", "n.yaml").journalUnits())
        .isEmpty();
  }

  @Test
  void aPacksProblemsAreEachAtTheirLine() {
    String broken =
        """
        pack: Lidar
        extra: 1
        probes:
          - id: ok
            kind: unit
            unit: a.service
          - id: a
            kind: unit
            unit: a.service
            every: often
          - id: b
            kind: command
            argv: sh -c reboot
          - id: c
            each: camera
            kind: unit
            unit: c.service
          - id: d
            kind: telepathy
          - just text
          - id: ok
            kind: unit
            unit: a.service
        journalUnits: [x.service, x.service, nope]
        beforeShutdown: []
        """;
    assertThatThrownBy(() -> Pack.parseYaml(broken, "p.yaml"))
        .isInstanceOf(PackException.class)
        .satisfies(
            e ->
                assertThat(((PackException) e).problems())
                    .anyMatch(p -> p.startsWith("p.yaml:1: pack Lidar isn't a pack's name"))
                    .anyMatch(p -> p.startsWith("p.yaml:2: unknown key \"extra\" in a pack"))
                    .anyMatch(p -> p.startsWith("p.yaml:10: every must be a number"))
                    .anyMatch(p -> p.startsWith("p.yaml:13: argv must be a list"))
                    .anyMatch(p -> p.startsWith("p.yaml:15: unknown key \"each\" in a probe"))
                    .anyMatch(p -> p.startsWith("p.yaml:18:") && p.contains("\"telepathy\""))
                    .anyMatch(p -> p.startsWith("p.yaml:20: each probe is a mapping"))
                    .anyMatch(p -> p.startsWith("p.yaml:21: probe ok is defined twice"))
                    .anyMatch(p -> p.startsWith("p.yaml:24: journal unit x.service is listed"))
                    .anyMatch(p -> p.startsWith("p.yaml:24: journal unit \"nope\" isn't"))
                    .anyMatch(
                        p -> p.startsWith("p.yaml:25: unknown key \"beforeShutdown\" in a pack")));
    assertThatThrownBy(() -> Pack.parseYaml("probes: []", "q.yaml"))
        .hasMessageContaining("q.yaml:1: pack: (its name) is missing");
    assertThatThrownBy(() -> Pack.parseYaml("- a", "r.yaml"))
        .hasMessageContaining("expected pack:");
    assertThatThrownBy(() -> Pack.parseYaml("pack: x\nprobes: nothing\n", "s.yaml"))
        .hasMessageContaining("s.yaml:2: expected a list");
    assertThatThrownBy(() -> Pack.parseYaml("pack: x\nprobes:\n  - id: [a]\n", "t.yaml"))
        .hasMessageContaining("t.yaml:3: id must be a single value");
    assertThatThrownBy(
            () -> Pack.parseYaml("pack: x\nprobes:\n  - id: a\n    argv: [[b]]\n", "u.yaml"))
        .hasMessageContaining("lists inside [...]");
    assertThatThrownBy(
            () -> Pack.parseYaml("pack: x\nprobes:\n  - id: a\n    argv:\n      - [b]\n", "v.yaml"))
        .hasMessageContaining("v.yaml:5: argv's items are single values");
    assertThatThrownBy(() -> Pack.parseYaml("pack: x\njournalUnits: a.service\n", "w.yaml"))
        .hasMessageContaining("w.yaml:2: journalUnits must be a list");
  }

  @Test
  void aNumberIsWrittenPlainly() {
    for (String every : List.of("'2'", "02", "1e3", "two")) {
      assertThatThrownBy(
              () ->
                  Pack.parseYaml(
                      "pack: x\nprobes:\n  - id: a\n    kind: unit\n    unit: a.service\n"
                          + "    every: "
                          + every
                          + "\n",
                      "n.yaml"))
          .as(every)
          .hasMessageContaining("n.yaml:6: every must be a number");
    }
  }
}
