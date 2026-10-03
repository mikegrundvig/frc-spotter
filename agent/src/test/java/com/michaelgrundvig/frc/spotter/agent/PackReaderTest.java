package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** A pack's YAML: the spec's examples as they're written, and every mistake at its line. */
class PackReaderTest {
  private static final String PACKS = Packs.INSTALLED;

  /** One of the spec's example packs, from the test's pack folders. */
  static String example(String name) throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            PackReaderTest.class.getResourceAsStream("/packs/" + name + "/pack.yaml"), name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Pack read(String name, String yaml) {
    return PackReader.read(yaml, PACKS + "/" + name, false);
  }

  private static List<String> problems(String name, String yaml) {
    return catchThrowableOfType(PackException.class, () -> read(name, yaml)).problems();
  }

  @Test
  void debiansPackReadsAsTheSpecWritesIt() throws IOException {
    Pack debian = read("debian", example("debian"));
    assertThat(debian.name()).isEqualTo("debian");
    assertThat(debian.version()).isEqualTo("1.0.0");
    assertThat(debian.folder()).isEqualTo(PACKS + "/debian");
    assertThat(debian.collectors())
        .extracting(Pack.Collector::id)
        .containsExactly(
            "cpu", "thermal", "memory", "disk", "network", "usb", "clock", "drive", "uptime");
    Pack.Collector cpu = debian.collectors().get(0);
    assertThat(cpu.command()).isEqualTo(new Command.Run(List.of("./cpu")));
    assertThat(cpu.every()).isEqualTo(Duration.ofSeconds(1));
    assertThat(cpu.timeout()).isEqualTo(Pack.Collector.TIMEOUT);
    assertThat(cpu.fields()).extracting(Field::name).containsExactly("busiest", "capped");
    Field busiest = cpu.fields().get(0);
    assertThat(busiest.label()).isEqualTo("Busiest core");
    assertThat(busiest.type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_NUMBER);
    assertThat(busiest.unit()).isEqualTo("%");
    Field capped = cpu.fields().get(1);
    assertThat(capped.warn().getEquals().getFlag()).isTrue();
    assertThat(capped.warn().getEquals().hasFlag()).isTrue();
    Field margin = debian.collectors().get(1).fields().get(1);
    assertThat(margin.warn().getBelow()).isEqualTo(10);
    assertThat(margin.fail().getBelow()).isEqualTo(5);
    assertThat(margin.fail().hasAbove()).isFalse();
    assertThat(debian.collectors().get(4).command())
        .isEqualTo(new Command.Run(List.of("./network", "eth0")));
    assertThat(debian.collectors().get(7).command())
        .isEqualTo(new Command.Read("/run/frc-spotter-debian/drive.json"));
    assertThat(debian.collectors().get(7).every()).isEqualTo(Duration.ofSeconds(60));
    Pack.Log journal = debian.logs().get(0);
    assertThat(journal.id()).isEqualTo("journal");
    assertThat(journal.label()).isEqualTo("System log");
    assertThat(journal.map())
        .isEqualTo(
            new Pack.LogMap(
                "__REALTIME_TIMESTAMP",
                "us",
                "PRIORITY",
                "SYSLOG_IDENTIFIER",
                "MESSAGE",
                "__CURSOR"));
    assertThat(debian.actions()).isEmpty();
  }

