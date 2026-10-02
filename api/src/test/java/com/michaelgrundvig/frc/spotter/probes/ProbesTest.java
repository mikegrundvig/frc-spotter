package com.michaelgrundvig.frc.spotter.probes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.json.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The probe definitions: each kind's parameters checked as it's made, so nothing a pack loads reads
 * a path written other than plainly or reaches another computer; and the packs a computer runs,
 * combined into one bounded set.
 */
class ProbesTest {
  static final Probe UNIT =
      new Probe(
          "vision.unit", "vision", new Check.Unit("vision.service", "active"), 2, 2, List.of());

  static final Probe COMMAND =
      new Probe(
          "vision.version",
          "vision",
          new Check.Command(List.of("/opt/x/bin/helper", "version", "/opt/x.jar"), 0, "^(\\S+)$"),
          60,
          20,
          List.of("/opt/x.jar"));

  static final Probe STATUS =
      new Probe(
          "vision.status",
          "vision",
          new Check.Http("http://localhost:5800/api/status", 200, "", ""),
          0,
          2,
          List.of());

  static final Probe MEMORY =
      new Probe(
          "board.memory",
          "board",
          new Check.Threshold("memory.available.percent", 10, Double.NaN),
          5,
          2,
          List.of());

  @Test
  void packsCombineInTheOrderTheyreRead() {
    ProbeSet set =
        ProbeSet.EMPTY
            .with(new Pack("vision", List.of("vision.service"), List.of(UNIT, COMMAND, STATUS)))
            .with(new Pack("board", List.of("vision.service", "board.service"), List.of(MEMORY)));
    assertThat(set.packs()).containsExactly("vision", "board");
    assertThat(set.probes()).containsExactly(UNIT, COMMAND, STATUS, MEMORY);
    assertThat(set.journalUnits()).containsExactly("vision.service", "board.service");
    assertThat(set.probe("vision.status").orElseThrow().kind()).isEqualTo(ProbeKind.HTTP);
    assertThat(set.probe("vision.status").orElseThrow().onDemand()).isTrue();
    assertThat(set.probe("none")).isEmpty();
    assertThat(ProbeSet.EMPTY.probes()).isEmpty();
  }

  @Test
  void aPackWhoseNameOrProbesAreTakenIsRefused() {
    ProbeSet set = ProbeSet.EMPTY.with(new Pack("vision", List.of(), List.of(UNIT)));
    assertThatThrownBy(() -> set.with(new Pack("vision", List.of(), List.of())))
        .hasMessage("a pack named vision was read already");
    assertThatThrownBy(() -> set.with(new Pack("other", List.of(), List.of(UNIT))))
        .hasMessage("probe vision.unit is defined already, by pack vision");
  }

  @Test
  void aCommandRunsOneProgramDirectlyWithItsArgumentsFixed() {
    // Shells and interpreters are programs like any other: a pack's file is trusted by its owner.
    for (List<String> argv :
        List.of(
            List.of("python3", "/opt/team/check.py"),
            List.of("/bin/sh", "-c", "echo ok"),
            List.of("busybox", "true"))) {
      assertThat(new Check.Command(argv, 0, "").argv()).isEqualTo(argv);
    }
    assertThatThrownBy(() -> new Check.Command(List.of(), 0, "")).hasMessageContaining("is empty");
    assertThatThrownBy(() -> new Check.Command(List.of("bin/x"), 0, ""))
        .hasMessageContaining("neither an absolute path nor a plain name");
    assertThatThrownBy(() -> new Check.Command(List.of("/opt/../bin/x"), 0, ""))
        .hasMessageContaining("isn't written plainly");
    assertThatThrownBy(() -> new Check.Command(List.of("x", "a\nb"), 0, ""))
        .hasMessageContaining("control character");
    assertThatThrownBy(() -> new Check.Command(List.of("x"), 256, ""))
        .hasMessageContaining("isn't an exit status");
    assertThatThrownBy(() -> new Check.Command(List.of("x"), 0, "("))
        .hasMessageContaining("isn't a regular expression");
    assertThatThrownBy(() -> new Check.Command(List.of("x".repeat(2000)), 0, ""))
        .hasMessageContaining("longer than");
    assertThatThrownBy(() -> new Check.Command(java.util.Collections.nCopies(40, "x"), 0, ""))
        .hasMessageContaining("more than 32 arguments");
    assertThat(
            new Check.Command(List.of("systemctl", "is-active", "x"), Check.Command.ANY_EXIT, "")
                .exit())
        .isEqualTo(-1);
  }

  @Test
  void anHttpProbeStaysOnThisComputer() {
    for (String url :
        List.of(
            "http://10.12.34.2:5800/",
            "https://localhost/",
            "http://user@localhost/",
            "http://localhost.example.org/",
            "file:///etc/passwd",
            "not a url")) {
      assertThatThrownBy(() -> new Check.Http(url, 200, "", ""))
          .as(url)
          .isInstanceOf(IllegalArgumentException.class);
    }
    new Check.Http("http://127.0.0.1:5800/api/status", 200, "", "");
    new Check.Http("http://[::1]:5800/x", 204, "a.b", "c");
    assertThatThrownBy(() -> new Check.Http("http://localhost/", 99, "", ""))
        .hasMessageContaining("isn't an HTTP status");
    assertThatThrownBy(() -> new Check.Http("http://localhost/", 200, "", "x"))
        .hasMessageContaining("equals needs a field");
    assertThatThrownBy(() -> new Check.Http("http://localhost/", 200, "a..b", ""))
        .hasMessageContaining("names joined with dots");
  }

