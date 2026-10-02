package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A coprocessor's health: {@code GET /v1/health}. The computer's, whatever runs on it: how busy and
 * hot it is, its memory and disks, how it booted, its journal's trouble, its drive, and each
 * probe's latest result, which is where what runs on it is checked.
 *
 * @param stamp which image this is, and as which computer
 * @param boot this boot, and what booted
 * @param cpu how busy each core is, and how fast each cluster runs
 * @param thermal every thermal zone, with its trip points
 * @param journal this boot's trouble, counted
 * @param drive the NVMe drive's health; null when the image found no NVMe drive
 * @param problems what the agent couldn't read, each saying what and why; empty when it read
 *     everything
 * @param memory the computer's memory
 * @param disks the root's and {@code /data}'s space, for those mounted
 * @param probes each probe the agent runs, with its latest result, in its configuration's order
 */
public record Health(
    Stamp stamp,
    Boot boot,
    Cpu cpu,
    List<ThermalZone> thermal,
    JournalSummary journal,
    @Nullable Drive drive,
    List<String> problems,
    Memory memory,
    List<Disk> disks,
    List<ProbeResult> probes) {
  public Health {
    thermal = List.copyOf(thermal);
    problems = List.copyOf(problems);
    disks = List.copyOf(disks);
    probes = List.copyOf(probes);
  }

  /** A probe's latest result, if the image defines it. */
  public Optional<ProbeResult> probe(String id) {
    return probes.stream().filter(probe -> probe.id().equals(id)).findFirst();
  }

  /** The hottest zone, or none when there are no zones. */
  public Optional<ThermalZone> hottest() {
    return thermal.stream().max((a, b) -> Double.compare(a.celsius(), b.celsius()));
  }

  /** The health as JSON. */
  public JsonValue.Obj toJson() {
    Drive known = drive;
    return JsonValue.Obj.builder()
        .put("stamp", stamp.toJson())
        .put("boot", boot.toJson())
        .put("cpu", cpu.toJson())
        .put("thermal", JsonValue.array(thermal, ThermalZone::toJson))
        .put("journal", journal.toJson())
        .put("drive", known == null ? JsonValue.NULL : known.toJson())
        .put("problems", problems)
        .put("memory", memory.toJson())
        .put("disks", JsonValue.array(disks, Disk::toJson))
        .put("probes", JsonValue.array(probes, ProbeResult::toJson))
        .build();
  }

  /** Health from its JSON. */
  public static Health fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("health");
    JsonValue.Obj drive = o.object("drive");
    return new Health(
        Stamp.fromJson(o.objectOrEmpty("stamp")),
        Boot.fromJson(o.objectOrEmpty("boot")),
        Cpu.fromJson(o.objectOrEmpty("cpu")),
        o.list("thermal", ThermalZone::fromJson),
        JournalSummary.fromJson(o.objectOrEmpty("journal")),
        drive == null ? null : Drive.fromJson(drive),
        o.strings("problems"),
        Memory.fromJson(o.objectOrEmpty("memory")),
        o.list("disks", Disk::fromJson),
        o.list("probes", ProbeResult::fromJson));
  }

  /** Health from JSON text: {@code /v1/health}'s answer. */
  public static Health parse(String json) {
    return fromJson(Json.parse(json));
  }
}
