package com.michaelgrundvig.frc.spotter.table;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A pack: the probes, steps before a power-off, journal units, and downloads for one piece of
 * software on a coprocessor, as its {@code pack.yaml} writes them, with placeholders still in
 * ({@code {pack}}, {@code {camera}}, ...). A computer names the packs it runs in the table, and the
 * build compiles them, with the probes the table gives it, into its {@link
 * com.michaelgrundvig.frc.spotter.probes.ProbeSet} (docs/agent.md, "Packs").
 *
 * <p>A pack is a folder: {@code pack.yaml}, and any helper programs its definitions name under its
 * {@code bin/}, installed at {@code /usr/lib/frc-coprocessor/packs/<name>/}. Spotter's own packs
 * are in its {@code packs/}; a team's own in its repository's {@code coprocessors/packs/}.
 *
 * @param name its name: lowercase letters, digits, hyphens
 * @param probes its probes, as written
 * @param beforeShutdown its steps before a power-off, as written
 * @param journalUnits the units whose journal it serves
 * @param downloads its downloads, as written
 */
public record Pack(
    String name,
    List<Written> probes,
    List<Written> beforeShutdown,
    List<String> journalUnits,
    List<Written> downloads) {
  /** What a pack's name may be. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** The pack every computer runs: the agent's own measurements, held against limits. */
  public static final String BUILTIN = "builtin";

  /** Where Spotter keeps its own packs, from its repository's root. */
  public static final String TEMPLATE_PACKS = "packs";

  /** Where a team keeps its own packs, from the repository's root. */
  public static final String TEAM_PACKS = "coprocessors/packs";

  /** A pack's definitions, in its folder. */
  public static final String FILE = "pack.yaml";

  public Pack {
    probes = List.copyOf(probes);
    beforeShutdown = List.copyOf(beforeShutdown);
    journalUnits = List.copyOf(journalUnits);
    downloads = List.copyOf(downloads);
  }

  /**
   * A definition as a pack or the table writes it: its members as JSON, placeholders still in.
   *
   * @param json its members
   * @param source where it's written, with its line: {@code packs/x/pack.yaml:12}
   * @param eachCamera whether it's written once for each of the computer's cameras ({@code each:
   *     camera}), with {@code {camera}} in it
   */
  public record Written(JsonValue.Obj json, String source, boolean eachCamera) {
    /** The definition as JSON, as the compiled table carries the table's own probes. */
    public JsonValue.Obj toJson() {
      if (!eachCamera) {
        return json;
      }
      JsonValue.Obj.Builder out = JsonValue.Obj.builder();
      json.members().forEach(out::put);
      return out.put("each", "camera").build();
    }

    /** A definition from the compiled table's JSON. */
    public static Written fromJson(JsonValue value, String source) {
      JsonValue.Obj o = value.asObject("probe");
      JsonValue.Obj.Builder json = JsonValue.Obj.builder();
      o.members()
          .forEach(
              (name, member) -> {
                if (!name.equals("each")) {
                  json.put(name, member);
                }
              });
      return new Written(json.build(), source, o.string("each", "").equals("camera"));
    }

    /** Two definitions are the same when they say the same, wherever they're written. */
    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof Written written
          && written.json.equals(json)
          && written.eachCamera == eachCamera;
    }

    @Override
    public int hashCode() {
      return json.hashCode() * 31 + Boolean.hashCode(eachCamera);
    }
  }

  /** The pack as JSON: the {@code pack.json} its folder carries on a computer. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("pack", name)
        .put("probes", JsonValue.array(probes, Written::toJson))
        .put("beforeShutdown", JsonValue.array(beforeShutdown, Written::toJson))
        .put("journalUnits", journalUnits)
        .put("downloads", JsonValue.array(downloads, Written::toJson))
        .build();
  }

  /** A pack from its {@code pack.json}; {@code source} names it in messages. */
  public static Pack fromJson(JsonValue json, String source) {
    JsonValue.Obj o = json.asObject(source);
    String name = o.string("pack", "");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(source + ": pack \"" + name + "\" isn't a pack's name");
    }
    return new Pack(
        name,
        o.list("probes", item -> Written.fromJson(item, source)),
        o.list("beforeShutdown", item -> Written.fromJson(item, source)),
        o.strings("journalUnits"),
        o.list("downloads", item -> Written.fromJson(item, source)));
  }

  /**
   * The file a pack's folder carries on a computer: its definitions, read from YAML and checked.
   */
  public static final String JSON_FILE = "pack.json";

  /** Reads and checks a pack from its YAML; problems name the file and line. */
  public static Pack parseYaml(String yaml, String source) {
    return PackYaml.pack(yaml, source);
  }

  /**
   * The pack of this name in a repository: the team's ({@code coprocessors/packs/<name>/}) if it
   * has one, else Spotter's own ({@code packs/<name>/}).
   *
   * @throws TableException when there's no such pack, or it has a problem
   */
  public static Pack read(Path repository, String name) throws IOException {
    for (String folder : List.of(TEAM_PACKS, TEMPLATE_PACKS)) {
      Path file = repository.resolve(folder).resolve(name).resolve(FILE);
      if (Files.isRegularFile(file)) {
        Pack pack =
            parseYaml(
                Files.readString(file, StandardCharsets.UTF_8), folder + "/" + name + "/" + FILE);
        if (!pack.name().equals(name)) {
          throw new TableException(
              List.of(
                  folder
                      + "/"
                      + name
                      + "/"
                      + FILE
                      + ": its pack is \""
                      + pack.name()
                      + "\", not \""
                      + name
                      + "\""));
        }
        return pack;
      }
    }
    throw new TableException(
        List.of(
            "no pack named \""
                + name
                + "\": neither "
                + TEAM_PACKS
                + "/"
                + name
                + "/"
                + FILE
                + " nor "
                + TEMPLATE_PACKS
                + "/"
                + name
                + "/"
                + FILE));
  }
}
