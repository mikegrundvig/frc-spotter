package com.michaelgrundvig.frc.spotter.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The limits grammar: {@code above} and {@code below} for numbers, {@code equals} and {@code
 * notEquals} for any type, {@code missing} for a value that's unavailable or empty text; within a
 * level any comparison that matches triggers it, {@code fail} before {@code warn}; a status is its
 * own verdict; and a value with none and no missing rule is unavailable, with no alert.
 */
class JudgeTest {
  static Spotter.Limit limit() {
    return Spotter.Limit.newInstance();
  }

  static Value value(
      Spotter.FieldType type, Spotter.Limit warn, Spotter.Limit fail, @Nullable Limits override) {
    Spotter.FieldDeclaration declaration =
        Spotter.FieldDeclaration.newInstance()
            .setId("p.c.f")
            .setLabel("Thing")
            .setType(type)
            .setUnit(type == Spotter.FieldType.FIELD_TYPE_NUMBER ? "°C" : "")
            .setWarn(warn)
            .setFail(fail);
    return new Value(new Field(declaration, override));
  }

  static Value number(double n, Spotter.Limit warn, Spotter.Limit fail) {
    Value value = value(Spotter.FieldType.FIELD_TYPE_NUMBER, warn, fail, null);
    value.kind = Value.Kind.NUMBER;
    value.number = n;
    Judge.judge(value);
    return value;
  }

  static Value text(String text, Spotter.Limit warn, Spotter.Limit fail) {
    Value value = value(Spotter.FieldType.FIELD_TYPE_TEXT, warn, fail, null);
    value.kind = Value.Kind.TEXT;
    value.text = text;
    Judge.judge(value);
    return value;
  }

  static Value flag(boolean flag, Spotter.Limit warn, Spotter.Limit fail) {
    Value value = value(Spotter.FieldType.FIELD_TYPE_BOOLEAN, warn, fail, null);
    value.kind = Value.Kind.FLAG;
    value.flag = flag;
    Judge.judge(value);
    return value;
  }

  static Value unavailable(Spotter.FieldType type, Spotter.Limit warn, Spotter.Limit fail) {
    Value value = value(type, warn, fail, null);
    value.kind = Value.Kind.UNAVAILABLE;
    value.text = "timed out after 5 s";
    Judge.judge(value);
    return value;
  }

  static Spotter.Scalar scalar(String text) {
    return Spotter.Scalar.newInstance().setText(text);
  }

  @Test
  void aboveAndBelowAreForNumbers() {
    Spotter.Limit warn = limit().setAbove(70);
    Spotter.Limit fail = limit().setAbove(85).setBelow(-10);
    assertThat(number(60, warn, fail).level()).isEqualTo(Level.OK);
    assertThat(number(60, warn, fail).reason()).isEmpty();
    assertThat(number(70, warn, fail).level()).isEqualTo(Level.OK);
    assertThat(number(70.5, warn, fail).level()).isEqualTo(Level.WARNING);
    assertThat(number(70.5, warn, fail).reason()).isEqualTo("above 70 °C");
    assertThat(number(90, warn, fail).level()).isEqualTo(Level.FAILING);
    assertThat(number(90, warn, fail).reason()).isEqualTo("above 85 °C");
    assertThat(number(-20, warn, fail).reason()).isEqualTo("below -10 °C");
    assertThat(number(Double.NaN, warn, fail).level()).isEqualTo(Level.OK);
  }

  @Test
  void equalsAndNotEqualsAreForAnyType() {
    assertThat(
            number(3, limit(), limit().setEquals(Spotter.Scalar.newInstance().setNumber(3)))
                .reason())
        .isEqualTo("is 3 °C");
    assertThat(
            number(200, limit(), limit().setNotEquals(Spotter.Scalar.newInstance().setNumber(200)))
                .level())
        .isEqualTo(Level.OK);
    assertThat(
            number(404, limit(), limit().setNotEquals(Spotter.Scalar.newInstance().setNumber(200)))
                .reason())
        .isEqualTo("isn't 200 °C");
    assertThat(text("inactive", limit(), limit().setNotEquals(scalar("active"))).reason())
        .isEqualTo("isn't active");
    assertThat(text("active", limit(), limit().setNotEquals(scalar("active"))).level())
        .isEqualTo(Level.OK);
    assertThat(text("calibrating", limit().setEquals(scalar("calibrating")), limit()).level())
        .isEqualTo(Level.WARNING);
    assertThat(
            flag(false, limit().setEquals(Spotter.Scalar.newInstance().setFlag(false)), limit())
                .reason())
        .isEqualTo("is false");
    assertThat(
            flag(false, limit().setNotEquals(Spotter.Scalar.newInstance().setFlag(true)), limit())
                .reason())
        .isEqualTo("isn't true");
    assertThat(
            flag(true, limit().setNotEquals(Spotter.Scalar.newInstance().setFlag(true)), limit())
                .level())
        .isEqualTo(Level.OK);
    // A comparison of another type can't match.
    assertThat(
            text("200", limit(), limit().setEquals(Spotter.Scalar.newInstance().setNumber(200)))
                .level())
        .isEqualTo(Level.OK);
  }

