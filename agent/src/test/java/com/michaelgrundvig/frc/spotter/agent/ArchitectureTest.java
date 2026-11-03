package com.michaelgrundvig.frc.spotter.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.michaelgrundvig.frc.spotter.agent.commands.Commands;
import com.michaelgrundvig.frc.spotter.agent.server.AgentServer;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.HttpURLConnection;
import java.nio.channels.FileChannel;
import org.junit.jupiter.api.Test;

/**
 * The agent's boundaries: what keeps it a bounded runner of its packs, and small; and its packages,
 * one per concern, each building on those below it: {@code board} (the computer and its own
 * settings), {@code packs}, {@code commands}, then {@code values}, {@code actions} and {@code
 * logs}, then {@code server} (the protocol), and the agent itself, which starts them all.
 */
class ArchitectureTest {
  private static final String ROOT = "com.michaelgrundvig.frc.spotter.agent";

  private static final JavaClasses AGENT =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.michaelgrundvig.frc.spotter.agent");

  @Test
  void onlyCommandsRunsWhatAPackNames() {
    noClasses()
        .that()
        .doNotBelongToAnyOf(Commands.class)
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
        .doNotBelongToAnyOf(AgentServer.class)
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

  @Test
  void itsConcernsBuildOnEachOtherOneWay() {
    slices()
        .matching(ROOT + ".(*)..")
        .should()
        .beFreeOfCycles()
        .because("each concern builds on the ones below it, never back up")
        .check(AGENT);
  }

  @Test
  void onlyTheServerAndTheAgentItselfKnowTheWhole() {
    noClasses()
        .that()
        .resideInAnyPackage(
            ROOT + ".board..",
            ROOT + ".packs..",
            ROOT + ".commands..",
            ROOT + ".values..",
            ROOT + ".actions..",
            ROOT + ".logs..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(ROOT + ".server..", ROOT)
        .because(
            "the protocol serves what the packs do, and the agent wires them; never the reverse")
        .check(AGENT);
  }
}
