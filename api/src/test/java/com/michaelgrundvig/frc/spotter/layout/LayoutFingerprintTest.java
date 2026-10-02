package com.michaelgrundvig.frc.spotter.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.layout.LayoutFingerprint.Tag;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The layout fingerprint: the same layout gives the same one however it's written, and a tag moved
 * by its resolution changes it.
 */
class LayoutFingerprintTest {
  /** Tag 1 facing the field, turned 180° about Z; tag 2 on the floor, facing up. */
  static final String LAYOUT =
      """
      {"tags": [
        {"ID": 2, "pose": {"translation": {"x": 4.5, "y": 1.25, "z": 0.0},
          "rotation": {"quaternion": {"W": 0.7071067811865476, "X": 0.0, "Y": -0.7071067811865475, "Z": 0.0}}}},
        {"ID": 1, "pose": {"translation": {"x": 16.697198, "y": 0.65532, "z": 1.4859},
          "rotation": {"quaternion": {"W": 6.123233995736766e-17, "X": 0.0, "Y": 0.0, "Z": 1.0}}}}
      ],
      "field": {"length": 16.54, "width": 8.07}}
      """;

  @Test
  void theSameLayoutGivesTheSameFingerprintHoweverItsWritten() {
    String fingerprint = LayoutFingerprint.ofJson(LAYOUT);
    assertThat(fingerprint).matches("[0-9a-f]{64}");
    // Fewer digits, the tags in another order, the quaternion negated (the same rotation), and
    // another field size: the same tags where they were.
    List<Tag> rewritten =
        List.of(
            new Tag(1, 16.69720, 0.65532, 1.48590, 0, 0, 0, -1),
            new Tag(2, 4.5, 1.25, 0, -0.70710678, 0, 0.70710678, 0));
    assertThat(LayoutFingerprint.of(rewritten)).isEqualTo(fingerprint);
  }

  @Test
  void aTagMovedByItsResolutionChangesIt() {
    String fingerprint = LayoutFingerprint.ofJson(LAYOUT);
    List<Tag> tags = LayoutFingerprint.tags(LAYOUT);
    Tag one = tags.get(1);
    Tag moved =
        new Tag(
            one.id(), one.x() + 0.0001, one.y(), one.z(), one.qw(), one.qx(), one.qy(), one.qz());
    assertThat(LayoutFingerprint.of(List.of(tags.get(0), moved))).isNotEqualTo(fingerprint);
    // Turned by 0.02° about Z.
    double half = Math.toRadians(180.02) / 2;
    Tag turned = new Tag(one.id(), one.x(), one.y(), one.z(), Math.cos(half), 0, 0, Math.sin(half));
    assertThat(LayoutFingerprint.of(List.of(tags.get(0), turned))).isNotEqualTo(fingerprint);
  }

  @Test
  void eachTagIsALineOfWholeNumbers() {
    List<Tag> tags = LayoutFingerprint.tags(LAYOUT);
    // 180° and -180° are one orientation, written 180.00.
    assertThat(LayoutFingerprint.line(tags.get(1))).isEqualTo("1 166972 6553 14859 0 0 18000");
    assertThat(LayoutFingerprint.line(new Tag(1, 16.697198, 0.65532, 1.4859, 0, 0, 0, -1)))
        .isEqualTo("1 166972 6553 14859 0 0 18000");
    assertThat(LayoutFingerprint.line(tags.get(0))).isEqualTo("2 45000 12500 0 0 -9000 0");
  }

  @Test
  void whatIsntALayoutIsRefused() {
    assertThatThrownBy(() -> LayoutFingerprint.ofJson("[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an AprilTag layout");
    assertThatThrownBy(() -> LayoutFingerprint.ofJson("{\"tags\": [{\"ID\": 1}]}"))
        .hasMessageContaining("without its ID, translation, or quaternion");
    assertThatThrownBy(
            () ->
                LayoutFingerprint.of(
                    List.of(new Tag(1, 0, 0, 0, 1, 0, 0, 0), new Tag(1, 1, 0, 0, 1, 0, 0, 0))))
        .hasMessageContaining("tag 1 twice");
    assertThatThrownBy(() -> LayoutFingerprint.of(List.of(new Tag(1, 0, 0, 0, 0, 0, 0, 0))))
        .hasMessageContaining("isn't a quaternion");
    assertThatThrownBy(
            () -> LayoutFingerprint.of(List.of(new Tag(1, Double.NaN, 0, 0, 1, 0, 0, 0))))
        .hasMessageContaining("isn't a number");
    assertThat(LayoutFingerprint.of(List.of()))
        .isEqualTo(LayoutFingerprint.ofJson("{\"tags\": []}"));
  }
}
