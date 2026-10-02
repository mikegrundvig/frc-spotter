package com.michaelgrundvig.frc.spotter.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The agent's boundaries: what keeps it read-only, bounded, and small. */
class ArchitectureTest {
  private static final JavaClasses AGENT =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.michaelgrundvig.frc.spotter.agent");

  @Test
  void onlyProcessCommandsStartsAProcess() {
    noClasses()
        .that()
        .doNotHaveSimpleName("ProcessCommands")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ProcessBuilder.class.getName())
        .orShould()
        .callMethod(Runtime.class, "exec", String.class)
        .because("every command goes through one bounded runner, with a timeout and a size limit")
        .check(AGENT);
  }

  @Test
  void theWebServerStaysInOnePlace() {
    noClasses()
        .that()
        .doNotHaveSimpleName("AgentServer")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.sun.net.httpserver..")
        .because("the API is one class; the rest only reads the computer")
        .check(AGENT);
  }

  @Test
  void theAgentKnowsTheComputerNotTheSoftwareOnIt() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.michaelgrundvig.frc.spotter.settings..", "java.sql..", "org.sqlite..")
        .because(
            "what the agent knows of a computer's software (PhotonVision's settings, its database)"
                + " is a pack's, run as a probe: the agent itself serves any computer")
        .check(AGENT);
  }

  @Test
  void noStaticMutableState() {
    fields()
        .that()
        .areStatic()
        .should()
        .beFinal()
        .because("the agent's state lives in its objects, so tests can make as many as they like")
        .check(AGENT);
  }
}
