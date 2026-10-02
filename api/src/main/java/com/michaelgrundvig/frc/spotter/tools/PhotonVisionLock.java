package com.michaelgrundvig.frc.spotter.tools;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.table.Board;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code coprocessor/photonvision.lock}: the PhotonVision version the coprocessors run, which must
 * be PhotonLib's ({@code vendordeps/photonlib.json}), with what pins an image's inputs: the
 * PhotonVision jar's address and SHA-256, and each board's base image's. A value not known yet is a
 * placeholder, text starting {@value #PLACEHOLDER}: the robot's build allows it and names it; the
 * image build refuses it.
 *
 * <p>It's JSON (which YAML tools read too), at these paths: {@code .version}, {@code .jar.url},
 * {@code .jar.sha256}, {@code .images.<board>.url}, and {@code .images.<board>.sha256}.
 *
 * @param version the PhotonVision version, exactly as PhotonLib's vendordep has it
 * @param jar the PhotonVision jar
 * @param images each board's base image
 */
public record PhotonVisionLock(String version, Pinned jar, Map<Board, Pinned> images) {
  /** What a value that isn't known yet starts with. */
  public static final String PLACEHOLDER = "PLACEHOLDER";

  /** Where the lock is, from the repository's root. */
  public static final String PATH = "coprocessor/photonvision.lock";

  /** Where PhotonLib's vendordep is, from the repository's root. */
  public static final String VENDORDEP = "vendordeps/photonlib.json";

  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  /**
   * A file an image is built from.
   *
   * @param url where it's downloaded from, or a placeholder
   * @param sha256 its SHA-256, or a placeholder
   */
  public record Pinned(String url, String sha256) {}

  public PhotonVisionLock {
    images = Collections.unmodifiableMap(new LinkedHashMap<>(images));
  }

  /** Reads a lock, checking every address and checksum is one or a placeholder. */
  public static PhotonVisionLock parse(String text) {
    JsonValue.Obj lock;
    try {
      lock = Json.parse(text).asObject(PATH);
    } catch (JsonException e) {
      throw new IllegalArgumentException(PATH + ": " + e.getMessage(), e);
    }
    List<String> problems = new ArrayList<>();
    String version = lock.string("version", "");
    if (version.isEmpty()) {
      problems.add("version is missing");
    }
    Pinned jar = pinned(problems, "jar", lock.objectOrEmpty("jar"));
    Map<Board, Pinned> images = new LinkedHashMap<>();
    for (Map.Entry<String, JsonValue> member : lock.objectOrEmpty("images").members().entrySet()) {
      Board board = Board.byId(member.getKey()).orElse(null);
      if (board == null) {
        problems.add("images: unknown board " + member.getKey() + "; known: " + Board.ids());
        continue;
      }
      String what = "images." + board.id();
      images.put(board, pinned(problems, what, member.getValue().asObject(what)));
    }
    for (Board board : Board.values()) {
      if (!images.containsKey(board)) {
        problems.add("images has no " + board.id());
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(PATH + ": " + String.join("; ", problems));
    }
    return new PhotonVisionLock(version, jar, images);
  }

  private static Pinned pinned(List<String> problems, String what, JsonValue.Obj object) {
    String url = object.string("url", "");
    if (!url.startsWith("https://") && !url.startsWith(PLACEHOLDER)) {
      problems.add(what + ".url must be an https:// address or start with " + PLACEHOLDER);
    }
    String sha256 = object.string("sha256", "");
    if (!SHA256.matcher(sha256).matches() && !sha256.startsWith(PLACEHOLDER)) {
      problems.add(
          what
              + ".sha256 must be a SHA-256 (64 lowercase hex digits) or start with "
              + PLACEHOLDER);
    }
    return new Pinned(url, sha256);
  }

  /** The values still placeholders, by path: empty once everything is pinned. */
  public List<String> placeholders() {
    List<String> placeholders = new ArrayList<>();
    addPlaceholders(placeholders, "jar", jar);
    images.forEach((board, image) -> addPlaceholders(placeholders, "images." + board.id(), image));
    return placeholders;
  }

  private static void addPlaceholders(List<String> placeholders, String what, Pinned pinned) {
    if (pinned.url().startsWith(PLACEHOLDER)) {
      placeholders.add(what + ".url");
    }
    if (pinned.sha256().startsWith(PLACEHOLDER)) {
      placeholders.add(what + ".sha256");
    }
  }

  /** The version in PhotonLib's vendordep: the one the robot program is built against. */
  public static String vendordepVersion(String vendordep) {
    String version;
    try {
      version = Json.parse(vendordep).asObject(VENDORDEP).string("version", "");
    } catch (JsonException e) {
      throw new IllegalArgumentException(VENDORDEP + ": " + e.getMessage(), e);
    }
    if (version.isEmpty()) {
      throw new IllegalArgumentException(VENDORDEP + " has no version");
    }
    return version;
  }

  /**
   * What's wrong between the lock and PhotonLib, or nothing. The robot checks every coprocessor's
   * PhotonVision against PhotonLib's version, so the images must be built with it.
   */
  public List<String> checkAgainst(String vendordepVersion) {
    if (version.equals(vendordepVersion)) {
      return List.of();
    }
    return List.of(
        PATH
            + " locks PhotonVision "
            + version
            + ", but "
            + VENDORDEP
            + " is "
            + vendordepVersion
            + ". They must match: when PhotonLib changes, set the lock's version to the"
            + " vendordep's, with the new jar's and base images' addresses and checksums (or"
            + " placeholders until they're known), and build new coprocessor images.");
  }
}