  @Test
  void aFileProbeReadsAnAbsolutePathAndSaysWhatOfIt() {
    assertThatThrownBy(
            () -> new Check.File("relative", Check.FileTest.EXISTS, -1, -1, "", "", "", ""))
        .hasMessageContaining("isn't an absolute path");
    assertThatThrownBy(
            () -> new Check.File("/a/../etc/shadow", Check.FileTest.EXISTS, -1, -1, "", "", "", ""))
        .hasMessageContaining("isn't written plainly");
    assertThatThrownBy(() -> new Check.File("/a", Check.FileTest.JSON, -1, -1, "", "", "", ""))
        .hasMessageContaining("needs a field");
    assertThatThrownBy(() -> new Check.File("/a", Check.FileTest.TEXT, -1, -1, "", "", "", ""))
        .hasMessageContaining("needs a match");
    assertThatThrownBy(() -> new Check.File("/a", Check.FileTest.SIZE, 10, 5, "", "", "", ""))
        .hasMessageContaining("least first");
    assertThatThrownBy(() -> new Check.File("/a", Check.FileTest.SHA256, -1, -1, "ABC", "", "", ""))
        .hasMessageContaining("64 lowercase hex");
    assertThatThrownBy(() -> Check.FileTest.byId("lines")).hasMessageContaining("isn't exists");
    assertThat(Check.FileTest.byId("sha256")).isEqualTo(Check.FileTest.SHA256);
  }

  @Test
  void unitsUsbAndThresholdsAreCheckedToo() {
    assertThatThrownBy(() -> new Check.Unit("vision", "active"))
        .hasMessageContaining("isn't a systemd unit");
    assertThatThrownBy(() -> new Check.Unit("x.service", "running"))
        .hasMessageContaining("isn't one of");
    assertThatThrownBy(() -> new Check.Usb("/dev/video0", 0)).hasMessageContaining("by-path");
    assertThatThrownBy(() -> new Check.Usb("/dev/v4l/by-path/x", -1))
        .hasMessageContaining("0 or more");
    assertThatThrownBy(() -> new Check.Threshold("cpu.temperature", 0, 1))
        .hasMessageContaining("isn't one of");
    assertThatThrownBy(() -> new Check.Threshold("cpu.total.percent", 5, 1))
        .hasMessageContaining("min is more than max");
    assertThat(Metrics.exists("thermal.margin.celsius")).isTrue();
  }

  @Test
  void aProbesOwnValuesAreChecked() {
    Check unit = UNIT.check();
    assertThatThrownBy(() -> new Probe("a/b", "x", unit, 1, 1, List.of()))
        .hasMessageContaining("without /");
    assertThatThrownBy(() -> new Probe(" a", "x", unit, 1, 1, List.of()))
        .hasMessageContaining("no space");
    assertThatThrownBy(() -> new Probe("a", "X", unit, 1, 1, List.of()))
        .hasMessageContaining("isn't a pack's name");
    assertThatThrownBy(() -> new Probe("a", "x", unit, 0.01, 1, List.of()))
        .hasMessageContaining("every must be");
    assertThatThrownBy(() -> new Probe("a", "x", unit, 1, 500, List.of()))
        .hasMessageContaining("timeout must be");
    assertThatThrownBy(() -> new Probe("a", "x", unit, 1, 1, List.of("x")))
        .hasMessageContaining("absolute path");
    assertThatThrownBy(
            () -> new Probe("a", "x", unit, 1, 1, java.util.Collections.nCopies(9, "/x")))
        .hasMessageContaining("more than 8 files");
    assertThat(new Probe("front left camera", "x", unit, 0, 1, List.of()).onDemand()).isTrue();
    assertThatThrownBy(
            () -> Probe.fromJson(Json.parse("{\"id\":\"a\",\"pack\":\"x\",\"kind\":\"shell\"}")))
        .hasMessageContaining(
            "kind \"shell\" isn't one of command, http, file, unit, usb, threshold");
  }

  @Test
  void aSetIsBoundedAndItsNamesUnique() {
    assertThatThrownBy(() -> new ProbeSet(List.of(), List.of(UNIT, UNIT), List.of()))
        .hasMessageContaining("two probes are named vision.unit");
    assertThatThrownBy(() -> new ProbeSet(List.of("a", "a"), List.of(), List.of()))
        .hasMessageContaining("two packs are named a");
    List<Probe> many =
        java.util.stream.IntStream.range(0, 65)
            .mapToObj(i -> new Probe("p" + i, "x", UNIT.check(), 1, 1, List.of()))
            .toList();
    assertThatThrownBy(() -> new ProbeSet(List.of(), many, List.of()))
        .hasMessageContaining("more than the 64");
    List<String> units =
        java.util.stream.IntStream.range(0, 17).mapToObj(i -> "u" + i + ".service").toList();
    assertThatThrownBy(() -> new ProbeSet(List.of(), List.of(), units))
        .hasMessageContaining("17 journal units, more than 16");
    assertThatThrownBy(() -> new ProbeSet(List.of(), List.of(), List.of("not a unit")))
        .hasMessageContaining("isn't a unit's name");
  }

  @Test
  void aMembersTextIsFoundByItsPath() {
    var json =
        Json.parse("{\"a\":{\"b\":\"x\",\"n\":1.5,\"t\":true,\"z\":null,\"o\":{\"k\":[1]}}}");
    assertThat(Check.member(json, "a.b")).isEqualTo("x");
    assertThat(Check.member(json, "a.n")).isEqualTo("1.5");
    assertThat(Check.member(json, "a.t")).isEqualTo("true");
    assertThat(Check.member(json, "a.z")).isEqualTo("null");
    assertThat(Check.member(json, "a.o")).isEqualTo("{\"k\":[1]}");
    assertThat(Check.member(json, "a.missing")).isEmpty();
    assertThat(Check.member(json, "a.b.c")).isEmpty();
  }
}
