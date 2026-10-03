package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;

/**
 * A field as a pack declares it: a value a collector fills, or a part of an action's response. The
 * agent passes its limits through and never evaluates them.
 *
 * @param name its name in its collector or response: a value's id is {@code pack.collector.name}
 * @param label what a display calls it; empty when the pack gives none
 * @param type its type
 * @param unit its unit, for a number; empty when none
 * @param warn its warning limit; empty when none
 * @param fail its failing limit; empty when none
 * @param fileName the name a {@code file} field downloads as; empty when none
 */
record Field(
    String name,
    String label,
    Spotter.FieldType type,
    String unit,
    Spotter.Limit warn,
    Spotter.Limit fail,
    String fileName) {

  /** A field with nothing but its name and type. */
  static Field of(String name, Spotter.FieldType type) {
    return new Field(
        name, "", type, "", Spotter.Limit.newInstance(), Spotter.Limit.newInstance(), "");
  }

  /**
   * Its declaration, under an id: a value's {@code pack.collector.field}, a response field's name.
   */
  Spotter.FieldDeclaration declaration(String id) {
    Spotter.FieldDeclaration declaration =
        Spotter.FieldDeclaration.newInstance()
            .setId(id)
            .setLabel(label)
            .setType(type)
            .setUnit(unit);
    if (!warn.isEmpty()) {
      declaration.setWarn(warn);
    }
    if (!fail.isEmpty()) {
      declaration.setFail(fail);
    }
    return declaration;
  }
}
