package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The agent's description and values: what a board says it has, and what it ignored. */
class AgentTest {
  @TempDir Path dir;
  Fixture fixture;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
  }

  private static final String VISION =
      """
      pack: vision
      version: 1.0.0
      collectors:
        - id: service
          run: [./service]
          every: 2s
          fields:
            running: {label: Vision's service, type: text, fail: {notEquals: active}}
        - id: health
          run: [./health]
          every: 1s
          fields:
            fps: {label: Frame rate, type: number, unit: fps, warn: {below: 25}, fail: {below: 10}}
            camera: {type: status}
      logs:
        - id: log
          label: Vision's log
          run: [./log]
      actions:
        - id: restart
          label: Restart vision
          description: Restarts its service
          run: [./restart]
          confirm: Restart it?
          timeout: 90s
          whileEnabled: true
          response:
            exit: {type: number, fail: {notEquals: 0}}
      """;

  @Test
  void aBoardWithNoPacksIsAValidAgentWithItsIdentityAndTwoActions() {
    try (Agent agent = fixture.agent()) {
      Spotter.Description description = agent.description();
      assertThat(description.getAgentVersion()).isEqualTo("0.4.0-test");
      Spotter.Identity identity = description.getIdentity();
      assertThat(identity.getHostname()).isEqualTo("vision-front");
      assertThat(identity.getAddresses()).containsExactly(Fixture.ADDRESS);
      assertThat(identity.getMac()).isEqualTo("c0:74:2b:fe:12:34");
      assertThat(identity.getBootId()).isEqualTo("3c1e6a2e-0000-4000-8000-000000000001");
      assertThat(identity.getOsRelease())
          .extracting(e -> e.getKey() + "=" + e.getValue())
          .containsExactly(
              "PRETTY_NAME=Debian GNU/Linux 13 (trixie)", "ID=debian", "VERSION_ID=13");
      assertThat(description.getPacks()).isEmpty();
      assertThat(description.getValues()).isEmpty();
      assertThat(description.getLogs()).isEmpty();
      assertThat(description.getActions())
          .extracting(Spotter.ActionDeclaration::getId)
          .containsExactly("core.power-off", "core.reboot");
      Spotter.ActionDeclaration off = description.getActions().get(0);
      assertThat(off.getLabel()).isEqualTo("Power off");
      assertThat(off.getConfirm()).isEqualTo("Power this computer off?");
      assertThat(off.getInput()).isEqualTo(Spotter.Input.INPUT_NONE);
      assertThat(off.getWhileEnabled()).isFalse();
      assertThat(off.getTimeoutSeconds()).isEqualTo(60);
      assertThat(off.getResponse())
          .extracting(Spotter.FieldDeclaration::getId)
          .containsExactly("outcome", "outcomeMessage", "exit", "output");
      assertThat(description.getProblems()).isEmpty();
      assertThat(description.getPushedPacks()).isEmpty();
      // Nothing configured, so no controller named: it takes no pushes.
      assertThat(description.getRefusesPushes()).isTrue();
      assertThat(description.getRequiresSignatures()).isFalse();
      Spotter.Values values = agent.values();
      assertThat(values.getComplete()).isTrue();
      assertThat(values.getValues()).isEmpty();
      assertThat(values.getRevision()).isEqualTo(description.getRevision());
    }
  }

  @Test
  void itDescribesItsPacksValuesLogsAndActionsByIdAndIndex() {
    fixture.pack("vision", VISION);
    fixture.pushed("detector", "pack: detector\nversion: 0.2.0\n");
    fixture.config("{\"team\": 1234}");
    try (Agent agent = fixture.agent()) {
      Spotter.Description description = agent.description();
      assertThat(description.getPacks())
          .extracting(
              p -> p.getName() + " " + p.getVersion() + " " + p.getFolder() + " " + p.getPushed())
          .containsExactly(
              "detector 0.2.0 /var/lib/frc-spotter/packs/detector true",
              "vision 1.0.0 /etc/frc-spotter/packs/vision false");
      assertThat(description.getValues())
          .extracting(Spotter.FieldDeclaration::getId)
          .containsExactly("vision.service.running", "vision.health.fps", "vision.health.camera");
      Spotter.FieldDeclaration fps = description.getValues().get(1);
      assertThat(fps.getLabel()).isEqualTo("Frame rate");
      assertThat(fps.getType()).isEqualTo(Spotter.FieldType.FIELD_TYPE_NUMBER);
      assertThat(fps.getUnit()).isEqualTo("fps");
      assertThat(fps.getWarn().getBelow()).isEqualTo(25);
      assertThat(fps.getFail().getBelow()).isEqualTo(10);
      assertThat(description.getValues().get(2).hasWarn()).isFalse();
      assertThat(description.getLogs())
          .extracting(l -> l.getId() + ": " + l.getLabel())
          .containsExactly("vision.log: Vision's log");
      Spotter.ActionDeclaration restart = description.getActions().get(2);
      assertThat(restart.getId()).isEqualTo("vision.restart");
      assertThat(restart.getDescription()).isEqualTo("Restarts its service");
      assertThat(restart.getTimeoutSeconds()).isEqualTo(90);
      assertThat(restart.getWhileEnabled()).isTrue();
      assertThat(restart.getResponse().get(2).getFail().getNotEquals().getNumber()).isZero();
      assertThat(description.getPushedPacks()).hasSize(64);
      // What runs stays on the board.
      assertThat(description.toString()).doesNotContain("./service", "./restart");
      Spotter.Values values = agent.values();
      assertThat(values.getValues())
          .extracting(Spotter.FieldValue::getIndex)
          .containsExactly(0, 1, 2);
      assertThat(values.getValues()).allMatch(v -> v.getUnavailable().equals(ValueStore.NOT_YET));
    }
  }

  @Test
  void itsCollectorsFillItsValuesWhenTheyRun() {
    String folder = fixture.pack("vision", VISION);
    fixture.script(folder + "/service", "echo inactive; exit 3");
    fixture.script(
        folder + "/health",
        "echo '{\"fps\": 29.5, \"camera\": {\"level\": \"warning\", \"message\": \"slow\"}}'");
    try (Agent agent = fixture.agent()) {
      agent.collectors().runAll();
      us.hebi.quickbuf.RepeatedMessage<Spotter.FieldValue> values = agent.values().getValues();
      assertThat(values.get(0).getText()).isEqualTo("inactive");
      assertThat(values.get(1).getNumber()).isEqualTo(29.5);
      assertThat(values.get(2).getStatus().getLevel()).isEqualTo(Spotter.Level.LEVEL_WARNING);
      assertThat(values.get(2).getStatus().getMessage()).isEqualTo("slow");
    }
  }

  @Test
  void itsRevisionChangesWithItsDescriptionAndOnlyThen() {
    int first;
    try (Agent agent = fixture.agent()) {
      first = agent.description().getRevision();
      assertThat(agent.description().getRevision()).isEqualTo(first);
      // Its addresses change as it runs: read afresh after a while, a new revision.
      fixture.addresses.add("10.12.34.12");
      assertThat(agent.description().getRevision()).isEqualTo(first);
      fixture.nanos.addAndGet(Agent.DESCRIPTION_NANOS);
      assertThat(agent.description().getIdentity().getAddresses()).hasSize(2);
      assertThat(agent.description().getRevision()).isNotEqualTo(first);
    }
    fixture.addresses.remove("10.12.34.12");
    try (Agent again = fixture.agent()) {
      assertThat(again.description().getRevision()).isEqualTo(first);
    }
  }

  @Test
  void itsSettingsSayWhetherItRefusesPushesAndRequiresSignatures() {
    fixture.config("{\"acceptPushes\": false, \"trustedKeys\": [\"" + AgentConfigTest.KEY + "\"]}");
    try (Agent agent = fixture.agent()) {
      assertThat(agent.description().getRefusesPushes()).isTrue();
      assertThat(agent.description().getRequiresSignatures()).isTrue();
    }
  }

  @Test
  void whatItIgnoredIsInItsProblems() {
    fixture.config("{\"packs\": []}");
    fixture.pack("mine", "pack: mine\n");
    fixture.owners.put(Packs.INSTALLED + "/mine/pack.yaml", new Host.Owner("pi", 0644));
    try (Agent agent = fixture.agent()) {
      assertThat(agent.description().getProblems())
          .containsExactly(
              "/etc/frc-spotter/agent.json: can't be read, so every write is refused: it may"
                  + " set acceptPushes, bind, controller, port, team, trustedKeys only, not packs"
                  + " (packs are folders in /etc/frc-spotter/packs/)",
              Packs.INSTALLED + "/mine/pack.yaml: isn't root's (its owner is pi), ignored");
    }
  }

  @Test
  void aDescriptionListsAtMostItsMostProblemsEachCut() {
    java.util.List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i < 200; i++) {
      many.add("problem " + i);
    }
    many.set(0, "x".repeat(2000));
    Configuration configuration =
        new Configuration(AgentConfig.DEFAULT, Packs.Loaded.NONE, many, "");
    com.michaelgrundvig.frc.spotter.protocol.Spotter.Description description =
        new Describer("0.4.0", configuration)
            .describe(com.michaelgrundvig.frc.spotter.protocol.Spotter.Identity.newInstance());
    assertThat(description.getProblems().length())
        .isEqualTo(com.michaelgrundvig.frc.spotter.protocol.Protocol.MAX_PROBLEMS);
    assertThat(description.getProblems().get(0)).hasSize(Describer.MAX_PROBLEM + 3);
    assertThat(description.getProblems().get(127))
        .isEqualTo("73 more problems: the agent's journal lists them all");
  }

  @Test
  void anIdentityThatCantBeReadIsLoggedAndTheLastOneKept() {
    Fixture broken = fixture;
    try (Agent agent = broken.agent()) {
      assertThat(agent.description().getIdentity().getHostname()).isEqualTo("vision-front");
    }
    Host failing =
        new Host(
            fixture.root,
            path -> Host.Owner.root(0644),
            () -> {
              throw new java.io.IOException("no interfaces");
            },
            fixture.nanos::get,
            fixture.log::add);
    try (Agent agent = new Agent(failing, Configuration.read(failing), "0.4.0", null, () -> {})) {
      assertThat(agent.description().getIdentity().getHostname()).isEmpty();
    }
    assertThat(fixture.log())
        .contains("Couldn't read this board's identity: java.io.IOException: no interfaces");
  }

  @Test
  void theControllerIs2OnItsOwnRobotNetworkUnlessNamed() throws Exception {
    try (Agent agent = fixture.agent()) {
      assertThat(agent.controller().address()).isEqualTo("10.12.34.2");
      // Only worked out: it takes the built-in actions from it, and no pushes.
      assertThat(agent.controller().named()).isFalse();
      assertThat(AgentServer.may(agent.controller(), true)).isTrue();
      assertThat(AgentServer.may(agent.controller(), false)).isFalse();
      assertThat(agent.configuration().acceptsPushes()).isFalse();
      assertThat(agent.configuration().pushesRefused())
          .isEqualTo(
              "its agent.json names no controller or team, and pushes are taken from a named one"
                  + " only");
      assertThat(agent.description().getRefusesPushes()).isTrue();
      fixture.addresses.add("10.99.71.11");
      assertThat(agent.controller().address()).isEmpty();
      assertThat(agent.controller().why())
          .contains("more than one 10.x network (10.12.34.x, 10.99.71.x)");
      fixture.addresses.clear();
      fixture.addresses.add("192.168.1.5");
      assertThat(agent.controller().why()).startsWith("this computer has no 10.TE.AM.x address");
    }
    fixture.config("{\"controller\": \"10.12.34.3\"}");
    try (Agent agent = fixture.agent()) {
      assertThat(agent.controller().address()).isEqualTo("10.12.34.3");
      assertThat(agent.controller().named()).isTrue();
      assertThat(AgentServer.may(agent.controller(), false)).isTrue();
      assertThat(agent.configuration().acceptsPushes()).isTrue();
    }
    fixture.config("{\"team\": 1234}");
    try (Agent agent = fixture.agent()) {
      assertThat(agent.controller().address()).isEqualTo("10.12.34.2");
      assertThat(agent.controller().named()).isTrue();
      assertThat(agent.description().getRefusesPushes()).isFalse();
    }
    try (Agent agent =
        new Agent(fixture.host, fixture.configuration(), "0.4.0", "127.0.0.1", () -> {})) {
      assertThat(agent.controller().address()).isEqualTo("127.0.0.1");
      assertThat(agent.names()).containsExactly("vision-front", "vision-front.local");
    }
  }

  @Test
  void osReleaseIsReadAsItsManualWritesIt() {
    assertThat(
            IdentitySource.parseOsRelease(
                "# comment\nID=debian\nNAME='Debian'\nPRETTY=\"say \\\"hi\\\" \\$x\"\nnot a line\n"
                    + "1BAD=x\n"))
        .containsExactly(
            java.util.Map.entry("ID", "debian"),
            java.util.Map.entry("NAME", "Debian"),
            java.util.Map.entry("PRETTY", "say \"hi\" $x"));
    fixture.write("/usr/lib/os-release", "ID=fallback\n");
    try {
      java.nio.file.Files.delete(fixture.path("/etc/os-release"));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    try (Agent agent = fixture.agent()) {
      assertThat(agent.description().getIdentity().getOsRelease().get(0).getValue())
          .isEqualTo("fallback");
    }
  }
}
