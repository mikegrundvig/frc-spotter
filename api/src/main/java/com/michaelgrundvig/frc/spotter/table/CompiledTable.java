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
import org.jspecify.annotations.Nullable;

/**
 * The coprocessor table as the build compiles it into the robot program: the table, plus what the
 * build knows that the robot checks each coprocessor against (the PhotonVision version, the image
 * recipe's hash, and each computer's committed settings hash). The build writes it from {@code
 * coprocessors/coprocessors.yaml} into the jar as {@link #RESOURCE}.
 *
 * @param table the table
 * @param photonvisionVersion the PhotonVision version PhotonLib was built for (the vendordep's)
 * @param recipeHash the hash of the image recipe this build would build
 * @param settingsHashes each computer's committed settings hash, by name; empty for a computer with
 *     none committed
 * @param probeSets each computer's probe definitions, by name, as its image carries them; none for
 *     a table compiled without them
 */
public record CompiledTable(
    Table table,
    String photonvisionVersion,
    String recipeHash,
    Map<String, String> settingsHashes,
    Map<String, ProbeSet> probeSets) {
  /** Where the build puts the compiled table in the robot program's jar. */
  public static final String RESOURCE = "/coprocessor/table.json";

  /** A table compiled without probe definitions. */
  public CompiledTable(
      Table table,
      String photonvisionVersion,
      String recipeHash,
      Map<String, String> settingsHashes) {
    this(table, photonvisionVersion, recipeHash, settingsHashes, Map.of());
  }

  public CompiledTable {
    settingsHashes = Collections.unmodifiableMap(new LinkedHashMap<>(settingsHashes));
    probeSets = Collections.unmodifiableMap(new LinkedHashMap<>(probeSets));
  }

  /** A computer's probe definitions, as its image carries them; none when the build had none. */
  public ProbeSet probeSet(Computer computer) {
    return probeSets.getOrDefault(computer.name(), ProbeSet.EMPTY);
  }

  /** A computer's committed settings hash; empty when none are committed. */
  public String settingsHash(Computer computer) {
    return settingsHashes.getOrDefault(computer.name(), "");
  }

  /** How much a difference between a stamp and this build matters. */
  public enum Severity {
    /**
     * The wrong computer or PhotonVision: a different name, team, address, or PhotonVision version.
     * A deploy fails on it, and the robot raises a high alert.
     */
    ERROR,
    /**
     * The image was built from a different recipe (the image's scripts, the agent, the lock): flash
     * the current release when convenient. Worth a warning, not a failed deploy.
     */
    WARNING,
    /**
     * Not known: this build has no recipe hash (built without Git, from a downloaded copy, or in a
     * folder of a larger repository), so the image's recipe can't be judged. Not a mismatch.
     */
    UNKNOWN
  }

  /**
   * One way a computer's stamp differs from this build, or couldn't be compared.
   *
   * @param field what differs: {@code name}, {@code team}, {@code address}, {@code
   *     photonvisionVersion}, {@code probesHash}, or {@code recipeHash}
   * @param severity how much it matters
   * @param message a sentence naming both values (or why there's no comparing)
   */
  public record Mismatch(String field, Severity severity, String message) {}

  /**
   * How a computer's stamp compares with what this build expects of it: its name, team, address,
   * and PhotonVision version (each an {@link Severity#ERROR} when it differs); its probe
   * definitions' hash, when this build compiled them (a {@link Severity#WARNING}: the image checks
   * other things than this build expects); and its recipe hash (a {@link Severity#WARNING} when it
   * differs; {@link Severity#UNKNOWN} when this build has no recipe hash to compare). Empty when
   * they all match. The settings hash is left out: settings change while someone calibrates, which
   * isn't a mismatch.
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
    compare(
        mismatches,
        "photonvisionVersion",
        Severity.ERROR,
        photonvisionVersion,
        stamp.photonvisionVersion());
    ProbeSet probes = probeSets.get(computer.name());
    if (probes != null) {
      compare(mismatches, "probesHash", Severity.WARNING, probes.hash(), stamp.probesHash());
    }
    if (recipeHash.isEmpty()) {
      mismatches.add(
          new Mismatch(
              "recipeHash",
              Severity.UNKNOWN,
              "the image's recipe can't be checked: this build has no recipe hash (it needs Git"
                  + " and the repository's own folder)"));
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
          field.equals("photonvisionVersion")
              ? "PhotonVision"
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
    JsonValue.Obj.Builder hashes = JsonValue.Obj.builder();
    settingsHashes.forEach(hashes::put);
    JsonValue.Obj.Builder probes = JsonValue.Obj.builder();
    probeSets.forEach((name, set) -> probes.put(name, set.toJson()));
    return JsonValue.Obj.builder()
        .put("table", table.toJson())
        .put("photonvisionVersion", photonvisionVersion)
        .put("recipeHash", recipeHash)
        .put("settingsHashes", hashes.build())
        .put("probeSets", probes.build())
        .build();
  }

  /** A compiled table from its JSON; the table is checked as any table is. */
  public static CompiledTable fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("compiled table");
    Map<String, String> hashes = new LinkedHashMap<>();
    JsonValue.Obj hashesJson = o.objectOrEmpty("settingsHashes");
    for (String name : hashesJson.members().keySet()) {
      hashes.put(name, hashesJson.string(name, ""));
    }
    Map<String, ProbeSet> probes = new LinkedHashMap<>();
    JsonValue.Obj probesJson = o.objectOrEmpty("probeSets");
    probesJson.members().forEach((name, set) -> probes.put(name, ProbeSet.fromJson(set)));
    return new CompiledTable(
        Table.fromJson(o.objectOrEmpty("table")),
        o.string("photonvisionVersion", ""),
        o.string("recipeHash", ""),
        hashes,
        probes);
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
