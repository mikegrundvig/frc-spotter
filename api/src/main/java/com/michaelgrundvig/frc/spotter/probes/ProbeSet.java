package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything one computer's agent runs beyond the agent itself: its probes, the steps to run before
 * it powers off, the journal units it serves besides its own and the kernel's, and the files it
 * serves. The agent compiles it when it starts, from its configuration and the packs installed
 * (coprocessor/README.md, "Packs"); the robot's build compiles the same from the coprocessor table,
 * with the same code; and the agent's stamp carries its {@link #hash}, so the robot knows exactly
 * what each computer checks.
 *
 * @param computer the computer it's for
 * @param packs the packs it was compiled from, in order: {@code builtin} first
 * @param probes every probe, in order
 * @param beforeShutdown the steps run before the computer powers off, in order
 * @param journalUnits the systemd units whose journal entries the agent serves besides the kernel's
 *     and its own
 * @param downloads the files it serves
 */
public record ProbeSet(
    String computer,
    List<String> packs,
    List<Probe> probes,
    List<Step> beforeShutdown,
    List<String> journalUnits,
    List<Download> downloads) {
  /** The definitions' format: a reader refuses one it doesn't know. */
  public static final int FORMAT = 1;

  /** The most probes a computer may define: each costs a line of every health answer. */
  public static final int MAX_PROBES = 64;

  /** The most steps before a power-off. */
  public static final int MAX_STEPS = 8;

  /** The most units served besides the agent's and the kernel's. */
  public static final int MAX_UNITS = 16;

  /** The most downloads. */
  public static final int MAX_DOWNLOADS = 8;

  private static final Pattern UNIT =
      Pattern.compile("[A-Za-z0-9:_.\\\\@-]{1,200}\\.(service|socket|timer|mount|scope)");

  /** No definitions: an image built before them, or a test's. */
  public static final ProbeSet EMPTY =
      new ProbeSet("", List.of(), List.of(), List.of(), List.of(), List.of());

  public ProbeSet {
    packs = List.copyOf(packs);
    probes = List.copyOf(probes);
    beforeShutdown = List.copyOf(beforeShutdown);
    journalUnits = List.copyOf(journalUnits);
    downloads = List.copyOf(downloads);
    if (probes.size() > MAX_PROBES) {
      throw new IllegalArgumentException(
          "computer " + computer + " has " + probes.size() + " probes, more than " + MAX_PROBES);
    }
    if (beforeShutdown.size() > MAX_STEPS) {
      throw new IllegalArgumentException("more than " + MAX_STEPS + " steps before a power-off");
    }
    if (journalUnits.size() > MAX_UNITS) {
      throw new IllegalArgumentException("more than " + MAX_UNITS + " journal units");
    }
    if (downloads.size() > MAX_DOWNLOADS) {
      throw new IllegalArgumentException("more than " + MAX_DOWNLOADS + " downloads");
    }
    unique(probes.stream().map(Probe::id).toList(), "probe");
    unique(beforeShutdown.stream().map(Step::name).toList(), "step");
    unique(downloads.stream().map(Download::name).toList(), "download");
    unique(journalUnits, "journal unit");
    for (String unit : journalUnits) {
      if (!UNIT.matcher(unit).matches()) {
        throw new IllegalArgumentException("journal unit \"" + unit + "\" isn't a unit's name");
      }
    }
  }

  private static void unique(List<String> names, String what) {
    Set<String> seen = new HashSet<>();
    for (String name : names) {
      if (!seen.add(name)) {
        throw new IllegalArgumentException("two of its " + what + "s are named " + name);
      }
    }
  }

  /** A probe by its id. */
  public Optional<Probe> probe(String id) {
    return probes.stream().filter(probe -> probe.id().equals(id)).findFirst();
  }

  /** A download by its name. */
  public Optional<Download> download(String name) {
    return downloads.stream().filter(download -> download.name().equals(name)).findFirst();
  }

  /** The definitions as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("format", FORMAT)
        .put("computer", computer)
        .put("packs", packs)
        .put("probes", JsonValue.array(probes, Probe::toJson))
        .put("beforeShutdown", JsonValue.array(beforeShutdown, Step::toJson))
        .put("journalUnits", journalUnits)
        .put("downloads", JsonValue.array(downloads, Download::toJson))
        .build();
  }

  /** The definitions as text: {@link Json#pretty}, which {@link #hash} is of. */
  public String text() {
    return Json.pretty(toJson());
  }

  /**
   * The SHA-256, in lowercase hex, of {@link #text}: written to a file, the file's own hash, so
   * {@code sha256sum} gives the same.
   */
  public String hash() {
    return sha256(text());
  }

  /** The SHA-256 of text, in lowercase hex. */
  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }

  /** Definitions from their JSON, checked; one of another format is refused. */
  public static ProbeSet fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("probe definitions");
    long format = o.integer("format", 0L);
    if (format != FORMAT) {
      throw new IllegalArgumentException(
          "probe definitions of format " + format + ": this agent reads format " + FORMAT);
    }
    return new ProbeSet(
        o.string("computer", ""),
        o.strings("packs"),
        o.list("probes", Probe::fromJson),
        o.list("beforeShutdown", Step::fromJson),
        o.strings("journalUnits"),
        o.list("downloads", Download::fromJson));
  }

  /** Definitions from JSON text. */
  public static ProbeSet parse(String json) {
    return fromJson(Json.parse(json));
  }
}
