package com.michaelgrundvig.frc.spotter.layout;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which AprilTag layout a coprocessor holds, as one short hash: the robot computes it from the
 * layout it uses, a coprocessor's pack from the layout its vision software stored, and the two are
 * compared. A layout pushed to a coprocessor that didn't take (or one left from another venue) then
 * shows as a mismatch, without sending the layout itself back and forth.
 *
 * <p>It's the SHA-256, in lowercase hex, of one line per tag, sorted by ID: the ID, the position in
 * tenths of a millimetre, and the rotation as roll, pitch, and yaw (WPILib's {@code Rotation3d}
 * angles about X, Y, and Z) in hundredths of a degree, each a whole number. The rounding is what
 * lets two writers agree: the same layout read from JSON and from WPILib's objects, or written with
 * fewer digits, differs in the last bits only. A tag moved by 0.1 mm or turned by 0.01° changes it,
 * far finer than any venue's survey. The field's size isn't part of it: it doesn't move a tag.
 */
public final class LayoutFingerprint {
  /** Tenths of a millimetre in a metre. */
  static final double POSITION_STEPS_PER_METRE = 10_000;

  /** Hundredths of a degree in a degree. */
  static final double ANGLE_STEPS_PER_DEGREE = 100;

  private LayoutFingerprint() {}

  /**
   * One tag's pose: its position in metres and its rotation as a quaternion, as WPILib's
   * AprilTagFieldLayout JSON writes them.
   */
  public record Tag(
      int id, double x, double y, double z, double qw, double qx, double qy, double qz) {}

  /** A layout's fingerprint; tags in any order. */
  public static String of(List<Tag> tags) {
    List<Tag> sorted = new ArrayList<>(tags);
    sorted.sort(Comparator.comparingInt(Tag::id));
    Set<Integer> ids = new HashSet<>();
    StringBuilder text = new StringBuilder();
    for (Tag tag : sorted) {
      if (!ids.add(tag.id())) {
        throw new IllegalArgumentException("the layout has tag " + tag.id() + " twice");
      }
      text.append(line(tag)).append('\n');
    }
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(sha256.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
  }

  /**
   * A layout's fingerprint from WPILib's AprilTagFieldLayout JSON: {@code {"tags": [{"ID": 1,
   * "pose": {"translation": {"x":, "y":, "z":}, "rotation": {"quaternion": {"W":, "X":, "Y":,
   * "Z":}}}}, ...], "field": {...}}}.
   *
   * @throws IllegalArgumentException when it isn't such a layout
   */
  public static String ofJson(String json) {
    return of(tags(json));
  }

  /** The tags of WPILib's AprilTagFieldLayout JSON. */
  public static List<Tag> tags(String json) {
    try {
      JsonValue.Obj layout = Json.parse(json).asObject("layout");
      List<Tag> tags = new ArrayList<>();
      for (JsonValue item : layout.items("tags")) {
        JsonValue.Obj tag = item.asObject("a tag");
        JsonValue.Obj pose = tag.objectOrEmpty("pose");
        JsonValue.Obj translation = pose.objectOrEmpty("translation");
        JsonValue.Obj quaternion = pose.objectOrEmpty("rotation").objectOrEmpty("quaternion");
        if (tag.get("ID") == null
            || pose.get("translation") == null
            || quaternion.get("W") == null) {
          throw new IllegalArgumentException("a tag without its ID, translation, or quaternion");
        }
        tags.add(
            new Tag(
                tag.integer("ID", 0),
                translation.number("x", 0),
                translation.number("y", 0),
                translation.number("z", 0),
                quaternion.number("W", 1),
                quaternion.number("X", 0),
                quaternion.number("Y", 0),
                quaternion.number("Z", 0)));
      }
      return tags;
    } catch (JsonException e) {
      throw new IllegalArgumentException("not an AprilTag layout: " + e.getMessage(), e);
    }
  }

  /** A tag's line: its ID, position, and rotation, each rounded to a whole number of steps. */
  static String line(Tag tag) {
    double norm =
        Math.sqrt(
            tag.qw() * tag.qw() + tag.qx() * tag.qx() + tag.qy() * tag.qy() + tag.qz() * tag.qz());
    if (!(norm > 0) || !Double.isFinite(norm)) {
      throw new IllegalArgumentException("tag " + tag.id() + "'s rotation isn't a quaternion");
    }
    double w = tag.qw() / norm;
    double x = tag.qx() / norm;
    double y = tag.qy() / norm;
    double z = tag.qz() / norm;
    return String.format(
        Locale.ROOT,
        "%d %d %d %d %d %d %d",
        tag.id(),
        steps(tag.x(), POSITION_STEPS_PER_METRE),
        steps(tag.y(), POSITION_STEPS_PER_METRE),
        steps(tag.z(), POSITION_STEPS_PER_METRE),
        angle(roll(w, x, y, z)),
        angle(pitch(w, x, y, z)),
        angle(yaw(w, x, y, z)));
  }

  private static long steps(double value, double perUnit) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("a tag's position isn't a number: " + value);
    }
    return Math.round(value * perUnit);
  }

  /** An angle in hundredths of a degree, with -180° written as 180°: they're one orientation. */
  private static long angle(double radians) {
    long steps = Math.round(Math.toDegrees(radians) * ANGLE_STEPS_PER_DEGREE);
    return steps == -180 * Math.round(ANGLE_STEPS_PER_DEGREE) ? -steps : steps;
  }

  // WPILib's Rotation3d.getX, getY, and getZ, for a unit quaternion.

  static double roll(double w, double x, double y, double z) {
    double cxcy = 1.0 - 2.0 * (x * x + y * y);
    double sxcy = 2.0 * (w * x + y * z);
    return cxcy * cxcy + sxcy * sxcy > 1e-20 ? Math.atan2(sxcy, cxcy) : 0.0;
  }

  static double pitch(double w, double x, double y, double z) {
    double ratio = 2.0 * (w * y - z * x);
    return Math.abs(ratio) >= 1.0 ? Math.copySign(Math.PI / 2.0, ratio) : Math.asin(ratio);
  }

  static double yaw(double w, double x, double y, double z) {
    double cycz = 1.0 - 2.0 * (y * y + z * z);
    double cysz = 2.0 * (w * z + x * y);
    return cycz * cycz + cysz * cysz > 1e-20
        ? Math.atan2(cysz, cycz)
        : Math.atan2(2.0 * w * z, w * w - z * z);
  }
}
