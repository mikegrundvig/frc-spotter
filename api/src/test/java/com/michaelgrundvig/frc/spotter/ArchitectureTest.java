package com.michaelgrundvig.frc.spotter;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** What keeps the shared code shareable: the robot program, the agent, and the build use it. */
class ArchitectureTest {
  private static final JavaClasses COMMON =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.michaelgrundvig.frc.spotter");

  @Test
  void onlyTheJdkAndJSpecify() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("java..", "org.jspecify..", "com.michaelgrundvig.frc.spotter..")
        .because(
            "the coprocessors don't run WPILib, and the robot program should get nothing it"
                + " doesn't already have")
        .check(COMMON);
  }

  @Test
  void noStaticMutableState() {
    fields()
        .that()
        .areStatic()
        .should()
        .beFinal()
        .because("shared code runs in the robot program, the agent, and the build alike")
        .check(COMMON);
  }
}
