package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/**
 * A pack that needs more than the agent's sandbox gives, and the systemd drop-in its installer adds
 * to give it that and no more: its action writes a model into a folder outside the agent's own,
 * which the sandbox (ProtectSystem=strict) keeps read-only until the drop-in names it
 * (ReadWritePaths). On a board the drop-in is {@code
 * /etc/systemd/system/frc-spotter.service.d/<pack>.conf}; here it's in {@code /run/systemd/system},
 * which reads the same, as the test's image keeps /etc read-only.
 */
@ContainerTest
class DropInContainerTest {
  static final String PACK =
      """
      pack: saver
      actions:
        - id: save
          label: Save a model
          run: [sh, -c, "echo model > /data/team/model"]
          response:
            exit: {type: number, fail: {notEquals: 0}}
      """;

  private static Spotter.FieldValue exit(Spotter.RunState state) {
    for (Spotter.FieldValue value : state.getResult().getResponse()) {
      if (value.getName().equals("exit")) {
        return value;
      }
    }
    throw new AssertionError("no exit in " + state);
  }

  @Test
  void aPackThatWritesOutsideTheSandboxNeedsItsInstallersDropIn() throws Exception {
    try (Network network = TestNetwork.create();
        Coprocessor coprocessor =
            new Coprocessor(TestImages.agent(), network, 55, "vision-dropin")) {
      coprocessor.start();
      coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
      // Where the team's program keeps its model: the agent's account's, on the board's writable
      // partition, and outside the sandbox's own folders.
      coprocessor.run("sh", "-c", "mkdir -p /data/team && chown frc-spotter /data/team");
      TestClient client = new TestClient(coprocessor.agentHost(), coprocessor.agentPort());
      byte[] bundle =
          Spotter.PackBundle.newInstance()
              .addFiles(
                  Spotter.PackFile.newInstance()
                      .setPath("saver/pack.yaml")
                      .setContent(PACK.getBytes(StandardCharsets.UTF_8)))
              .toByteArray();
      HttpResponse<byte[]> pushed = client.post(Protocol.PACKS, bundle, null);
      assertThat(pushed.statusCode()).as("%s", pushed).isEqualTo(202);
      awaitAction(client, "saver.save");

      // Without the drop-in: the sandbox's read-only filesystem.
      Spotter.RunState refused = client.finished(client.start("saver.save", new byte[0]));
      assertThat(exit(refused).getNumber()).isNotZero();
      Spotter.LogPage log =
          client.get(Protocol.RUNS + refused.getRun() + "/log", Spotter.LogPage.newInstance());
      assertThat(log.getEntries())
          .extracting(Spotter.LogEntry::getMessage)
          .anyMatch(line -> line.contains("Read-only file system"));

      // The installer's drop-in, and a restart: the folder is the pack's to write.
      coprocessor.write(
          "/run/systemd/system/frc-spotter.service.d/saver.conf",
          "[Service]\nReadWritePaths=/data/team\n");
      coprocessor.run("systemctl", "daemon-reload");
      coprocessor.run("systemctl", "restart", "frc-spotter.service");
      coprocessor.awaitAgent();
      awaitAction(client, "saver.save");
      Spotter.RunState saved = client.finished(client.start("saver.save", new byte[0]));
      assertThat(exit(saved).getNumber()).isZero();
      assertThat(coprocessor.run("cat", "/data/team/model")).isEqualTo("model\n");
    }
  }

  /** Waits for the agent to describe an action, after its restart. */
  private static void awaitAction(TestClient client, String id) throws Exception {
    for (int i = 0; i < 150; i++) {
      try {
        for (Spotter.ActionDeclaration action : client.describe().getActions()) {
          if (action.getId().equals(id)) {
            return;
          }
        }
      } catch (java.io.IOException e) {
        // Restarting.
      }
      Thread.sleep(100);
    }
    throw new AssertionError("no action " + id + " within 15 s");
  }
}
