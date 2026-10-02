package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A file the agent serves, made by a program the image fixes: what the program prints is the file
 * ({@code GET /v1/downloads/<name>}), streamed and cut off at {@code maxBytes}. PhotonVision's pack
 * serves its settings backup this way.
 *
 * @param name its name, which is also the file's: letters, digits, {@code .}, {@code _}, {@code -}
 * @param argv the program and its arguments, run directly as the agent's user
 * @param contentType the file's media type, such as {@code application/zip}
 * @param maxBytes the most it may be, at most {@link #MAX_BYTES}
 * @param timeoutSeconds how long making it may take, at most {@link #MAX_TIMEOUT_SECONDS}
 */
public record Download(
    String name, List<String> argv, String contentType, long maxBytes, double timeoutSeconds) {
  /** The largest file served. */
  public static final long MAX_BYTES = 256L * 1024 * 1024;

  /** The longest making one may take. */
  public static final double MAX_TIMEOUT_SECONDS = 300;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Pattern TYPE = Pattern.compile("[a-z]+/[A-Za-z0-9.+-]+(; ?charset=utf-8)?");

  public Download {
    argv = List.copyOf(argv);
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "download \"" + name + "\": letters, digits, '.', '_', '-', at most 64");
    }
    Check.checkArgv(argv, "argv");
    if (!TYPE.matcher(contentType).matches()) {
      throw new IllegalArgumentException("contentType \"" + contentType + "\" isn't a media type");
    }
    if (maxBytes < 1 || maxBytes > MAX_BYTES) {
      throw new IllegalArgumentException("maxBytes must be 1 to " + MAX_BYTES);
    }
    if (!(timeoutSeconds >= 0.1 && timeoutSeconds <= MAX_TIMEOUT_SECONDS)) {
      throw new IllegalArgumentException(
          "a download's timeout must be 0.1 to " + (long) MAX_TIMEOUT_SECONDS + " seconds");
    }
  }

  /** The download as JSON. */
  public JsonValue.Obj toJson() {
    return JsonValue.Obj.builder()
        .put("name", name)
        .put("argv", argv)
        .put("contentType", contentType)
        .put("maxBytes", maxBytes)
        .put("timeout", timeoutSeconds)
        .build();
  }

  /** A download from its JSON, checked. */
  public static Download fromJson(JsonValue json) {
    JsonValue.Obj o = json.asObject("download");
    return new Download(
        o.string("name", ""),
        o.strings("argv"),
        o.string("contentType", "application/octet-stream"),
        o.integer("maxBytes", 16L * 1024 * 1024),
        o.number("timeout", 30));
  }
}
