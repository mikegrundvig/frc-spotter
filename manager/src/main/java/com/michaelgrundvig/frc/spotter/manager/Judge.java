package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import org.jspecify.annotations.Nullable;

/**
 * The limits grammar, applied: within a level, any comparison that matches triggers it, and {@code
 * fail} is checked before {@code warn}. {@code missing} matches a value that's unavailable or is
 * empty text; a value with no value and no {@code missing} rule is unavailable, with no alert. A
 * status needs no limits: its level is the script's verdict. Judging builds no text: the words are
 * the field's, made with its description, or the value's own.
 */
final class Judge {
  private Judge() {}

  /** Sets a value's level and reason. */
  static void judge(Value value) {
    Field field = value.field;
    if (value.kind == Value.Kind.STATUS && value.status != Level.UNAVAILABLE) {
      value.level = value.status;
      value.reason = value.text;
      return;
    }
    String why = matched(field.fail, field.failWords, value);
    if (why != null) {
      value.level = Level.FAILING;
      value.reason = why;
      return;
    }
    why = matched(field.warn, field.warnWords, value);
    if (why != null) {
      value.level = Level.WARNING;
      value.reason = why;
      return;
    }
    if (!value.available() || value.kind == Value.Kind.STATUS) {
      value.level = Level.UNAVAILABLE;
      value.reason = value.text;
      return;
    }
    value.level = Level.OK;
    value.reason = "";
  }

  /** What a level's comparison that matches says, or null when none does. */
  private static @Nullable String matched(Spotter.Limit limit, Field.Words words, Value value) {
    switch (value.kind) {
      case NONE:
      case UNAVAILABLE:
        return limit.getMissing() ? value.text : null;
      case STATUS:
        // A status with no level: no verdict, so only a missing rule applies.
        return limit.getMissing() ? value.text : null;
      case NUMBER:
        if (limit.hasAbove() && value.number > limit.getAbove()) {
          return words.above;
        }
        if (limit.hasBelow() && value.number < limit.getBelow()) {
          return words.below;
        }
        if (limit.hasEquals() && limit.getEquals().hasNumber()) {
          if (value.number == limit.getEquals().getNumber()) {
            return words.equals;
          }
        }
        if (limit.hasNotEquals() && limit.getNotEquals().hasNumber()) {
          if (value.number != limit.getNotEquals().getNumber()) {
            return words.notEquals;
          }
        }
        return null;
      case FLAG:
        if (limit.hasEquals() && limit.getEquals().hasFlag()) {
          if (value.flag == limit.getEquals().getFlag()) {
            return words.equals;
          }
        }
        if (limit.hasNotEquals() && limit.getNotEquals().hasFlag()) {
          if (value.flag != limit.getNotEquals().getFlag()) {
            return words.notEquals;
          }
        }
        return null;
      case TEXT:
        if (limit.getMissing() && value.text.isEmpty()) {
          return Field.EMPTY;
        }
        if (words.equalsText != null && value.text.equals(words.equalsText)) {
          return words.equals;
        }
        if (words.notEqualsText != null && !value.text.equals(words.notEqualsText)) {
          return words.notEquals;
        }
        return null;
    }
    return null;
  }
}
