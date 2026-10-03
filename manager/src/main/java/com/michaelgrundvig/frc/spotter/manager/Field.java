package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A value's declaration, as the manager judges it: its id, label, type and unit, the limits that
 * apply (robot code's override, else its pack's), and the words each comparison is reported in,
 * made once per description so judging a value never builds text.
 */
final class Field {
  final String id;
  final String label;
  final Spotter.FieldType type;
  final String unit;
  final Spotter.Limit warn;
  final Spotter.Limit fail;
  final boolean overridden;
  final Words warnWords;
  final Words failWords;

  /** What each of a level's comparisons says when it matches: "below 15 %". */
  static final class Words {
    final String above;
    final String below;
    final String equals;
    final String notEquals;
    final @Nullable String equalsText;
    final @Nullable String notEqualsText;

    Words(Spotter.Limit limit, String unit) {
      above = limit.hasAbove() ? "above " + number(limit.getAbove(), unit) : "";
      below = limit.hasBelow() ? "below " + number(limit.getBelow(), unit) : "";
      equals = limit.hasEquals() ? "is " + scalar(limit.getEquals(), unit) : "";
      notEquals = limit.hasNotEquals() ? "isn't " + scalar(limit.getNotEquals(), unit) : "";
      equalsText =
          limit.hasEquals() && limit.getEquals().hasText() ? limit.getEquals().getText() : null;
      notEqualsText =
          limit.hasNotEquals() && limit.getNotEquals().hasText()
              ? limit.getNotEquals().getText()
              : null;
    }
  }

  /** What a missing rule says when the value is empty text. */
  static final String EMPTY = "is empty";

  Field(Spotter.FieldDeclaration declaration, @Nullable Limits override) {
    id = declaration.getId();
    label = declaration.getLabel().isEmpty() ? id : declaration.getLabel();
    type = declaration.getType();
    unit = declaration.getUnit();
    overridden = override != null;
    warn = override != null ? override.warn() : declaration.getWarn().clone();
    fail = override != null ? override.fail() : declaration.getFail().clone();
    warnWords = new Words(warn, unit);
    failWords = new Words(fail, unit);
  }

  /** A description's values, as the manager judges them, with robot code's overrides. */
  static Field[] of(Spotter.Description description, Map<String, Limits> overrides) {
    Field[] fields = new Field[description.getValues().length()];
    for (int i = 0; i < fields.length; i++) {
      Spotter.FieldDeclaration declaration = description.getValues().get(i);
      fields[i] = new Field(declaration, overrides.get(declaration.getId()));
    }
    return fields;
  }

  /** The limits that apply. */
  Limits limits() {
    return new Limits(warn, fail);
  }

  /** A number in words, with its unit: {@code 15 %}, {@code 61.2 °C}. */
  static String number(double value, String unit) {
    String number =
        value == Math.rint(value) && Math.abs(value) < 1e15
            ? Long.toString((long) value)
            : Double.toString(value);
    return unit.isEmpty() ? number : number + " " + unit;
  }

  private static String scalar(Spotter.Scalar scalar, String unit) {
    if (scalar.hasNumber()) {
      return number(scalar.getNumber(), unit);
    }
    if (scalar.hasFlag()) {
      return Boolean.toString(scalar.getFlag());
    }
    return scalar.getText();
  }
}
