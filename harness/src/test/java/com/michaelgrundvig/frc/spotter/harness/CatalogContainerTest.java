package com.michaelgrundvig.frc.spotter.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.Level;
import com.michaelgrundvig.frc.spotter.manager.LogQuery;
import com.michaelgrundvig.frc.spotter.manager.Manager;
import com.michaelgrundvig.frc.spotter.manager.Robot;
import com.michaelgrundvig.frc.spotter.manager.Run;
import com.michaelgrundvig.frc.spotter.manager.Value;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.Network;

/**
 * The catalog's packs (the repository's packs/), installed as a board's installer puts them in the
 * Debian 13 test container under systemd, with the agent from its .deb, read by the robot's
 * manager: every value fills with a sane value (or, for what a container lacks, is unavailable and
 * says why), the limits judge, and the journal pages. The scripts' parsing of what a container
 * can't give (an NVMe drive, chrony, systemd-timesyncd, a Pi's kernel log) is checked against
 * stand-ins of those programs, off the PATH.
 */
@ContainerTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogContainerTest {
  /** The stand-ins' folder in the container, first on a script's PATH when a test says. */
  static final String STAND_INS =
      "PATH=/opt/stand-ins:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

  Network network;
  Coprocessor coprocessor;
  @Nullable Manager manager;

  @BeforeAll
  void aBoardWithTheCatalog() {
    network = TestNetwork.create();
    coprocessor = new Coprocessor(TestImages.catalog(), network, 81, "vision-catalog");
    coprocessor.start();
    // Writes from the test, as the robot controller: the photonvision pack's actions.
    try {
      coprocessor.restartAgent("--controller=" + coprocessor.robotAddress());
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @AfterAll
  void stop() {
    coprocessor.close();
    network.close();
  }

  @AfterEach
  void closeTheManager() {
    if (manager != null) {
      manager.close();
      manager = null;
    }
  }

  /** The robot's manager of the board, once every value has been collected once. */
  private Board connected() throws Exception {
    Manager started =
        new Manager(
            new Robot(() -> false, () -> false, System::nanoTime),
            List.of(coprocessor.agentHost() + ":" + coprocessor.agentPort()));
    manager = started;
    started.start();
    Board board = started.boards().get(0);
    await(
        "every value collected",
        () ->
            !board.values().isEmpty()
                && board.values().stream()
                    .noneMatch(v -> v.unavailable().equals("not collected yet")));
    return board;
  }

  private void await(String what, BooleanSupplier condition) throws Exception {
    Manager started = Objects.requireNonNull(manager);
    long until = System.nanoTime() + 60_000_000_000L;
    while (true) {
      started.update();
      if (condition.getAsBoolean()) {
        return;
      }
      if (System.nanoTime() > until) {
        throw new AssertionError(
            "not within 60 s: "
                + what
                + "; "
                + started.boards()
                + " "
                + started.boards().get(0).values());
      }
      Thread.sleep(50);
    }
  }

  private static Value value(Board board, String id) {
    return Objects.requireNonNull(board.value(id), id);
  }

  /** Whether a path exists in the container. */
  private boolean exists(String glob) {
    return coprocessor
        .run("sh", "-c", "ls -d " + glob + " >/dev/null 2>&1 && echo yes || echo no")
        .strip()
        .equals("yes");
  }

  @Test
  void everyDebianValueFillsWithASaneValue() throws Exception {
    Board board = connected();
    assertThat(board.description().getProblems()).isEmpty();
    StringBuilder seen = new StringBuilder("The debian pack in the Debian 13 container:");
    for (Value each : board.values()) {
      if (each.id().startsWith("debian.")) {
        seen.append("\n  ").append(each);
      }
    }
    System.out.println(seen);

    assertThat(value(board, "debian.cpu.busiest").number()).isBetween(0.0, 100.0);
    Value capped = value(board, "debian.cpu.capped");
    if (exists("/sys/devices/system/cpu/cpufreq/policy*")) {
      assertThat(capped.available()).as(capped.toString()).isTrue();
    } else {
      assertThat(capped.unavailable()).isEqualTo("its output has no \"capped\"");
    }
    Value hottest = value(board, "debian.thermal.hottest");
    if (exists("/sys/class/thermal/thermal_zone*")) {
      assertThat(hottest.number()).as(hottest.toString()).isBetween(-40.0, 125.0);
    } else {
      assertThat(hottest.unavailable()).isEqualTo("its output has no \"hottest\"");
    }
    Value available = value(board, "debian.memory.available");
    assertThat(available.number()).isGreaterThan(0.0).isLessThanOrEqualTo(100.0);
    assertThat(value(board, "debian.disk.root-free").number()).isBetween(0.0, 100.0);
    // The container's eth0, a veth: up, at a veth's speed, never dropped.
    Value up = value(board, "debian.network.up");
    assertThat(up.flag()).isTrue();
    assertThat(up.level()).isEqualTo(Level.OK);
    assertThat(value(board, "debian.network.speed").number()).isPositive();
    assertThat(value(board, "debian.network.drops").number()).isZero();
    assertThat(value(board, "debian.usb.devices").text()).isNotEmpty();
    // A container's journal has no kernel lines.
    assertThat(value(board, "debian.usb.disconnects").number()).isZero();
    assertThat(value(board, "debian.clock.synced").available())
        .as(value(board, "debian.clock.synced").toString())
        .isTrue();
    // No chrony and no systemd-timesyncd in a container: no offset.
    assertThat(value(board, "debian.clock.offset").unavailable())
        .isEqualTo("its output has no \"offset\"");
    // No NVMe drive in a container: no file.
    assertThat(value(board, "debian.drive.wear").unavailable())
        .isEqualTo("no such file: /run/frc-spotter-debian/drive.json");
    assertThat(value(board, "debian.uptime.seconds").number()).isPositive();
  }

  @Test
  void theDrivesHealthIsTheRootTimersFileAndItsLimitsJudgeIt() throws Exception {
    // The timer's service, as installed: without a drive, it runs and writes nothing.
    assertThat(coprocessor.run("systemctl", "is-enabled", "frc-spotter-debian-drive.timer").strip())
        .isEqualTo("enabled");
    coprocessor.run("systemctl", "start", "frc-spotter-debian-drive.service");
    assertThat(exists("/run/frc-spotter-debian/drive.json")).isFalse();

    // Stopped while the test writes the file in its place, or a run of it, finding no drive, would
    // take the file away.
    coprocessor.run(
        "systemctl", "stop", "frc-spotter-debian-drive.timer", "frc-spotter-debian-drive.service");
    try {
      // As root, with nvme-cli's stand-in and a file for a drive: its report, as the agent reads
      // it once it's started again (the drive's collector runs each minute, and an agent that
      // starts runs every collector at once).
      driveHealth("3");
      assertThat(coprocessor.run("cat", "/run/frc-spotter-debian/drive.json").strip())
          .isEqualTo("{\"wear\": 3, \"unsafe-shutdowns\": 23}");
      Board board = restarted();
      assertThat(value(board, "debian.drive.wear").number()).isEqualTo(3);
      assertThat(value(board, "debian.drive.wear").level()).isEqualTo(Level.OK);
      assertThat(value(board, "debian.drive.unsafe-shutdowns").number()).isEqualTo(23);

      for (String[] wear :
          new String[][] {{"85", "WARNING", "above 80 %"}, {"97", "FAILING", "above 95 %"}}) {
        driveHealth(wear[0]);
        Value drive = value(restarted(), "debian.drive.wear");
        assertThat(drive.number()).isEqualTo(Double.parseDouble(wear[0]));
        assertThat(drive.level()).isEqualTo(Level.valueOf(wear[1]));
        assertThat(drive.reason()).isEqualTo(wear[2]);
      }
    } finally {
      // As it was: no drive, the agent's value of that, and the timer.
      coprocessor.run("rm", "-f", "/run/frc-spotter-debian/drive.json", "/run/stand-in-wear");
      coprocessor.run("systemctl", "start", "frc-spotter-debian-drive.timer");
      restarted();
      closeTheManager();
    }
  }

  /**
   * The board, its agent started again: the drive's collector runs each minute, and an agent that
   * starts runs every collector at once.
   */
  private Board restarted() throws Exception {
    closeTheManager();
    // Restarted this often, the agent would pass systemd's start limit (five in ten seconds):
    // reset-failed clears its count.
    coprocessor.run("systemctl", "reset-failed", "frc-spotter.service");
    coprocessor.run("systemctl", "restart", "frc-spotter.service");
    coprocessor.awaitAgent();
    return connected();
  }

  private void driveHealth(String wear) {
    coprocessor.write("/run/stand-in-wear", wear);
    coprocessor.run(
        "env", STAND_INS, "/etc/frc-spotter/packs/debian/drive-health", "/etc/hostname");
  }

  @Test
  void theClockReadsChronysOffsetElseSystemdTimesyncds() {
    String clock = "/etc/frc-spotter/packs/debian/clock";
    assertThat(coprocessor.run("env", STAND_INS, clock).strip())
        .isEqualTo("{\"synced\": true, \"offset\": 0.000412}");
    coprocessor.write("/run/stand-in-no-chrony", "");
    try {
      for (String[] span :
          new String[][] {
            {"-2min 3.5s", "-123.5"}, {"+850us", "0.00085"}, {"+1.234ms", "0.001234"}, {"+2s", "2"}
          }) {
        coprocessor.write("/run/stand-in-offset", span[0]);
        assertThat(coprocessor.run("env", STAND_INS, clock).strip())
            .as(span[0])
            .isEqualTo("{\"synced\": true, \"offset\": " + span[1] + "}");
      }
    } finally {
      coprocessor.run("rm", "-f", "/run/stand-in-no-chrony", "/run/stand-in-offset");
    }
  }

  @Test
  void aNetworkInterfaceThatIsntThereHasNoLink() {
    assertThat(coprocessor.run("/etc/frc-spotter/packs/debian/network", "nothere").strip())
        .isEqualTo("{\"up\": false}");
  }

  @Test
  void theJournalPagesBackAndOnByCursorAndByLevel() throws Exception {
    for (int i = 1; i <= 6; i++) {
      coprocessor.run(
          "logger",
          "-t",
          "catalog-test",
          "-p",
          i % 2 == 0 ? "user.warning" : "user.info",
          "entry " + i);
    }
    Board board = connected();
    // The journal has others' lines among the test's (the agent's, systemd's): each page is read
    // for the test's own, by their source.
    await(
        "the logged entries",
        () -> {
          try {
            return mine(page(board, LogQuery.latest(50))).contains("entry 6");
          } catch (Exception e) {
            return false;
          }
        });
    Spotter.LogPage latest = page(board, LogQuery.latest(50));
    assertThat(mine(latest))
        .containsExactly("entry 1", "entry 2", "entry 3", "entry 4", "entry 5", "entry 6");
    Spotter.LogEntry four = entry(latest, "entry 4");
    assertThat(four.getSource()).isEqualTo("catalog-test");
    assertThat(four.getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_WARNING);
    assertThat(entry(latest, "entry 5").getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_INFO);
    assertThat(four.getTimeMicros()).isPositive();

    // Back from entry 4, and on from entry 2: the page leaves out the entry it's paged from.
    assertThat(mine(page(board, LogQuery.before(four.getCursor(), 50))))
        .endsWith("entry 1", "entry 2", "entry 3")
        .doesNotContain("entry 4");
    Spotter.LogEntry two = entry(latest, "entry 2");
    assertThat(mine(page(board, LogQuery.after(two.getCursor(), 50))))
        .startsWith("entry 3", "entry 4", "entry 5", "entry 6")
        .doesNotContain("entry 2");
    // Warnings and worse only.
    assertThat(mine(page(board, LogQuery.latest(50).atLeast("warning"))))
        .containsExactly("entry 2", "entry 4", "entry 6");
  }

  /** A page's entries the test logged, by message, in order. */
  private static List<String> mine(Spotter.LogPage page) {
    List<String> messages = new java.util.ArrayList<>();
    for (Spotter.LogEntry entry : page.getEntries()) {
      if (entry.getSource().equals("catalog-test")) {
        messages.add(entry.getMessage());
      }
    }
    return messages;
  }

  private static Spotter.LogEntry entry(Spotter.LogPage page, String message) {
    for (Spotter.LogEntry entry : page.getEntries()) {
      if (entry.getMessage().equals(message)) {
        return entry;
      }
    }
    throw new AssertionError("no " + message + " in " + page);
  }

  private static Spotter.LogPage page(Board board, LogQuery query) throws Exception {
    return board.log("debian.journal", query).get(30, TimeUnit.SECONDS);
  }

  @Test
  void aRaspberryPisUnderVoltageIsCountedFromItsKernelLog() throws Exception {
    Board board = connected();
    // Not a Pi: nothing logged.
    assertThat(value(board, "raspberry-pi.power.episodes").number()).isZero();
    Value summary = value(board, "raspberry-pi.power.summary");
    assertThat(summary.level()).isEqualTo(Level.OK);
    assertThat(summary.text()).isEqualTo("none since boot");
    // A Pi's kernel log, two episodes: failing.
    assertThat(
            coprocessor
                .run("env", STAND_INS, "/etc/frc-spotter/packs/raspberry-pi/undervoltage")
                .strip())
        .isEqualTo(
            "{\"episodes\": 2, \"summary\": {\"level\": \"failing\", \"message\": \"2 since boot,"
                + " latest 1234.6 s\"}}");
  }

  @Test
  void photonvisionsServiceWebAndSettingsAreRead() throws Exception {
    Board board = connected();
    assertThat(value(board, "photonvision.service.running").text()).isEqualTo("active");
    assertThat(value(board, "photonvision.service.running").level()).isEqualTo(Level.OK);
    assertThat(value(board, "photonvision.web.status").number()).isEqualTo(200);
    assertThat(value(board, "photonvision.web.status").level()).isEqualTo(Level.OK);
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest("its settings".getBytes(StandardCharsets.UTF_8)));
    assertThat(value(board, "photonvision.settings.hash").text())
        .isEqualTo(hash + "  /opt/photonvision/photonvision_config/photon.sqlite");
    // Its log is its service's journal.
    Spotter.LogPage log =
        board.log("photonvision.log", LogQuery.latest(20)).get(30, TimeUnit.SECONDS);
    assertThat(log.getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .anyMatch(message -> message.contains("PhotonVision stand-in"));
  }

  @Test
  void photonvisionsActionsAreAnsweredAsPhotonVisionAnswersThem() throws Exception {
    Board board = connected();
    Run restart = board.run("photonvision.restart").whenDone().get(30, TimeUnit.SECONDS);
    assertThat(restart.outcome()).isEqualTo(Spotter.Outcome.OUTCOME_COMPLETED);
    assertThat(response(restart, "status").number()).isEqualTo(204);
    assertThat(response(restart, "status").level()).isEqualTo(Level.OK);

    Run export = board.run("photonvision.export").whenDone().get(30, TimeUnit.SECONDS);
    assertThat(response(export, "status").number()).isEqualTo(200);
    assertThat(response(export, "status").level()).isEqualTo(Level.OK);
    assertThat(new String(export.file("body").get(30, TimeUnit.SECONDS), StandardCharsets.UTF_8))
        .isEqualTo("PK its settings");

    // A field layout, as a form's field "data" whose file is named layout.json: taken.
    String layout = "{\"tags\": [], \"field\": {\"length\": 16.54, \"width\": 8.07}}";
    Run sent = board.run("photonvision.layout", layout).whenDone().get(30, TimeUnit.SECONDS);
    assertThat(response(sent, "status").number()).isEqualTo(200);
    assertThat(response(sent, "status").level()).isEqualTo(Level.OK);
    assertThat(response(sent, "body").text())
        .isEqualTo("Successfully saved the uploaded FieldLayout, rebooting...");
    assertThat(coprocessor.run("cat", "/run/stand-in-layout"))
        .contains("name=\"data\"; filename=\"layout.json\"")
        .contains(layout);

    // The file named after the field, as before a pack could name it: refused, as PhotonVision
    // refuses it.
    assertThat(
            coprocessor.run(
                "curl",
                "-s",
                "-F",
                "data=@/etc/hostname;filename=data",
                "http://localhost:5800/api/settings/fieldLayout"))
        .isEqualTo(
            "The uploaded file was not of type 'json'. The uploaded file should be a .json file.");
  }

  private static Value response(Run run, String name) {
    return Objects.requireNonNull(run.response(name), () -> run + " has no " + name);
  }
}
