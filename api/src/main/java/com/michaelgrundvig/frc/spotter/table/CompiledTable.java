package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The coprocessor table as the build compiles it into the robot program: the table, plus what the
 * build knows that the robot checks each coprocessor against (each computer's probes, and what an
 * image builder adds: the recipe hash it would build with, and labels every image must carry). The
 * build writes it from {@code coprocessors/coprocessors.yaml} into the jar as {@link #RESOURCE}.
 *
 * @param table the table
 * @param recipeHash the hash of the image recipe this build would build; empty when there's none
 *     (no image builder, or one that couldn't compute it)
 * @param labels labels each computer's stamp must carry with these values ({@link Stamp#labels}):
 *     an image builder's, such as the version of the software in the image the robot code was built
 *     against
 * @param probeSets each computer's probe definitions, by name, as its agent compiles them; none for
 *     a table compiled without them
 */
public record CompiledTable(
    Table table, String recipeHash, Map<String, String> labels, Map<String, ProbeSet> probeSets) {
  /** Where the build puts the compiled table in the robot program's jar. */
  public static final String RESOURCE = "/coprocessor/table.json";

  public CompiledTable {
    labels = Collections.unmodifiableMap(new TreeMap<>(labels));
    probeSets = Collections.unmodifiableMap(new LinkedHashMap<>(probeSets));
  }

  /** A computer's probe definitions, as its agent runs them; none when the build had none. */
  public ProbeSet probeSet(Computer computer) {
    return probeSets.getOrDefault(computer.name(), ProbeSet.EMPTY);
  }

  /** How much a difference between a stamp and this build matters. */
  public enum Severity {
    /**
     * The wrong computer or image: a different name, team, or address, or a label the build expects
     * with another value. A deploy fails on it, and the robot raises a high alert.
     */
    ERROR,
    /**
     * The image was built from a different recipe, or its agent runs other probes than this build
     * compiled: flash the current release when convenient. Worth a warning, not a failed deploy.
     */
    WARNING,
    /**
     * Not known: this build has no recipe hash (no image builder, or built without Git), so the
     * image's recipe can't be judged. Not a mismatch.
     */
    UNKNOWN
  }

  /**
   * One way a computer's stamp differs from this build, or couldn't be compared.
   *
   * @param field what differs: {@code name}, {@code team}, {@code address}, {@code labels.<label>},
   *     {@code probesHash}, or {@code recipeHash}
   * @param severity how much it matters
   * @param message a sentence naming both values (or why there's no comparing)
   */
  public record Mismatch(String field, Severity severity, String message) {}

  /**
   * How a computer's stamp compares with what this build expects of it: its name, team, address,
   * and each label this build expects (each an {@link Severity#ERROR} when it differs); its probe
   * definitions' hash, when this build compiled them (a {@link Severity#WARNING}: the agent checks
   * other things than this build expects); and its recipe hash (a {@link Severity#WARNING} when it
   * differs; {@link Severity#UNKNOWN} when this build has no recipe hash to compare). Empty when
   * they all match.
   */
  public List<Mismatch> compare(Computer computer, Stamp stamp) {
    List<Mismatch> mismatches = new ArrayList<>();
    compare(mismatches, "name", Severity.ERROR, computer.name(), stamp.name());
    compare(
        mismatches,
        "team",
        Severity.ERROR,
        Integer.toString(table.team()),
        Integer.toString(stamp.team()));
    compare(mismatches, "address", Severity.ERROR, table.ip(computer), stamp.address());
    labels.forEach(
        (label, value) ->
            compare(mismatches, "labels." + label, Severity.ERROR, value, stamp.label(label)));
    ProbeSet probes = probeSets.get(computer.name());
    if (probes != null) {
      compare(mismatches, "probesHash", Severity.WARNING, probes.hash(), stamp.probesHash());
    }
    if (recipeHash.isEmpty()) {
      mismatches.add(
          new Mismatch(
              "recipeHash",
              Severity.UNKNOWN,
              "the image's recipe can't be checked: this build has no recipe hash"));
    } else {
      compare(mismatches, "recipeHash", Severity.WARNING, recipeHash, stamp.recipeHash());
    }
    return mismatches;
  }

  /**
   * The messages of {@link #compare}'s errors and warnings: how a computer's stamp differs from
   * this build, each as a sentence naming both values. Empty when nothing differs (or only the
   * recipe is unknown).
   */
  public List<String> mismatches(Computer computer, Stamp stamp) {
    return compare(computer, stamp).stream()
        .filter(mismatch -> mismatch.severity() != Severity.UNKNOWN)
        .map(Mismatch::message)
        .toList();
  }

  private static void compare(
      List<Mismatch> mismatches, String field, Severity severity, String built, String found) {
    if (!built.equals(found)) {
      String what =
          field.startsWith("labels.")
              ? "the image's " + field.substring("labels.".length())
              : field.equals("recipeHash")
                  ? "recipe hash"
                  : field.equals("probesHash") ? "the probe definitions' hash" : field;
      mismatches.add(
          new Mismatch(
              field,
              severity,
              what
                  + " is \""
                  + found
                  + "\" on the coprocessor and \""
                  + built
                  + "\" in this build"));
    }
  }

  /** The compiled table as JSON. */
  public JsonValue.Obj toJson() {
    JsonValue.Obj.Builder labelsJson = JsonValue.Obj.builder();
    labels.forEach(labelsJson::put);
    JsonValue.Obj.Builder probes = JsonValue.Obj.builder();
    probeSets.forEach((name, set) -> probes.put(name, set.toJson()));
    return JsonValue.Obj.builder()
        .put("table", table.toJson())
        .put("recipeHash", recipeHash)
        .put("labels", labelsJson.build())
        .put("probeSets", probes.build())
        .build();
  }

  /** A compiled table from its JSON; the table is checked as any table is. */
  public static CompiledTable fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("compiled table");
    Map<String, String> labels = new TreeMap<>();
    JsonValue.Obj labelsJson = o.objectOrEmpty("labels");
    for (String label : labelsJson.members().keySet()) {
      labels.put(label, labelsJson.string(label, ""));
    }
    Map<String, ProbeSet> probes = new LinkedHashMap<>();
    JsonValue.Obj probesJson = o.objectOrEmpty("probeSets");
    probesJson.members().forEach((name, set) -> probes.put(name, ProbeSet.fromJson(set)));
    return new CompiledTable(
        Table.fromJson(o.objectOrEmpty("table")), o.string("recipeHash", ""), labels, probes);
  }

  /** A compiled table from JSON text. */
  public static CompiledTable parse(String json) {
    return fromJson(Json.parse(json));
  }

  /** The compiled table in the robot program's jar. */
  public static CompiledTable load() {
    try (InputStream in = CompiledTable.class.getResourceAsStream(RESOURCE)) {
      return read(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The compiled table from a resource's stream; null (no such resource) says how it's made. */
  static CompiledTable read(@Nullable InputStream in) throws IOException {
    if (in == null) {
      throw new IllegalStateException(
          RESOURCE
              + " isn't in the program: the build makes it from "
              + Table.PATH
              + " (the coprocessorTable task)");
    }
    return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
  }
}
