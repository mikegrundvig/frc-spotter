package com.michaelgrundvig.frc.spotter.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonTest {
  @Test
  void aValueReadAndWrittenAgainIsUnchanged() {
    String text =
        "{\"b\":[1,2.50,-3e+2,0.1,1.0E-4],\"a\":{\"z\":null,\"y\":true,\"x\":false},\"c\":\"\"}";
    assertThat(Json.compact(Json.parse(text))).isEqualTo(text);
  }

  @Test
  void sortedOrdersMembersByName() {
    assertThat(Json.sorted(Json.parse("{\"b\":1,\"a\":{\"d\":2,\"c\":3}}")))
        .isEqualTo("{\"a\":{\"c\":3,\"d\":2},\"b\":1}");
  }

  @Test
  void namesSortByUtf16CodeUnitsAsRfc8785Does() {
    // U+00E9 (é) sorts after 'z'; uppercase before lowercase.
    assertThat(Json.sorted(Json.parse("{\"é\":1,\"z\":2,\"Z\":3}")))
        .isEqualTo("{\"Z\":3,\"z\":2,\"é\":1}");
  }

  @Test
  void hashableWritesEveryWayOfWritingANumberTheSame() {
    for (String one : List.of("1", "1.0", "1.000", "1e0", "10e-1", "0.1E1")) {
      assertThat(Json.hashable(Json.parse(one))).as(one).isEqualTo("1");
    }
    assertThat(Json.hashable(Json.parse("1.50"))).isEqualTo("15e-1");
    assertThat(Json.hashable(Json.parse("-0.0"))).isEqualTo("0");
    assertThat(Json.hashable(Json.parse("1E+3"))).isEqualTo("1000");
    assertThat(Json.hashable(Json.parse("1e400"))).isEqualTo("1e400");
    assertThat(Json.hashable(Json.parse("123456789012345678901")))
        .isEqualTo("123456789012345678901e0");
  }

  @Test
  void prettyIsSortedIndentedAndEndsInANewline() {
    JsonValue value =
        Json.parse(
            "{\"name\":\"front\",\"point\":{\"y\":2.0,\"x\":1.5},\"empty\":{},\"list\":[],"
                + "\"nested\":{\"inner\":{\"a\":[1,2]}},\"long\":["
                + "\"aaaaaaaaaaaaaaaaaaaa\",\"bbbbbbbbbbbbbbbbbbbb\",\"cccccccccccccccccccc\","
                + "\"dddddddddddddddddddd\"]}");
    assertThat(Json.pretty(value))
        .isEqualTo(
            """
            {
              "empty": {},
              "list": [],
              "long": [
                "aaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbb",
                "cccccccccccccccccccc",
                "dddddddddddddddddddd"
              ],
              "name": "front",
              "nested": {
                "inner": {
                  "a": [1, 2]
                }
              },
              "point": {"x": 1.5, "y": 2.0}
            }
            """);
    assertThat(Json.pretty(JsonValue.of("plain"))).isEqualTo("\"plain\"\n");
    assertThat(Json.parse(Json.pretty(value))).isEqualTo(value);
  }

  @Test
  void stringsEscapeOnlyWhatJsonRequires() {
    String text = "\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0001 é ✓ \\ud800\"";
    JsonValue value = Json.parse(text);
    assertThat(value.asString("s")).isEqualTo("q\" b\\ \b\f\n\r\t \u0001 é ✓ \ud800");
    assertThat(Json.compact(value)).isEqualTo(text);
    assertThat(Json.compact(Json.parse("\"\\/\\u00e9\""))).isEqualTo("\"/é\"");
    assertThat(Json.compact(JsonValue.of("\udc00x"))).isEqualTo("\"\\udc00x\"");
  }

  @Test
  void malformedTextSaysWhereAndWhat() {
    assertThatThrownBy(() -> Json.parse("{\"a\":1,\n \"b\" 2}"))
        .isInstanceOf(JsonException.class)
        .hasMessage("line 2, column 6: expected ':'");
    assertThatThrownBy(() -> Json.parse("{\"a\":1,\"a\":2}")).hasMessageContaining("duplicate");
    assertThatThrownBy(() -> Json.parse("[1 2]")).hasMessageContaining("expected ',' or ']'");
    assertThatThrownBy(() -> Json.parse("{\"a\":1 \"b\"}"))
        .hasMessageContaining("expected ',' or '}'");
    assertThatThrownBy(() -> Json.parse("01")).hasMessageContaining("not a number");
    assertThatThrownBy(() -> Json.parse("1 2")).hasMessageContaining("after the value");
    assertThatThrownBy(() -> Json.parse("")).hasMessageContaining("found the end");
    assertThatThrownBy(() -> Json.parse("\"abc")).hasMessageContaining("never ends");
    assertThatThrownBy(() -> Json.parse("\"a\\q\"")).hasMessageContaining("unknown escape");
    assertThatThrownBy(() -> Json.parse("\"\\u12\"")).hasMessageContaining("four hex");
    assertThatThrownBy(() -> Json.parse("\"a\nb\"")).hasMessageContaining("control character");
    assertThatThrownBy(() -> Json.parse("tru")).hasMessageContaining("expected true");
    assertThatThrownBy(() -> Json.parse("{1:2}")).hasMessageContaining("member's name");
    assertThatThrownBy(() -> Json.parse("@")).hasMessageContaining("unexpected character");
    assertThatThrownBy(() -> Json.parse("[".repeat(Json.MAX_DEPTH + 2)))
        .hasMessageContaining("nested deeper");
  }

  @Test
  void numbersAreBoundedSoNoneTakesLongToWork() {
    String longest = "1".repeat(Json.MAX_NUMBER);
    assertThat(Json.hashable(Json.parse(longest))).endsWith("e0");
    assertThat(Json.hashable(Json.parse("1e999999999"))).isEqualTo("1e999999999");
    assertThat(Json.hashable(Json.parse("-1." + "0".repeat(900) + "1e-999999999")))
        .isEqualTo("-10" + "0".repeat(899) + "1e-1000000900");

    long start = System.nanoTime();
    assertThatThrownBy(() -> Json.parse("[" + "1".repeat(1_000_000) + "]"))
        .isInstanceOf(JsonException.class)
        .hasMessage("line 1, column 2: a number longer than 1000 characters");
    assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    assertThatThrownBy(() -> Json.parse("1e2147483647"))
        .hasMessage("line 1, column 1: a number's exponent of more than 9 digits: 1e2147483647");
    assertThatThrownBy(() -> Json.parse("1E+0000000001")).hasMessageContaining("exponent");
    assertThatThrownBy(() -> new JsonValue.Num("1".repeat(1001))).hasMessageContaining("longer");
    assertThatThrownBy(() -> new JsonValue.Num("2e-123456789").longValue("n"))
        .hasMessageContaining("whole number");
  }

  @Test
  void hexEscapesAreExactlyHexDigits() {
    assertThat(Json.hexValue("00e9")).isEqualTo(0xe9);
    assertThat(Json.hexValue("FFFF")).isEqualTo(0xffff);
    assertThat(Json.hexValue("+123")).isEqualTo(-1);
    assertThat(Json.hexValue("-1")).isEqualTo(-1);
    assertThat(Json.hexValue("")).isEqualTo(-1);
    assertThat(Json.hexValue("\uff11\uff12")).isEqualTo(-1); // fullwidth digits
    assertThatThrownBy(() -> Json.parse("\"\\u+123\"")).hasMessageContaining("four hex digits");
  }

  @Test
  void numbersAreBuiltBriefly() {
    assertThat(JsonValue.of(45.0)).isEqualTo(new JsonValue.Num("45"));
    assertThat(JsonValue.of(0.1)).isEqualTo(new JsonValue.Num("0.1"));
    assertThat(JsonValue.of(1e20)).isEqualTo(new JsonValue.Num("1.0E20"));
    assertThat(JsonValue.of(Double.NaN)).isEqualTo(JsonValue.NULL);
    assertThat(JsonValue.of(Double.POSITIVE_INFINITY)).isEqualTo(JsonValue.NULL);
    assertThatThrownBy(() -> new JsonValue.Num("1.")).isInstanceOf(JsonException.class);
  }

  @Test
  void membersReadWithDefaultsWhenMissingAndErrorsWhenTheWrongKind() {
    JsonValue.Obj object =
        Json.parse(
                "{\"s\":\"x\",\"n\":2,\"d\":2.5,\"b\":true,\"z\":null,\"o\":{},\"a\":[\"p\"],"
                    + "\"big\":10000000000}")
            .asObject("test");
    assertThat(object.string("s", "")).isEqualTo("x");
    assertThat(object.string("missing", "fallback")).isEqualTo("fallback");
    assertThat(object.string("z", "fallback")).isEqualTo("fallback");
    assertThat(object.integer("n", 0)).isEqualTo(2);
    assertThat(object.integer("big", 0L)).isEqualTo(10_000_000_000L);
    assertThat(object.number("d", 0)).isEqualTo(2.5);
    assertThat(object.number("missing", 7)).isEqualTo(7);
    assertThat(object.bool("b", false)).isTrue();
    assertThat(object.bool("missing", true)).isTrue();
    assertThat(object.optionalBool("b")).isTrue();
    assertThat(object.optionalBool("z")).isNull();
    assertThat(object.object("o")).isEqualTo(JsonValue.Obj.EMPTY);
    assertThat(object.object("missing")).isNull();
    assertThat(object.objectOrEmpty("missing")).isEqualTo(JsonValue.Obj.EMPTY);
    assertThat(object.strings("a")).containsExactly("p");
    assertThat(object.items("missing")).isEmpty();
    assertThat(object.get("s")).isEqualTo(JsonValue.of("x"));

    assertThatThrownBy(() -> object.string("n", ""))
        .hasMessage("n: expected a string, found a number");
    assertThatThrownBy(() -> object.integer("d", 0)).hasMessageContaining("whole number");
    assertThatThrownBy(() -> object.integer("big", 0)).hasMessageContaining("out of range");
    assertThatThrownBy(() -> object.integer("s", 0L)).hasMessageContaining("expected a number");
    assertThatThrownBy(() -> object.number("s", 0)).hasMessageContaining("expected a number");
    assertThatThrownBy(() -> object.bool("s", false)).hasMessageContaining("true or false");
    assertThatThrownBy(() -> object.object("a")).hasMessageContaining("expected an object");
    assertThatThrownBy(() -> object.items("o")).hasMessageContaining("expected an array");
    assertThatThrownBy(() -> JsonValue.of("x").asDouble("v")).hasMessageContaining("number");
    assertThatThrownBy(() -> JsonValue.of("x").asLong("v")).hasMessageContaining("number");
    assertThat(JsonValue.of(3L).asLong("v")).isEqualTo(3);
    assertThat(JsonValue.TRUE.kind()).isEqualTo("true");
    assertThat(JsonValue.FALSE.kind()).isEqualTo("false");
    assertThat(JsonValue.NULL.kind()).isEqualTo("null");
  }

  @Test
  void aBuilderKeepsTheOrderMembersWereAddedIn() {
    JsonValue.Obj object =
        JsonValue.Obj.builder()
            .put("z", "last-name-first")
            .put("n", 1L)
            .put("d", 0.5)
            .put("b", true)
            .putOptional("u", null)
            .putOptional("k", false)
            .put("l", List.of("a", "b"))
            .build();
    assertThat(Json.compact(object))
        .isEqualTo(
            "{\"z\":\"last-name-first\",\"n\":1,\"d\":0.5,\"b\":true,\"u\":null,\"k\":false,"
                + "\"l\":[\"a\",\"b\"]}");
  }
}
