package com.michaelgrundvig.frc.spotter.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.Json;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableTest {
  private static final Computer FRONT =
      new Computer("vision-front", 11, Board.ORANGEPI_5, List.of("front-left"), 5808);
  private static final Computer BACK =
      new Computer("vision-back", 12, Board.ORANGEPI_5B, List.of("back"), 5809);

  @Test
  void addressesAreBuiltFromTheTeamNumber() {
    assertThat(Table.ip(1234, 11)).isEqualTo("10.12.34.11");
    assertThat(Table.ip(123, 12)).isEqualTo("10.1.23.12");
    assertThat(Table.ip(0, 11)).isEqualTo("10.0.0.11");
    assertThat(Table.ip(12345, 13)).isEqualTo("10.123.45.13");
  }

  @Test
  void aTableFindsItsComputersByName() {
    Table table = new Table(1234, 5808, List.of(FRONT, BACK));
    assertThat(table.computer("vision-back")).contains(BACK);
    assertThat(table.computer("nope")).isEmpty();
  }

  @Test
  void aTableRoundTripsThroughJson() {
    Table table = new Table(1234, 5808, List.of(FRONT, BACK));
    assertThat(Table.fromJson(Json.parse(Json.compact(table.toJson())))).isEqualTo(table);
  }

  @Test
  void everyTableIsCheckedHoweverItsMade() {
    Computer twin = new Computer("vision-front", 11, Board.ORANGEPI_5, List.of("front-left"), 5808);
    assertThatThrownBy(() -> new Table(-1, 80, List.of(FRONT, twin)))
        .isInstanceOf(TableException.class)
        .hasMessageContaining("team -1 is out of range")
        .hasMessageContaining("agentPort 80 is out of range")
        .hasMessageContaining("two computers are named vision-front")
        .hasMessageContaining("two computers have address 11")
        .hasMessageContaining("cameras listed by two computers: front-left");
    assertThatThrownBy(() -> new Computer("x", 11, Board.ORANGEPI_5, List.of("a", "a"), 5810))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lists a camera twice")
        .hasMessageContaining("NetworkTables uses it");
    assertThat(Computer.cameraProblem(" padded")).contains("starts or ends with a space");
    assertThat(Computer.cameraProblem("x".repeat(65))).contains("longer than 64");
    assertThatThrownBy(
            () -> Computer.fromJson(Json.parse("{\"name\":\"a\",\"address\":11,\"board\":\"pi\"}")))
        .hasMessageContaining("unknown board \"pi\"");
  }

  @Test
  void aTableIsReadFromItsFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("coprocessors.yaml");
    Files.writeString(file, "team: 0\ncomputers: []\n");
    assertThat(Table.readYaml(file)).isEqualTo(new Table(0, 5808, List.of()));
  }

  @Test
  void boardsAreKnownByTheirNames() {
    assertThat(Board.byId("orangepi-5-plus")).contains(Board.ORANGEPI_5_PLUS);
    assertThat(Board.byId("orangepi-6")).isEmpty();
    assertThat(Board.ORANGEPI_5B).hasToString("orangepi-5b");
  }

  @Test
  void theCompiledTableRoundTripsAndSaysHowAStampDiffers() {
    CompiledTable compiled =
        new CompiledTable(
            new Table(1234, 5808, List.of(FRONT, BACK)),
            "abc",
            Map.of("visionVersion", "v2027.1.0"),
            Map.of());
    assertThat(CompiledTable.parse(Json.compact(compiled.toJson()))).isEqualTo(compiled);

    Stamp stamp =
        new Stamp(
            "vision-front",
            1234,
            "10.12.34.11",
            "r1",
            "abc",
            "",
            Map.of("visionVersion", "v2027.1.0", "board", "orangepi-5"),
            "",
            "",
            "");
    assertThat(compiled.mismatches(FRONT, stamp)).isEmpty();
    Stamp other =
        new Stamp(
            "vision-back",
            1234,
            "10.12.34.12",
            "r1",
            "abd",
            "",
            Map.of("visionVersion", "v2027.0.9"),
            "",
            "",
            "");
    assertThat(compiled.mismatches(FRONT, other))
        .containsExactly(
            "name is \"vision-back\" on the coprocessor and \"vision-front\" in this build",
            "address is \"10.12.34.12\" on the coprocessor and \"10.12.34.11\" in this build",
            "the image's visionVersion is \"v2027.0.9\" on the coprocessor and \"v2027.1.0\" in"
                + " this build",
            "recipe hash is \"abd\" on the coprocessor and \"abc\" in this build");
  }

  @Test
  void eachMismatchSaysHowMuchItMatters() {
    Table table = new Table(1234, 5808, List.of(FRONT));
    Stamp stamp =
        new Stamp(
            "vision-front",
            1234,
            "10.12.34.11",
            "r1",
            "old",
            "",
            Map.of("visionVersion", "v2027.0.9"),
            "",
            "",
            "");
    CompiledTable built =
        new CompiledTable(table, "new", Map.of("visionVersion", "v2027.1.0"), Map.of());
    assertThat(built.compare(FRONT, stamp))
        .extracting(CompiledTable.Mismatch::field, CompiledTable.Mismatch::severity)
        .containsExactly(
            tuple("labels.visionVersion", CompiledTable.Severity.ERROR),
            tuple("recipeHash", CompiledTable.Severity.WARNING));
    // A label the build expects and the image lacks is a mismatch too.
    assertThat(
            new CompiledTable(table, "old", Map.of("board", "orangepi-5"), Map.of())
                .mismatches(FRONT, stamp))
        .containsExactly(
            "the image's board is \"\" on the coprocessor and \"orangepi-5\" in this build");

    // No recipe hash (no image builder): the recipe can't be judged, which isn't a mismatch.
    CompiledTable noGit = new CompiledTable(table, "", Map.of(), Map.of());
    assertThat(noGit.compare(FRONT, stamp))
        .singleElement()
        .satisfies(
            mismatch -> {
              assertThat(mismatch.field()).isEqualTo("recipeHash");
              assertThat(mismatch.severity()).isEqualTo(CompiledTable.Severity.UNKNOWN);
              assertThat(mismatch.message()).contains("no recipe hash");
            });
    assertThat(noGit.mismatches(FRONT, stamp)).isEmpty();
  }

  @Test
  void theCompiledTableIsReadFromTheProgramOrSaysHowItsMade() throws IOException {
    String json = "{\"table\":{\"team\":9999,\"computers\":[]},\"recipeHash\":\"x\"}";
    assertThat(
            CompiledTable.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                .table()
                .team())
        .isEqualTo(9999);
    // The build puts it in the robot program; these tests have none.
    assertThatThrownBy(CompiledTable::load)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("coprocessorTable");
  }
}
