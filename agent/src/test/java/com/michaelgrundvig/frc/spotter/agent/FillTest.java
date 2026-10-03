package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.FieldType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How a command's output fills fields: JSON keys by name, otherwise one field. */
class FillTest {
  private static final Field NUMBER = Field.of("busiest", FieldType.FIELD_TYPE_NUMBER);
  private static final Field TEXT = Field.of("version", FieldType.FIELD_TYPE_TEXT);
  private static final Field FLAG = Field.of("capped", FieldType.FIELD_TYPE_BOOLEAN);
  private static final Field STATUS = Field.of("status", FieldType.FIELD_TYPE_STATUS);

  private static Commands.Result ran(int exit, String output, String errors) {
    return new Commands.Result(
        Commands.Kind.RUN,
        Spotter.Outcome.OUTCOME_COMPLETED,
        "",
        exit,
        output.getBytes(StandardCharsets.UTF_8),
        false,
        errors);
  }

  @Test
  void aJsonObjectFillsEachFieldFromTheKeyOfItsName() {
    List<Spotter.FieldValue> values =
        Fill.fields(
            List.of(NUMBER, FLAG, TEXT),
            "{\"busiest\": 87, \"capped\": false, \"version\": \"3.1\", \"other\": 1}");
    assertThat(values.get(0).getNumber()).isEqualTo(87);
    assertThat(values.get(1).hasFlag()).isTrue();
    assertThat(values.get(1).getFlag()).isFalse();
    assertThat(values.get(2).getText()).isEqualTo("3.1");
  }

  @Test
  void aKeyNotThereIsUnavailableAndSaysWhich() {
    List<Spotter.FieldValue> values = Fill.fields(List.of(NUMBER, TEXT), "{\"busiest\": 3}");
    assertThat(values.get(1).getUnavailable()).isEqualTo("its output has no \"version\"");
    // One field, a JSON object without its key: the object rule still holds.
    assertThat(Fill.fields(List.of(TEXT), "{\"status\": \"up\"}").get(0).getUnavailable())
        .isEqualTo("its output has no \"version\"");
    assertThat(Fill.fields(List.of(TEXT), "{\"version\": null}").get(0).getUnavailable())
        .isEqualTo("\"version\" is null");
  }

  @Test
  void otherOutputIsOneValueTrimmedAndConverted() {
    // /proc/uptime: its first number fills the one field.
    assertThat(Fill.fields(List.of(NUMBER), "4123.55 15991.04\n").get(0).getNumber())
        .isEqualTo(4123.55);
    assertThat(Fill.fields(List.of(TEXT), "  active\n").get(0).getText()).isEqualTo("active");
    assertThat(Fill.fields(List.of(TEXT), "").get(0).getText()).isEmpty();
    assertThat(Fill.fields(List.of(FLAG), "TRUE\n").get(0).getFlag()).isTrue();
    assertThat(Fill.fields(List.of(TEXT), "[1, 2]").get(0).getText()).isEqualTo("[1, 2]");
    assertThat(Fill.fields(List.of(NUMBER), "-1.5e3").get(0).getNumber()).isEqualTo(-1500);
  }

  @Test
  void whatDoesntConvertIsUnavailableWithWhy() {
    assertThat(Fill.fields(List.of(NUMBER), "fast").get(0).getUnavailable())
        .isEqualTo("not a number: \"fast\"");
    assertThat(Fill.fields(List.of(NUMBER), "").get(0).getUnavailable())
        .isEqualTo("not a number: \"\"");
    assertThat(Fill.fields(List.of(NUMBER), "1e999").get(0).hasUnavailable()).isTrue();
    assertThat(Fill.fields(List.of(NUMBER), "NaN").get(0).hasUnavailable()).isTrue();
    assertThat(Fill.fields(List.of(FLAG), "yes").get(0).getUnavailable())
        .isEqualTo("not true or false: \"yes\"");
    assertThat(Fill.fields(List.of(STATUS), "ok").get(0).getUnavailable())
        .startsWith("a status is a JSON object");
    assertThat(Fill.fields(List.of(NUMBER, TEXT), "87").get(0).getUnavailable())
        .isEqualTo("its output isn't a JSON object, which its 2 fields need: \"87\"");
    String longer = "x".repeat(100);
    assertThat(Fill.fields(List.of(NUMBER), longer).get(0).getUnavailable())
        .isEqualTo("not a number: \"" + "x".repeat(Fill.QUOTED) + "...\"");
  }

