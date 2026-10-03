package com.michaelgrundvig.frc.spotter.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.HttpURLConnection;
import java.nio.channels.FileChannel;
import org.junit.jupiter.api.Test;

/** The agent's boundaries: what keeps it a bounded runner of its packs, and small. */
class ArchitectureTest {
  private static final JavaClasses AGENT =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.michaelgrundvig.frc.spotter.agent");

  @Test
  void onlyCommandsRunsWhatAPackNames() {
    noClasses()
        .that()
        .doNotHaveSimpleName("Commands")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName(ProcessBuilder.class.getName())
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(HttpURLConnection.class.getName())
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(FileChannel.class.getName())
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
        .because("the protocol is one class; the rest runs packs")
        .check(AGENT);
  }

  @Test
  void theAgentMeasuresNothingItself() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("java.sql..", "org.sqlite..", "java.lang.management..")
        .because("what the agent knows of a board, its own health included, is its packs'")
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