  @Test
  void photonVisionsPackHasHttpCollectorsAndActions() throws IOException {
    Pack vision = read("photonvision", example("photonvision"));
    assertThat(vision.collectors().get(0).fields().get(0).fail().getNotEquals().getText())
        .isEqualTo("active");
    assertThat(vision.collectors().get(1).command())
        .isEqualTo(new Command.Http("GET", "http://localhost:5800/", ""));
    Field answers = vision.collectors().get(1).fields().get(0);
    assertThat(answers.name()).isEqualTo("status");
    assertThat(answers.type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_NUMBER);
    assertThat(answers.fail().getMissing()).isTrue();
    assertThat(answers.fail().getNotEquals().getNumber()).isEqualTo(200);
    Pack.Action restart = vision.actions().get(0);
    assertThat(restart.command())
        .isEqualTo(new Command.Http("POST", "http://localhost:5800/api/utils/restartProgram", ""));
    assertThat(restart.confirm()).startsWith("Restart PhotonVision?");
    assertThat(restart.timeout()).isEqualTo(Pack.Action.TIMEOUT);
    assertThat(restart.input()).isEqualTo(Spotter.Input.INPUT_NONE);
    assertThat(restart.whileEnabled()).isFalse();
    // The built-in fields first, the pack's status with its limit.
    assertThat(restart.response())
        .extracting(Field::name)
        .containsExactly("outcome", "outcomeMessage", "status", "body");
    assertThat(restart.response().get(2).fail().getNotEquals().getNumber()).isEqualTo(200);
    Pack.Action export = vision.actions().get(1);
    assertThat(export.timeout()).isEqualTo(Duration.ofSeconds(120));
    Field body = export.response().get(3);
    assertThat(body.type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_FILE);
    assertThat(body.fileName()).isEqualTo("photonvision-settings.zip");
    Pack.Action layout = vision.actions().get(2);
    assertThat(layout.command())
        .isEqualTo(
            new Command.Http("POST", "http://localhost:5800/api/settings/fieldLayout", "data"));
    assertThat(layout.input()).isEqualTo(Spotter.Input.INPUT_FILE);
  }