  @Test
  void jsonValuesConvertToTheFieldsType() {
    Field status = STATUS;
    Spotter.FieldValue failing =
        Fill.fromJson(
            status,
            JsonText.object("{\"s\": {\"level\": \"failing\", \"message\": \"2 since boot\"}}")
                .get("s"));
    assertThat(failing.getStatus().getLevel()).isEqualTo(Spotter.Level.LEVEL_FAILING);
    assertThat(failing.getStatus().getMessage()).isEqualTo("2 since boot");
    assertThat(Fill.fromJson(status, JsonText.object("{\"s\": {\"level\": \"OK\"}}").get("s")))
        .extracting(v -> v.getStatus().getLevel())
        .isEqualTo(Spotter.Level.LEVEL_OK);
    assertThat(Fill.fromJson(status, JsonText.object("{\"s\": {\"level\": \"bad\"}}").get("s")))
        .extracting(Spotter.FieldValue::getUnavailable)
        .asString()
        .startsWith("a status's level is ok, warning or failing");
    assertThat(Fill.fromJson(NUMBER, "12.5").getNumber()).isEqualTo(12.5);
    assertThat(Fill.fromJson(NUMBER, true).getUnavailable()).isEqualTo("not a number: \"true\"");
    assertThat(Fill.fromJson(FLAG, "false").getFlag()).isFalse();
    assertThat(Fill.fromJson(FLAG, 1L).getUnavailable()).isEqualTo("not true or false: \"1\"");
    assertThat(Fill.fromJson(TEXT, 87L).getText()).isEqualTo("87");
    assertThat(Fill.fromJson(TEXT, List.of(1L, 2L)).getText()).isEqualTo("[1,2]");
    Field json = Field.of("report", FieldType.FIELD_TYPE_JSON);
    assertThat(Fill.fromJson(json, JsonText.object("{\"cameras\": 2}")).getJson())
        .isEqualTo("{\"cameras\":2}");
    assertThat(Fill.fromText(json, "{\"a\": 1}").getJson()).isEqualTo("{\"a\": 1}");
  }

  private static Commands.Result answered(int status, String body) {
    return new Commands.Result(
        Commands.Kind.HTTP,
        Spotter.Outcome.OUTCOME_COMPLETED,
        "",
        status,
        body.getBytes(StandardCharsets.UTF_8),
        false,
        "");
  }

  private static final Field OUTCOME = Field.of("outcome", FieldType.FIELD_TYPE_TEXT);
  private static final Field WHY = Field.of("outcomeMessage", FieldType.FIELD_TYPE_TEXT);
  private static final Field EXIT = Field.of("exit", FieldType.FIELD_TYPE_NUMBER);
  private static final Field OUTPUT = Field.of("output", FieldType.FIELD_TYPE_TEXT);
  private static final Field CODE = Field.of("status", FieldType.FIELD_TYPE_NUMBER);
  private static final Field BODY = Field.of("body", FieldType.FIELD_TYPE_TEXT);

  @Test
  void theNamedPartsAreTheCommandsOwnAndTheRestComeFromItsOutput() {
    List<Spotter.FieldValue> values =
        Fill.fill(
            List.of(OUTCOME, CODE, TEXT, BODY, WHY),
            answered(503, "{\"version\": \"2027.1\", \"status\": \"ok\"}"),
            1024);
    assertThat(values.get(0).getText()).isEqualTo("completed");
    // An HTTP error is a status like any other, for the pack's limits to judge.
    assertThat(values.get(1).getNumber()).isEqualTo(503);
    assertThat(values.get(2).getText()).isEqualTo("2027.1");
    assertThat(values.get(3).getText()).isEqualTo("{\"version\": \"2027.1\", \"status\": \"ok\"}");
    assertThat(values.get(4).getText()).isEmpty();
    // A JSON key with a part's name never fills the part: status is the HTTP status above.
    assertThat(Fill.fill(List.of(CODE), answered(200, "{\"status\": 7}"), 1024).get(0).getNumber())
        .isEqualTo(200);
  }

