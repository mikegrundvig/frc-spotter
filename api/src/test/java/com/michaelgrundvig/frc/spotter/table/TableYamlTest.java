package com.michaelgrundvig.frc.spotter.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class TableYamlTest {
  private static Table parse(String yaml) {
    return Table.parseYaml(yaml, "coprocessors.yaml");
  }

  private static List<String> problems(String yaml) {
    try {
      parse(yaml);
    } catch (TableException e) {
      return e.problems();
    }
    throw new AssertionError("expected problems");
  }

  @Test
  void readsTheContractsExample() {
    Table table =
        parse(
            """
            team: 1234             # the team number; addresses are 10.TE.AM.x
            agentPort: 5808
            computers:
              - name: vision-front # hostname
                address: 11
                board: orangepi-5
                cameras: [front-left, front-right]
              - name: vision-back
                address: 12
                board: orangepi-5-plus
                agentPort: 5809
                cameras:
                  - back
            """);
    assertThat(table.team()).isEqualTo(1234);
    assertThat(table.agentPort()).isEqualTo(5808);
    assertThat(table.computers())
        .containsExactly(
            new Computer(
                "vision-front", 11, Board.ORANGEPI_5, List.of("front-left", "front-right"), 5808),
            new Computer("vision-back", 12, Board.ORANGEPI_5_PLUS, List.of("back"), 5809));
    assertThat(table.ip(table.computers().get(0))).isEqualTo("10.12.34.11");
  }

  @Test
  void readsTheCompactListStyleQuotesAndADocumentMarker() {
    Table table =
        parse(
            """
            ---
            team: 0
            computers:
            - name: 'vision'
              address: 11
              board: "orangepi-5b"
              cameras: ["it's", 'say ''hi''', "\\u0041BC", no]
            -
              name: other
              address: 12
              board: orangepi-5-max
              cameras:
            """);
    assertThat(table.agentPort()).isEqualTo(Table.DEFAULT_AGENT_PORT);
    assertThat(table.computers().get(0).cameras()).containsExactly("it's", "say 'hi'", "ABC", "no");
    assertThat(table.computers().get(1).cameras()).isEmpty();
    assertThat(table.computers().get(1).board()).isEqualTo(Board.ORANGEPI_5_MAX);
  }

  @Test
  void anEmptyListOfComputersIsATable() {
    assertThat(parse("team: 0\ncomputers: []\n").computers()).isEmpty();
    assertThat(parse("team: 0\ncomputers:\n").computers()).isEmpty();
  }

  @Test
  void everyProblemIsReportedWithItsLine() {
    assertThat(
            problems(
                """
                team: 99999
                adress: 3
                computers:
                  - name: Vision_Front
                    address: 300
                    board: raspberry-pi
                    cameras: [a/b]
                  - name: ok
                    address: "12"
                    board: orangepi-5
                    agentPort: 5800
                """))
        .containsExactly(
            "coprocessors.yaml:2: unknown key \"adress\" in the table; known: agentPort,"
                + " computers, team",
            "coprocessors.yaml:1: team 99999 is out of range: 0 to 25599",
            "coprocessors.yaml:4: name \"Vision_Front\" isn't a hostname: use lowercase letters,"
                + " digits, and hyphens, starting with a letter and not ending with a hyphen, at"
                + " most 63 characters",
            "coprocessors.yaml:5: address 300 is out of range: the last number of 10.TE.AM.x,"
                + " from 6 to 19, FRC's static range for devices on the robot (11 and up by"
                + " convention)",
            "coprocessors.yaml:6: unknown board \"raspberry-pi\"; known: orangepi-5, orangepi-5b,"
                + " orangepi-5-plus, orangepi-5-pro, orangepi-5-max",
            "coprocessors.yaml:7: camera \"a/b\" has a '/'; PhotonVision publishes each camera"
                + " under /photonvision/<camera>",
            "coprocessors.yaml:9: address must be a whole number, not \"12\"",
            "coprocessors.yaml:11: agentPort 5800 is taken: PhotonVision's page uses it");
  }

  @Test
  void namesAddressesAndCamerasAreUnique() {
    assertThat(
            problems(
                """
                team: 0
                computers:
                  - name: vision
                    address: 11
                    board: orangepi-5
                    cameras: [front]
                  - name: vision
                    address: 11
                    board: orangepi-5
                    cameras: [back]
                  - name: other
                    address: 13
                    board: orangepi-5
                    cameras: [back]
                """))
        .containsExactly(
            "coprocessors.yaml:7: name vision is taken (line 3)",
            "coprocessors.yaml:7: address 11 is taken (line 3)",
            "coprocessors.yaml:11: camera back is already listed by the computer on line 7;"
                + " PhotonVision camera names must be unique on the robot");
  }

  @Test
  void requiredValuesAreNamedWhenMissing() {
    assertThat(problems("agentPort: 70000\ncomputers:\n  - name: a\n"))
        .containsExactly(
            "coprocessors.yaml:1: team is missing",
            "coprocessors.yaml:1: agentPort 70000 is out of range: 1024 to 65535 (5801-5809 is"
                + " the team range)",
            "coprocessors.yaml:3: address is missing",
            "coprocessors.yaml:3: board is missing");
    assertThat(problems("team: 0\n"))
        .containsExactly("coprocessors.yaml:1: computers is missing (computers: [] for none)");
  }

  @Test
  void theWrongShapesAreNamed() {
    assertThat(problems("- a\n- b\n"))
        .containsExactly("coprocessors.yaml:1: expected team, agentPort, and computers at the top");
    assertThat(problems("team: 0\ncomputers: vision\n"))
        .containsExactly("coprocessors.yaml:2: computers must be a list (- name: ...)");
    assertThat(problems("team: 0\ncomputers:\n  - vision\n"))
        .containsExactly(
            "coprocessors.yaml:3: each computer is a mapping: - name: ..., address: ...");
    assertThat(
            problems(
                "team: 0\ncomputers:\n  - name: a\n    address: 11\n    board: orangepi-5\n"
                    + "    cameras: front\n"))
        .containsExactly("coprocessors.yaml:6: cameras must be a list: [front-left, front-right]");
    assertThat(
            problems(
                "team: 0\ncomputers:\n  - name: a\n    address: 11\n    board: orangepi-5\n"
                    + "    cameras:\n      - [x]\n      - \"\"\n"))
        .containsExactly(
            "coprocessors.yaml:7: each camera is a name",
            "coprocessors.yaml:8: a camera's name is empty");
    assertThat(
            problems(
                "team: 0\ncomputers:\n  - name: a\n    address: 11\n    board: orangepi-5\n"
                    + "    cameras: [front, front]\n"))
        .containsExactly("coprocessors.yaml:6: camera front is listed twice");
    assertThat(problems("team:\n  nested: 1\ncomputers: []\n"))
        .containsExactly("coprocessors.yaml:1: team must be a single value");
    assertThat(problems("team: two\ncomputers: []\n"))
        .containsExactly("coprocessors.yaml:1: team must be a whole number, not \"two\"");
  }

  @Test
  void yamlBeyondTheSubsetIsRefusedWithItsLine() {
    assertError("team: 0\ncomputers: {a: 1}\n", "2: {...} mappings aren't supported");
    assertError("team: &t 0\n", "1: anchors and aliases");
    assertError("team: !!int 0\n", "1: tags (!) aren't supported");
    assertError("team: |\n  0\n", "1: multi-line values");
    assertError("team: @x\n", "1: a value can't start with @");
    assertError("team: 0\n\tcomputers: []\n", "2: indent with spaces, not tabs");
    assertError("team: 0\nteam: 1\n", "2: \"team\" is given twice (first on line 1)");
    assertError("team: 0\n  computers: []\n", "2: unexpected indentation");
    assertError("team: 0\ncomputers: [a, [b]]\n", "2: lists inside [...] aren't supported");
    assertError("team: 0\ncomputers: [a, b\n", "2: a [...] list must close on the same line");
    assertError("team: 0\ncomputers: [a, , b]\n", "2: an empty item in [...]");
    assertError("team: \"0\" x\n", "1: unexpected text after the closing quote");
    assertError("team: \"0\n", "1: a quoted value must close on the same line");
    assertError("team: \"\\q\"\n", "1: unknown escape \\q");
    assertError("team: \"\\u12\"\n", "1: a \\u escape needs four hex digits");
    assertError("team: \"\\uzzzz\"\n", "1: a \\u escape needs four hex digits");
    assertError("team: \"\\u+123\"\n", "1: a \\u escape needs four hex digits");
    assertError("? team\n", "1: complex keys (?) aren't supported");
    assertError("just text\n", "1: expected a key and a colon");
    assertError("team: 0\n---\n", "2: only one document is allowed");
    assertError("--- team: 0\n", "1: start the table on the line after ---");
    assertError("team: 0\n...\n", "2: remove this ...");
    assertError("# only a comment\n", "1: the file is empty");
    assertError("team: 0\ncomputers: [\"a\" b]\n", "2: expected a comma after the closing quote");
    assertError("team: 0\ncomputers: [a: b]\n", "2: unexpected \"a: b\" in [...]");
    assertError("team: 0\n- a\n", "2: a list item where a key was expected");
    assertError("computers:\n- a\n  - b\n", "3: unexpected indentation");
  }

  @Test
  void valuesYamlReadsAsSomethingElseAreRefusedUnlessQuoted() {
    for (String camera :
        List.of("~", "null", "NULL", "true", "False", "1", "-2.5", "0x1f", ".inf")) {
      assertThat(problems(computerWith("cameras: [front, " + camera + "]")))
          .as(camera)
          .containsExactly(
              "coprocessors.yaml:6: camera "
                  + camera
                  + " isn't text to YAML (it's null, true or false, or a number): put it in"
                  + " quotes");
    }
    assertThat(parse(computerWith("cameras: [\"true\", '1']")).computers().get(0).cameras())
        .containsExactly("true", "1");
    assertThat(
            problems(
                "team: 0\ncomputers:\n  - name: null\n    address: 11\n    board: orangepi-5\n"))
        .containsExactly(
            "coprocessors.yaml:3: name null isn't text to YAML (it's null, true or false, or a"
                + " number): put it in quotes");
  }

  @Test
  void flowItemsAreCheckedAsAnyValueIs() {
    for (String camera :
        List.of("*cam", "&a b", "!t c", "%e", "@x", "`x", "|x", ">x", "- x", "? x")) {
      assertThatThrownBy(() -> parse(computerWith("cameras: [" + camera + "]")))
          .as(camera)
          .isInstanceOf(TableException.class)
          .hasMessageStartingWith("coprocessors.yaml:6: ");
    }
  }

  @Test
  void numbersHaveNoLeadingZerosAndCamerasArePrintableAscii() {
    assertThat(problems("team: 01234\ncomputers: []\n"))
        .containsExactly("coprocessors.yaml:1: team must be a whole number, not \"01234\"");
    assertThat(problems(computerWith("cameras: [\"caf\\u00e9\"]")))
        .containsExactly(
            "coprocessors.yaml:6: camera \"café\" has a character other than a letter, digit,"
                + " space, or punctuation (printable ASCII)");
    assertThat(problems("team: 0\nagentPort: 1183\ncomputers: []\n"))
        .containsExactly(
            "coprocessors.yaml:2: agentPort 1183 is taken: PhotonVision's camera streams use 1181"
                + " and up, two ports per camera");
  }

  @Test
  void nestingIsLimited() {
    StringBuilder yaml = new StringBuilder("team:\n");
    for (int i = 1; i <= 20; i++) {
      yaml.append("  ".repeat(i)).append("k").append(i).append(":\n");
    }
    assertThatThrownBy(() -> parse(yaml.toString()))
        .hasMessageContaining("nested deeper than 16 levels");
  }

  private static String computerWith(String line) {
    return "team: 0\ncomputers:\n  - name: a\n    address: 11\n    board: orangepi-5\n    "
        + line
        + "\n";
  }

  @Test
  void commentsQuotesAndLineEndingsAreReadAsYamlDoes() {
    Table table =
        parse(
            "\uFEFFteam: 0 # hash\r\n\"agentPort\": 5809\r\ncomputers:\r\n  - name: a\r\n"
                + "    address: 11\r\n    board: orangepi-5-pro\r\n"
                + "    cameras: [\"x # not a comment\", y#z, it's]\r\n");
    assertThat(table.agentPort()).isEqualTo(5809);
    assertThat(table.computers().get(0).cameras())
        .containsExactly("x # not a comment", "y#z", "it's");
  }

  @Test
  void doubleQuotedValuesTakeYamlsEscapes() {
    YamlSubset.Mapping mapping =
        (YamlSubset.Mapping)
            YamlSubset.parse("key: \"\\u00e9\\\"\\\\\\/\\n\\t\\ \"\n", "test.yaml");
    YamlSubset.Scalar value =
        (YamlSubset.Scalar) Objects.requireNonNull(mapping.entries().get("key")).value();
    assertThat(value.text()).isEqualTo("é\"\\/\n\t ");
    assertThat(value.quoted()).isTrue();
  }

  private static void assertError(String yaml, String message) {
    assertThatThrownBy(() -> parse(yaml))
        .isInstanceOf(TableException.class)
        .hasMessageStartingWith("coprocessors.yaml:" + message);
  }
}