  @Test
  void failIsCheckedBeforeWarn() {
    Spotter.Limit warn = limit().setBelow(30);
    Spotter.Limit fail = limit().setBelow(10);
    assertThat(number(5, warn, fail).level()).isEqualTo(Level.FAILING);
    assertThat(number(5, warn, fail).reason()).isEqualTo("below 10 °C");
  }

  @Test
  void missingMatchesAValueThatsUnavailableOrEmptyText() {
    Value gone =
        unavailable(Spotter.FieldType.FIELD_TYPE_NUMBER, limit(), limit().setMissing(true));
    assertThat(gone.level()).isEqualTo(Level.FAILING);
    assertThat(gone.reason()).isEqualTo("timed out after 5 s");
    assertThat(
            unavailable(Spotter.FieldType.FIELD_TYPE_TEXT, limit().setMissing(true), limit())
                .level())
        .isEqualTo(Level.WARNING);
    assertThat(text("", limit(), limit().setMissing(true)).reason()).isEqualTo(Field.EMPTY);
    assertThat(text("", limit(), limit().setMissing(true)).level()).isEqualTo(Level.FAILING);
    assertThat(text(" ", limit(), limit().setMissing(true)).level()).isEqualTo(Level.OK);
  }

  @Test
  void aValueWithNoneAndNoMissingRuleIsUnavailableWithNoAlert() {
    Value gone = unavailable(Spotter.FieldType.FIELD_TYPE_NUMBER, limit().setBelow(5), limit());
    assertThat(gone.level()).isEqualTo(Level.UNAVAILABLE);
    assertThat(gone.reason()).isEqualTo("timed out after 5 s");
    assertThat(gone.available()).isFalse();
    assertThat(gone.number()).isNaN();
    // Nothing sent yet.
    Value none = value(Spotter.FieldType.FIELD_TYPE_TEXT, limit(), limit(), null);
    Judge.judge(none);
    assertThat(none.level()).isEqualTo(Level.UNAVAILABLE);
    assertThat(none.unavailable()).isEqualTo(Value.NOT_SENT);
  }

  @Test
  void aStatusIsItsOwnVerdict() {
    Value status =
        value(Spotter.FieldType.FIELD_TYPE_STATUS, limit(), limit().setMissing(true), null);
    status.kind = Value.Kind.STATUS;
    status.status = Level.WARNING;
    status.text = "below 30 fps";
    Judge.judge(status);
    assertThat(status.level()).isEqualTo(Level.WARNING);
    assertThat(status.reason()).isEqualTo("below 30 fps");
    assertThat(status.status()).isEqualTo(Level.WARNING);
    assertThat(status.text()).isEqualTo("below 30 fps");

    // A status with no level is no verdict: only a missing rule applies.
    status.status = Level.UNAVAILABLE;
    status.text = "";
    Judge.judge(status);
    assertThat(status.level()).isEqualTo(Level.FAILING);
  }

  @Test
  void robotCodesLimitsReplaceBothOfThePacksLevels() {
    Limits override = new Limits(limit().setAbove(50), limit());
    Value value =
        value(
            Spotter.FieldType.FIELD_TYPE_NUMBER,
            limit().setAbove(70),
            limit().setAbove(85),
            override);
    value.kind = Value.Kind.NUMBER;
    value.number = 90;
    Judge.judge(value);
    assertThat(value.level()).isEqualTo(Level.WARNING);
    assertThat(value.reason()).isEqualTo("above 50 °C");
    assertThat(value.overridden()).isTrue();
  }

  @Test
  void numbersInWordsAreShortWithTheirUnit() {
    assertThat(Field.number(15, "%")).isEqualTo("15 %");
    assertThat(Field.number(61.2, "°C")).isEqualTo("61.2 °C");
    assertThat(Field.number(0.5, "")).isEqualTo("0.5");
    assertThat(Field.number(-3, "V")).isEqualTo("-3 V");
    assertThat(Field.number(1e20, "")).isEqualTo("1.0E20");
  }

  @Test
  void anAlertSaysTheBoardTheLabelAndWhy() {
    Spotter.Limit fail = limit().setAbove(85).setMissing(true);
    assertThat(Link.text("vision-front", number(90, limit(), fail)))
        .isEqualTo("vision-front: Thing above 85 °C");
    assertThat(
            Link.text(
                "vision-front", unavailable(Spotter.FieldType.FIELD_TYPE_NUMBER, limit(), fail)))
        .isEqualTo("vision-front: Thing unavailable: timed out after 5 s");
    Value status = value(Spotter.FieldType.FIELD_TYPE_STATUS, limit(), limit(), null);
    status.kind = Value.Kind.STATUS;
    status.status = Level.FAILING;
    status.text = "no tags";
    Judge.judge(status);
    assertThat(Link.text("vision-front", status)).isEqualTo("vision-front: Thing: no tags");
    assertThat(status.toString()).isEqualTo("p.c.f = no tags (FAILING: no tags)");
  }

  @Test
  void aValueWithNoLabelIsCalledByItsId() {
    Field field =
        new Field(
            Spotter.FieldDeclaration.newInstance()
                .setId("debian.memory.available")
                .setType(Spotter.FieldType.FIELD_TYPE_NUMBER),
            null);
    assertThat(new Value(field).label()).isEqualTo("debian.memory.available");
    assertThat(new Value(field).toString())
        .isEqualTo(
            "debian.memory.available = unavailable: not sent yet (UNAVAILABLE: not sent yet)");
  }
}