  @Test
  void aRunsExitCodeIsItsAndItsOutputFillsTheOneOtherField() {
    // systemctl is-active says inactive with exit 3: what it printed is the value, the exit a part.
    List<Spotter.FieldValue> values =
        Fill.fill(List.of(TEXT, EXIT, OUTPUT), ran(3, "inactive\n", ""), 1024);
    assertThat(values.get(0).getText()).isEqualTo("inactive");
    assertThat(values.get(1).getNumber()).isEqualTo(3);
    assertThat(values.get(2).getText()).isEqualTo("inactive\n");
    // Nothing printed, whatever the exit: an empty text, or no number.
    assertThat(Fill.fill(List.of(TEXT), ran(2, "", "no such unit"), 1024).get(0).getText())
        .isEmpty();
    assertThat(Fill.fill(List.of(NUMBER), ran(2, "", ""), 1024).get(0).getUnavailable())
        .isEqualTo("not a number: \"\"");
    // Parts declared alone leave nothing for the output to fill.
    assertThat(Fill.fill(List.of(EXIT), ran(0, "a\nb", ""), 1024).get(0).getNumber()).isZero();
  }

  @Test
  void aCommandThatDidntCompleteFillsItsOutcomeAndNothingElse() {
    Commands.Result timedOut =
        Commands.Result.failed(
            Commands.Kind.RUN, Spotter.Outcome.OUTCOME_TIMED_OUT, "timed out after 5s");
    List<Spotter.FieldValue> values =
        Fill.fill(List.of(OUTCOME, WHY, EXIT, OUTPUT, TEXT), timedOut, 1024);
    assertThat(values.get(0).getText()).isEqualTo("timedOut");
    assertThat(values.get(1).getText()).isEqualTo("timed out after 5s");
    assertThat(values.subList(2, 5)).allMatch(v -> v.getUnavailable().equals("timed out after 5s"));
    Commands.Result refused =
        Commands.Result.failed(
            Commands.Kind.HTTP,
            Spotter.Outcome.OUTCOME_UNREACHABLE,
            "connection refused (localhost:5800)");
    assertThat(Fill.fill(List.of(OUTCOME, CODE), refused, 1024))
        .extracting(v -> v.hasText() ? v.getText() : v.getUnavailable())
        .containsExactly("unreachable", "connection refused (localhost:5800)");
    Commands.Result missing =
        Commands.Result.failed(
            Commands.Kind.READ, Spotter.Outcome.OUTCOME_COULD_NOT_START, "no such file: /x");
    assertThat(Fill.fill(List.of(OUTCOME, TEXT), missing, 1024))
        .extracting(v -> v.hasText() ? v.getText() : v.getUnavailable())
        .containsExactly("couldNotStart", "no such file: /x");
  }

  @Test
  void outputPastTheMostKeptFillsOnlyTheOutcome() {
    Commands.Result truncated =
        new Commands.Result(
            Commands.Kind.RUN, Spotter.Outcome.OUTCOME_COMPLETED, "", 0, new byte[2048], true, "");
    List<Spotter.FieldValue> values = Fill.fill(List.of(OUTCOME, EXIT, TEXT), truncated, 2048);
    assertThat(values.get(0).getText()).isEqualTo("completed");
    assertThat(values.get(1).getUnavailable()).isEqualTo("it printed more than the 2 KiB kept");
    assertThat(values.get(2).getUnavailable()).isEqualTo("it printed more than the 2 KiB kept");
  }

  @Test
  void eachKindHasItsParts() {
    assertThat(Fill.parts(Commands.Kind.RUN).keySet())
        .containsExactly("outcome", "outcomeMessage", "exit", "output");
    assertThat(Fill.parts(Commands.Kind.HTTP).keySet())
        .containsExactly("outcome", "outcomeMessage", "status", "body");
    assertThat(Fill.parts(Commands.Kind.READ).keySet())
        .containsExactly("outcome", "outcomeMessage");
    assertThat(Fill.kind(new Command.Read("/x"))).isEqualTo(Commands.Kind.READ);
    assertThat(Fill.name(Spotter.Outcome.OUTCOME_CANCELLED)).isEqualTo("cancelled");
    assertThat(Fill.name(Spotter.Outcome.OUTCOME_LOST)).isEqualTo("lost");
    assertThat(Fill.name(Spotter.Outcome.OUTCOME_UNSPECIFIED)).isEmpty();
    Field json = Field.of("output", FieldType.FIELD_TYPE_JSON);
    assertThat(Fill.fill(List.of(json), ran(0, "{\"a\": 1}", ""), 1024).get(0).getJson())
        .isEqualTo("{\"a\": 1}");
  }

  @Test
  void jsonTextReadsObjectsOnly() {
    assertThat(JsonText.asObject("  {\"a\": [1, {\"b\": true}]}\n")).isPresent();
    assertThat(JsonText.asObject("[1]")).isEmpty();
    assertThat(JsonText.asObject("{not json}")).isEmpty();
    assertThat(JsonText.asObject("{\"a\": 1")).isEmpty();
  }
}
