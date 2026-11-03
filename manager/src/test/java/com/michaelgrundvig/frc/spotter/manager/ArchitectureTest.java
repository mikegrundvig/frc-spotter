package com.michaelgrundvig.frc.spotter.manager;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The manager's boundaries: plain Java and Spotter's protocol, nothing of WPILib's, and no clock of
 * its own, so robot code's clock (a simulation's, a replay's) is the only time it knows.
 */
class ArchitectureTest {
  private static final JavaClasses MANAGER =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.michaelgrundvig.frc.spotter.manager");

  @Test
  void itNeedsNothingButJavaAndTheProtocol() {
    classes()
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "java..",
            "com.michaelgrundvig.frc.spotter.manager..",
            "com.michaelgrundvig.frc.spotter.protocol..",
            "us.hebi.quickbuf..",
            "org.jspecify.annotations..")
        .because("robot code wires in WPILib's pieces; the manager runs anywhere Java 17 does")
        .check(MANAGER);
  }

  @Test
  void itHasNoClockOfItsOwn() {
    noClasses()
        .should()
        .callMethod(System.class, "nanoTime")
        .orShould()
        .callMethod(System.class, "currentTimeMillis")
        .orShould()
        .dependOnClassesThat()
        .areAssignableTo(Clock.class)
        .orShould()
        .callMethod(Instant.class, "now")
        .because("every time it judges is on the robot's clock, which robot code supplies")
        .check(MANAGER);
  }

  @Test
  void noStaticMutableState() {
    fields()
        .that()
        .areStatic()
        .should()
        .beFinal()
        .because("the manager's state lives in its objects, so tests can make as many as they like")
        .check(MANAGER);
  }

  @Test
  void itsMachineryBuildsOnItselfOneWay() {
    slices()
        .matching("com.michaelgrundvig.frc.spotter.manager.(*)..")
        .should()
        .beFreeOfCycles()
        .because(
            "following a board uses limits, requests, runs and packs; runs and packs use requests;"
                + " never the reverse")
        .check(MANAGER);
  }

  /** The protocol, which shares this project with the manager. */
  private static final JavaClasses PROTOCOL =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.michaelgrundvig.frc.spotter.protocol");

  @Test
  void theProtocolIsTheContractAloneAndKnowsNoManager() {
    classes()
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(
            "java..",
            "com.michaelgrundvig.frc.spotter.protocol..",
            "us.hebi.quickbuf..",
            "org.jspecify.annotations..")
        .because(
            "the protocol is what the agent and the manager share: the agent uses it without the"
                + " manager's code, so it may use none")
        .check(PROTOCOL);
  }
}
