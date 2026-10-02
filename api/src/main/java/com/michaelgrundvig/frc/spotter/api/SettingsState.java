package com.michaelgrundvig.frc.spotter.api;

import com.michaelgrundvig.frc.spotter.json.JsonValue;

/**
 * PhotonVision's live settings against the ones stamped into the image. They differ while someone
 * tunes or calibrates, which is normal; committed to Git and reflashed, they match again.
 *
 * @param stampedHash the settings hash in the stamp: the settings the image was built with; empty
 *     when it was built with none
 * @param liveHash the hash of the settings PhotonVision has now; empty when they couldn't be read
 */
public record SettingsState(String stampedHash, String liveHash) {
  /** Nothing known. */
  public static final SettingsState UNKNOWN = new SettingsState("", "");

  /** Whether the live settings are the stamped ones. */
  public boolean matches() {
    return !liveHash.isEmpty() && liveHash.equals(stampedHash);
  }

  /** The state as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("stampedHash", stampedHash)
        .put("liveHash", liveHash)
        .put("matches", matches())
        .build();
  }

  /** A state from its JSON. */
  public static SettingsState fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("settings");
    return new SettingsState(o.string("stampedHash", ""), o.string("liveHash", ""));
  }
}
