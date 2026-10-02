package com.michaelgrundvig.frc.spotter.probes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Mapping;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Node;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Scalar;
import com.michaelgrundvig.frc.spotter.probes.YamlSubset.Sequence;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** The YAML packs are written in: what it reads, and everything else refused at its line. */
class YamlSubsetTest {
  private static Node parse(String yaml) {
    return YamlSubset.parse(yaml, "p.yaml");
  }

  private static Node at(Node node, String key) {
    return Objects.requireNonNull(((Mapping) node).entries().get(key), key).value();
  }

  private static List<String> texts(Node node) {
    return ((Sequence) node).items().stream().map(item -> ((Scalar) item).text()).toList();
  }

  @Test
  void readsMappingsListsAndQuotes() {
    Node root =
        parse(
            """
            ---
            pack: 'vision'           # a comment
            journalUnits: [a.service, "b.service"]
            probes:
            - id: one
              argv: ["it's", 'say ''hi''', "\\u0041BC", no]
            -
              id: two
            """);
    assertThat(((Scalar) at(root, "pack")).text()).isEqualTo("vision");
    assertThat(((Scalar) at(root, "pack")).quoted()).isTrue();
    assertThat(texts(at(root, "journalUnits"))).containsExactly("a.service", "b.service");
    List<Node> probes = ((Sequence) at(root, "probes")).items();
    assertThat(texts(at(probes.get(0), "argv"))).containsExactly("it's", "say 'hi'", "ABC", "no");
    assertThat(((Scalar) at(probes.get(1), "id")).text()).isEqualTo("two");
  }

  @Test
  void anEmptyValueIsEmpty() {
    Scalar empty = (Scalar) at(parse("probes:\n"), "probes");
    assertThat(empty.empty()).isTrue();
  }

  @Test
  void yamlBeyondTheSubsetIsRefusedWithItsLine() {
    assertError("pack: x\nprobes: {a: 1}\n", "2: {...} mappings aren't supported");
    assertError("pack: &t 0\n", "1: anchors and aliases");
    assertError("pack: !!int 0\n", "1: tags (!) aren't supported");
    assertError("pack: |\n  0\n", "1: multi-line values");
    assertError("pack: @x\n", "1: a value can't start with @");
    assertError("pack: 0\n\tprobes: []\n", "2: indent with spaces, not tabs");
    assertError("pack: 0\npack: 1\n", "2: \"pack\" is given twice (first on line 1)");
    assertError("pack: 0\n  probes: []\n", "2: unexpected indentation");
    assertError("pack: 0\nprobes: [a, [b]]\n", "2: lists inside [...] aren't supported");
    assertError("pack: 0\nprobes: [a, b\n", "2: a [...] list must close on the same line");
    assertError("pack: 0\nprobes: [a, , b]\n", "2: an empty item in [...]");
    assertError("pack: \"0\" x\n", "1: unexpected text after the closing quote");
    assertError("pack: \"0\n", "1: a quoted value must close on the same line");
    assertError("pack: \"\\q\"\n", "1: unknown escape \\q");
    assertError("pack: \"\\u12\"\n", "1: a \\u escape needs four hex digits");
    assertError("pack: \"\\uzzzz\"\n", "1: a \\u escape needs four hex digits");
    assertError("? pack\n", "1: complex keys (?) aren't supported");
    assertError("just text\n", "1: expected a key and a colon");
    assertError("pack: 0\n---\n", "2: only one document is allowed");
    assertError("--- pack: 0\n", "1: start the file on the line after ---");
    assertError("pack: 0\n...\n", "2: remove this ...");
    assertError("# only a comment\n", "1: the file is empty");
    assertError("pack: 0\nprobes: [\"a\" b]\n", "2: expected a comma after the closing quote");
    assertError("pack: 0\nprobes: [a: b]\n", "2: unexpected \"a: b\" in [...]");
    assertError("pack: 0\n- a\n", "2: a list item where a key was expected");
    assertError("probes:\n- a\n  - b\n", "3: unexpected indentation");
  }

  @Test
  void flowItemsAreCheckedAsAnyValueIs() {
    for (String item :
        List.of("*cam", "&a b", "!t c", "%e", "@x", "`x", "|x", ">x", "- x", "? x")) {
      assertThatThrownBy(() -> parse("argv: [" + item + "]"))
          .as(item)
          .isInstanceOf(PackException.class)
          .hasMessageStartingWith("p.yaml:1: ");
    }
  }

  @Test
  void nestingIsLimited() {
    StringBuilder yaml = new StringBuilder("pack:\n");
    for (int i = 1; i <= 20; i++) {
      yaml.append("  ".repeat(i)).append("k").append(i).append(":\n");
    }
    assertThatThrownBy(() -> parse(yaml.toString()))
        .hasMessageContaining("nested deeper than 16 levels");
  }

  @Test
  void commentsQuotesAndLineEndingsAreReadAsYamlDoes() {
    Node root =
        parse(
            "\uFEFFpack: x # hash\r\n\"every\": 5\r\n"
                + "argv: [\"x # not a comment\", y#z, it's]\r\n");
    assertThat(((Scalar) at(root, "every")).text()).isEqualTo("5");
    assertThat(texts(at(root, "argv"))).containsExactly("x # not a comment", "y#z", "it's");
  }

  @Test
  void doubleQuotedValuesTakeYamlsEscapes() {
    Scalar value = (Scalar) at(parse("key: \"\\u00e9\\\"\\\\\\/\\n\\t\\ \"\n"), "key");
    assertThat(value.text()).isEqualTo("é\"\\/\n\t ");
    assertThat(value.quoted()).isTrue();
  }

  private static void assertError(String yaml, String message) {
    assertThatThrownBy(() -> parse(yaml))
        .isInstanceOf(PackException.class)
        .hasMessageStartingWith("p.yaml:" + message);
  }
}