  @Test
  void aTeamsOwnPackHasRunActionsWithJsonResponses() throws IOException {
    Pack detector = read("detector", example("detector"));
    assertThat(detector.collectors().get(0).fields())
        .extracting(Field::type)
        .containsExactly(
            Spotter.FieldType.FIELD_TYPE_NUMBER,
            Spotter.FieldType.FIELD_TYPE_TEXT,
            Spotter.FieldType.FIELD_TYPE_STATUS);
    assertThat(detector.logs().get(0).map()).isEqualTo(Pack.LogMap.DEFAULT);
    Pack.Action recalibrate = detector.actions().get(0);
    assertThat(recalibrate.timeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(recalibrate.response())
        .extracting(Field::name)
        .containsExactly(
            "outcome", "outcomeMessage", "exit", "output", "reprojection-error", "report");
    assertThat(recalibrate.response().get(5).type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_JSON);
    assertThat(recalibrate.response().get(4).warn().getAbove()).isEqualTo(0.5);
    assertThat(detector.actions().get(1).input()).isEqualTo(Spotter.Input.INPUT_FILE);
  }

  @Test
  void theRaspberryPisPackHasAStatus() throws IOException {
    Pack pi = read("raspberry-pi", example("raspberry-pi"));
    assertThat(pi.collectors().get(0).command())
        .isEqualTo(new Command.Run(List.of("/usr/lib/frc-spotter-raspberry-pi/undervoltage")));
    assertThat(pi.collectors().get(0).every()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void aPacksNameIsItsFolders() {
    assertThat(problems("vision", "pack: detector\n"))
        .containsExactly(
            PACKS
                + "/vision/pack.yaml:1: pack detector is in a folder named vision: a pack's"
                + " folder is its name");
    assertThat(problems("core", "pack: core\n"))
        .containsExactly(
            PACKS
                + "/core/pack.yaml:1: pack core is the agent's own: its built-in actions are"
                + " core's");
    assertThat(problems("Vision", "pack: Vision\n")).hasSize(1);
    assertThat(problems("vision", "version: 1\n"))
        .containsExactly(PACKS + "/vision/pack.yaml:1: pack: (its name) is missing");
    assertThat(read("vision", "pack: vision\n").collectors()).isEmpty();
  }

  @Test
  void anUnknownKeyIsAProblemAtItsLine() {
    assertThat(
            problems(
                "vision",
                """
                pack: vision
                collectors:
                  - id: web
                    run: [curl, localhost]
                    evry: 2s
                    fields:
                      up: {type: boolean}
                """))
        .anyMatch(
            p -> p.startsWith(PACKS + "/vision/pack.yaml:5: unknown key \"evry\" in a collector"))
        .anyMatch(p -> p.contains("collector web needs every"));
    assertThat(problems("vision", "pack: vision\nprobes: []\n"))
        .containsExactly(
            PACKS
                + "/vision/pack.yaml:2: unknown key \"probes\" in a pack; known: actions,"
                + " collectors, logs, pack, version");
  }

  @Test
  void yamlItCantParseSaysWhere() {
    assertThat(problems("vision", "pack: vision\ncollectors: [\n").get(0))
        .isEqualTo(
            PACKS + "/vision/pack.yaml:3: expected the node content, but found '<stream end>'");
    assertThat(problems("vision", "pack: vision\npack: other\n"))
        .containsExactly(PACKS + "/vision/pack.yaml:2: found duplicate key pack");
    assertThat(problems("vision", "- a\n- b\n"))
        .containsExactly(PACKS + "/vision/pack.yaml: expected pack:, and its collectors:");
  }

  private static String collector(String body) {
    return "pack: p\ncollectors:\n  - id: c\n" + body.indent(4);
  }

  @Test
  void aCollectorNamesOneCommandAndHowOften() {
    assertThat(problems("p", collector("every: 1s\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.endsWith("a collector names one of run:, http: or file:"));
    assertThat(problems("p", collector("run: [a]\nfile: /x\nevery: 1s\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.endsWith(", not run and file"));
    assertThat(problems("p", collector("run: a\nevery: 1s\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.endsWith("run is a list: [program, its arguments...]"));
    assertThat(problems("p", collector("file: proc/uptime\nevery: 1s\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.endsWith("file is an absolute path, not proc/uptime"));
    assertThat(
            problems(
                "p",
                collector(
                    "http: {get: \"http://10.12.34.5:5800/\"}\nevery: 1s\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.contains("http asks the board itself only"));
    assertThat(
            problems(
                "p", collector("http: {get: x, post: y}\nevery: 1s\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.endsWith("http names one of get: or post:, with its URL"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {}")))
        .anyMatch(p -> p.endsWith("collector c needs at least one field"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s")))
        .anyMatch(p -> p.endsWith("collector c needs fields:, the values it fills"));
  }

  @Test
  void durationsHaveUnitsAndBounds() {
    Pack pack =
        read("p", collector("run: [a]\nevery: 250ms\ntimeout: 1.5s\nfields: {v: {type: text}}"));
    assertThat(pack.collectors().get(0).every()).isEqualTo(Duration.ofMillis(250));
    assertThat(pack.collectors().get(0).timeout()).isEqualTo(Duration.ofMillis(1500));
    assertThat(read("p", collector("run: [a]\nevery: 2m\nfields: {v: {type: text}}")).collectors())
        .first()
        .extracting(Pack.Collector::every)
        .isEqualTo(Duration.ofMinutes(2));
    assertThat(problems("p", collector("run: [a]\nevery: 2\nfields: {v: {type: text}}")))
        .anyMatch(
            p ->
                p.endsWith("a duration is a number and its unit (ms, s, m, h), such as 2s, not 2"));
    assertThat(problems("p", collector("run: [a]\nevery: 10ms\nfields: {v: {type: text}}")))
        .anyMatch(p -> p.endsWith("10ms is out of range: 100ms to 24h"));
    assertThat(PackReader.show(Duration.ofHours(1))).isEqualTo("1h");
    assertThat(PackReader.show(Duration.ofMillis(1500))).isEqualTo("1500ms");
  }

  @Test
  void valuesAreNumbersTextBooleansOrStatuses() {
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {v: {type: json}}")))
        .anyMatch(p -> p.endsWith("a value's type is boolean, number, status, text, not json"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {v: {label: V}}")))
        .anyMatch(p -> p.endsWith("field v needs a type: boolean, number, status, text"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {v: text}")))
        .anyMatch(p -> p.endsWith("field v is a mapping: {type: ..., label: ...}"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {\"v w\": {type: text}}")))
        .anyMatch(p -> p.contains("isn't a name"));
    // A field's name is its collector's: two collectors may each have a v.
    assertThat(
            read(
                    "p",
                    "pack: p\ncollectors:\n"
                        + "  - {id: a, run: [a], every: 1s, fields: {v: {type: text}}}\n"
                        + "  - {id: b, run: [b], every: 1s, fields: {v: {type: text}}}\n")
                .collectors())
        .hasSize(2);
    assertThat(
            problems(
                "p",
                "pack: p\ncollectors:\n"
                    + "  - {id: a, run: [a], every: 1s, fields: {v: {type: text}}}\n"
                    + "  - {id: a, run: [b], every: 1s, fields: {w: {type: text}}}\n"))
        .anyMatch(p -> p.endsWith("collector a is declared twice"));
  }

  @Test
  void limitsAreTheFixedGrammarAsTheFieldsTypeHasThem() {
    Pack pack =
        read(
            "p",
            collector(
                """
                run: [a]
                every: 1s
                fields:
                  n: {type: number, warn: {above: 3, notEquals: 7}, fail: {below: -1.5e2, missing: true}}
                  t: {type: text, fail: {equals: 200, notEquals: "x"}}
                  b: {type: boolean, warn: {equals: false}}
                  s: {type: status}
                """));
    List<Field> fields = pack.collectors().get(0).fields();
    assertThat(fields.get(0).warn().getAbove()).isEqualTo(3);
    assertThat(fields.get(0).warn().getNotEquals().getNumber()).isEqualTo(7);
    assertThat(fields.get(0).fail().getBelow()).isEqualTo(-150);
    assertThat(fields.get(0).fail().getMissing()).isTrue();
    // Text compares as text, as written: 200 is "200".
    assertThat(fields.get(1).fail().getEquals().getText()).isEqualTo("200");
    assertThat(fields.get(1).fail().getNotEquals().getText()).isEqualTo("x");
    assertThat(fields.get(2).warn().getEquals().hasFlag()).isTrue();
    assertThat(fields.get(2).warn().getEquals().getFlag()).isFalse();
    assertThat(fields.get(3).warn().isEmpty()).isTrue();

    assertThat(
            problems(
                "p", collector("run: [a]\nevery: 1s\nfields: {t: {type: text, warn: {above: 3}}}")))
        .anyMatch(p -> p.endsWith("above is for numbers"));
    assertThat(
            problems(
                "p",
                collector(
                    "run: [a]\nevery: 1s\nfields: {n: {type: number, warn: {equals: high}}}")))
        .anyMatch(p -> p.endsWith("equals is a number, not high"));
    assertThat(
            problems(
                "p",
                collector(
                    "run: [a]\nevery: 1s\nfields: {n: {type: number, warn: {above: \"3\"}}}")))
        .anyMatch(p -> p.endsWith("above is a number, not 3"));
    assertThat(
            problems(
                "p",
                collector(
                    "run: [a]\nevery: 1s\nfields: {b: {type: boolean, warn: {equals: yes}}}")))
        .anyMatch(p -> p.endsWith("equals is true or false"));
    assertThat(
            problems(
                "p",
                collector("run: [a]\nevery: 1s\nfields: {n: {type: number, warn: {over: 3}}}")))
        .anyMatch(p -> p.contains("unknown key \"over\" in a limit"));
    assertThat(
            problems("p", collector("run: [a]\nevery: 1s\nfields: {n: {type: number, warn: 3}}")))
        .anyMatch(p -> p.endsWith("a limit is a mapping: {below: 5, missing: true}"));
    assertThat(
            problems("p", collector("run: [a]\nevery: 1s\nfields: {n: {type: number, name: x}}")))
        .anyMatch(p -> p.endsWith("name is the file a file field downloads as"));
  }

  @Test
  void aCollectorsNamedPartsAreItsCommandsAndTyped() {
    Pack pack =
        read(
            "p",
            """
            pack: p
            collectors:
              - id: web
                http: {get: "http://localhost:5800/"}
                every: 1s
                fields:
                  outcome: {type: text}
                  outcomeMessage: {type: text}
                  status: {type: number, fail: {notEquals: 200}}
                  body: {type: text}
              - id: script
                run: [./check]
                every: 1s
                fields:
                  exit: {type: number}
                  output: {type: text}
                  # Not a run command's part: a JSON key.
                  body2: {type: text}
              - id: file
                file: /proc/uptime
                every: 1s
                fields:
                  # A file read's parts are only its outcome: exit is a JSON key here.
                  uptime: {type: number}
            """);
    assertThat(pack.collectors().get(0).fields())
        .extracting(Field::name)
        .containsExactly("outcome", "outcomeMessage", "status", "body");
    assertThat(
            problems(
                "p",
                collector(
                    "http: {get: \"http://localhost/\"}\nevery: 1s\nfields: {status: {type: text}}")))
        .anyMatch(p -> p.endsWith("status's type is number, not text"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {output: {type: json}}")))
        .anyMatch(p -> p.endsWith("output's type is text, not json"));
    assertThat(problems("p", collector("run: [a]\nevery: 1s\nfields: {outcome: {type: status}}")))
        .anyMatch(p -> p.endsWith("outcome's type is text, not status"));
    // A run command's exit is a number, but a file read has none: there, exit is any value's name.
    assertThat(
            read("p", collector("file: /x\nevery: 1s\nfields: {exit: {type: text}}")).collectors())
        .hasSize(1);
    // Each collector may declare its parts: their ids are pack.collector.part.
    assertThat(
            read(
                    "p",
                    "pack: p\ncollectors:\n"
                        + "  - {id: a, run: [a], every: 1s, fields: {exit: {type: number}}}\n"
                        + "  - {id: b, run: [b], every: 1s, fields: {exit: {type: number}}}\n")
                .collectors())
        .hasSize(2);
  }

  @Test
  void namesAreUniqueWhereTheyreDeclaredAndADuplicateIgnoresThePack() {
    // A field twice in one collector, or one response: snakeyaml-engine refuses the duplicate key.
    assertThat(
            problems(
                "p",
                """
                pack: p
                collectors:
                  - id: health
                    run: [./health]
                    every: 1s
                    fields:
                      fps: {type: number}
                      fps: {type: text}
                """))
        .containsExactly(PACKS + "/p/pack.yaml:8: found duplicate key fps");
    assertThat(
            problems(
                "p",
                "pack: p\nactions:\n  - id: go\n    run: [./go]\n    response:\n"
                    + "      answer: {type: number}\n      answer: {type: text}\n"))
        .containsExactly(PACKS + "/p/pack.yaml:7: found duplicate key answer");
    // Collectors', logs' and actions' ids, in the pack's lists, checked one by one.
    assertThat(
            problems(
                "p",
                "pack: p\nactions:\n  - {id: go, run: [a]}\n  - {id: stop, run: [b]}\n"
                    + "  - {id: go, run: [c]}\n"))
        .containsExactly(PACKS + "/p/pack.yaml:5: action go is declared twice");
    // The same name in different places is no clash: a collector, a log and an action.
    Pack pack =
        read(
            "p",
            "pack: p\ncollectors:\n  - {id: same, run: [a], every: 1s, fields: {same: {type: text}}}\n"
                + "logs:\n  - {id: same, run: [b]}\nactions:\n  - {id: same, run: [c]}\n");
    assertThat(pack.collectors().get(0).id()).isEqualTo("same");
    assertThat(pack.logs().get(0).id()).isEqualTo("same");
    assertThat(pack.actions().get(0).id()).isEqualTo("same");
  }

  @Test
  void aLogRunsACommandAndMapsItsKeys() {
    Pack pack =
        read(
            "p",
            """
            pack: p
            logs:
              - id: one
                run: [./log]
                map: {time: ts, level: severity}
            """);
    assertThat(pack.logs().get(0).map())
        .isEqualTo(new Pack.LogMap("ts", "", "severity", "source", "message", "cursor"));
    assertThat(problems("p", "pack: p\nlogs:\n  - {id: one, http: {get: \"http://localhost/\"}}\n"))
        .anyMatch(p -> p.contains("a log runs a command (run:)"))
        .anyMatch(p -> p.endsWith("log one needs run: the command that prints its entries"));
    assertThat(
            problems(
                "p",
                "pack: p\nlogs:\n  - {id: one, run: [a], map: {time: {from: t, unit: min}}}\n"))
        .anyMatch(p -> p.endsWith("a time's unit is ns, us, ms or s, not min"));
    assertThat(problems("p", "pack: p\nlogs:\n  - {id: one, run: [a]}\n  - {id: one, run: [b]}\n"))
        .anyMatch(p -> p.endsWith("log one is declared twice"));
  }

  @Test
  void anActionsResponseStartsWithItsKindsOwnFields() {
    Pack pack =
        read(
            "p",
            """
            pack: p
            actions:
              - id: run
                run: [./go]
                input: text
                whileEnabled: true
                timeout: 1h
                response:
                  output: {type: json}
                  answer: {type: number, fail: {below: 1}}
            """);
    Pack.Action run = pack.actions().get(0);
    assertThat(run.response())
        .extracting(Field::name)
        .containsExactly("outcome", "outcomeMessage", "exit", "output", "answer");
    assertThat(run.response().get(3).type()).isEqualTo(Spotter.FieldType.FIELD_TYPE_JSON);
    assertThat(run.input()).isEqualTo(Spotter.Input.INPUT_TEXT);
    assertThat(run.whileEnabled()).isTrue();
    assertThat(run.timeout()).isEqualTo(Duration.ofHours(1));

    String bad =
        """
        pack: p
        actions:
          - id: a
            http: {get: "http://localhost/", form: data}
            input: file
            timeout: 2h
            response:
              outcome: {type: text}
              status: {type: text}
              body: {type: status}
              report: {type: file}
          - id: b
            file: /x
          - id: c
            run: [x]
            input: picture
            whileEnabled: maybe
        """;
    assertThat(problems("p", bad))
        .anyMatch(p -> p.endsWith("a GET sends no form"))
        .anyMatch(p -> p.endsWith("action a sends a GET, which takes no input"))
        .anyMatch(p -> p.endsWith("2h is out of range: 1ms to 1h"))
        .anyMatch(p -> p.endsWith("outcome is every response's own, and can't be declared"))
        .anyMatch(p -> p.endsWith("status's type is number, not text"))
        .anyMatch(
            p -> p.endsWith("body's type, the whole output's is file, json, text, not status"))
        .anyMatch(
            p ->
                p.endsWith(
                    "a response field's type is boolean, json, number, status, text, not file"))
        .anyMatch(p -> p.endsWith("an action names one of run: or http:"))
        .anyMatch(p -> p.endsWith("input is none, text or file, not picture"))
        .anyMatch(p -> p.endsWith("whileEnabled is true or false"));
  }

  @Test
  void onlyTheBoardItselfIsAskedOverHttp() {
    assertThat(PackReader.local("http://localhost:5800/api")).isTrue();
    assertThat(PackReader.local("http://127.0.0.1/")).isTrue();
    assertThat(PackReader.local("http://[::1]:8080/")).isTrue();
    assertThat(PackReader.local("http://LOCALHOST/")).isTrue();
    assertThat(PackReader.local("https://localhost/")).isFalse();
    assertThat(PackReader.local("http://user@localhost/")).isFalse();
    assertThat(PackReader.local("http://localhost.example.com/")).isFalse();
    assertThat(PackReader.local("not a url")).isFalse();
  }

  @Test
  void nothingButAMappingIsAPack() {
    assertThatThrownBy(() -> read("p", "")).isInstanceOf(PackException.class);
    assertThat(problems("p", "pack: p\ncollectors: x\n"))
        .anyMatch(p -> p.endsWith("collectors is a list: - id: ..."));
    assertThat(problems("p", "pack: p\ncollectors:\n  - x\n"))
        .anyMatch(p -> p.endsWith("each collector is a mapping: - id: ..."));
    assertThat(read("p", "pack: p\ncollectors:\nlogs: ~\n").collectors()).isEmpty();
  }
}
