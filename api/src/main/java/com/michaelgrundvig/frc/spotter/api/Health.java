package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A coprocessor's health: {@code GET /v1/health}. What PhotonVision can't see, including its own
 * failure; what PhotonVision already publishes (one temperature, total CPU, memory, disk) it
 * doesn't repeat. About 2 KB.
 *
 * @param stamp which image this is, and as which computer
 * @param boot this boot, and what booted
 * @param cpu how busy each core is, and how fast each cluster runs
 * @param thermal every thermal zone, with its trip points
 * @param photonvision PhotonVision's service, as systemd sees it
 * @param cameras the cameras it should run against those plugged in
 * @param journal this boot's trouble, counted
 * @param drive the NVMe drive's health; null when the image found no NVMe drive (or isn't ours)
 * @param settings PhotonVision's live settings against the stamped ones
 * @param problems what the agent couldn't read, each saying what and why; empty when it read
 *     everything
 */
public record Health(
    Stamp stamp,
    Boot boot,
    Cpu cpu,
    List<ThermalZone> thermal,
    Service photonvision,
    Cameras cameras,
    JournalSummary journal,
    @Nullable Drive drive,
    SettingsState settings,
    List<String> problems) {
  public Health {
    thermal = List.copyOf(thermal);
    problems = List.copyOf(problems);
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
        .put("photonvision", photonvision.toJson())
        .put("cameras", cameras.toJson())
        .put("journal", journal.toJson())
        .put("drive", known == null ? JsonValue.NULL : known.toJson())
        .put("settings", settings.toJson())
        .put("problems", problems)
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
        Service.fromJson(o.objectOrEmpty("photonvision")),
        Cameras.fromJson(o.objectOrEmpty("cameras")),
        JournalSummary.fromJson(o.objectOrEmpty("journal")),
        drive == null ? null : Drive.fromJson(drive),
        SettingsState.fromJson(o.objectOrEmpty("settings")),
        o.strings("problems"));
  }

  /** Health from JSON text: {@code /v1/health}'s answer. */
  public static Health parse(String json) {
    return fromJson(Json.parse(json));
  }
}
