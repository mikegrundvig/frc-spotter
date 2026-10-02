package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.probes.Pack;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spotter's catalog of packs ({@code packs/}), read as the agent reads them, so the catalog can't
 * drift from the format: every file a pack, headed by what it checks and where to copy it, and all
 * of them copied onto one computer together without a clash.
 */
class CatalogTest {
  @TempDir Path dir;

  static List<Path> catalog() throws IOException {
    try (Stream<Path> files = Files.list(Fixture.PROJECT.resolve("packs"))) {
      return files.filter(file -> file.toString().endsWith(Pack.SUFFIX)).sorted().toList();
    }
  }

  @Test
  void theCatalogHasItsPacks() throws IOException {
    assertThat(catalog())
        .extracting(file -> file.getFileName().toString())
        .contains(
            "health.yaml",
            "my-program.yaml",
            "orangepi-rk3588.yaml",
            "photonvision.yaml",
            "raspberry-pi.yaml");
  }

  @Test
  void everyPackIsReadByTheAgentsOwnReaderAndSaysWhereItGoes() throws IOException {
    for (Path file : catalog()) {
      String name = file.getFileName().toString();
      String yaml = Files.readString(file, StandardCharsets.UTF_8);
      Pack pack = Pack.parseYaml(yaml, "packs/" + name);
      assertThat(name).isEqualTo(pack.name() + Pack.SUFFIX);
      assertThat(pack.probes()).as(name).isNotEmpty();
      assertThat(pack.probes()).as(name).allMatch(probe -> probe.everySeconds() > 0);
      assertThat(yaml).as(name).startsWith("# ");
      String header = yaml.substring(0, yaml.indexOf("\npack:"));
      assertThat(header).as(name).contains(Pack.DIRECTORY + "/" + name);
    }
  }

  @Test
  void theWholeCatalogRunsTogetherOnOneComputer() throws IOException {
    Fixture fixture = new Fixture(dir);
    fixture.commands.answer(List.of("journalctl"), List.of());
    for (Path file : catalog()) {
      String name = file.getFileName().toString();
      fixture.pack(
          name.substring(0, name.length() - Pack.SUFFIX.length()),
          Files.readString(file, StandardCharsets.UTF_8));
    }
    Configuration configuration = Configuration.read(fixture.host);
    assertThat(configuration.problems()).isEmpty();
    assertThat(configuration.probes().packs()).hasSize(catalog().size());
  }

  @Test
  void healthHoldsAnyBoardsOwnMeasurementsToLimits() throws IOException {
    Fixture fixture = new Fixture(dir);
    fixture.commands.answer(List.of("journalctl"), List.of());
    fixture.pack(
        "health",
        Files.readString(Fixture.PROJECT.resolve("packs/health.yaml"), StandardCharsets.UTF_8));
    Agent agent = fixture.agent();
    agent.probes().runAll();
    fixture.micros.addAndGet(1_000_000);
    List<ProbeResult> results = agent.health().probes();
    assertThat(results)
        .extracting(ProbeResult::id, ProbeResult::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("health.thermal-margin", ProbeResult.PASS),
            // The fixture's big cores are held to 2016 of 2400 MHz.
            org.assertj.core.groups.Tuple.tuple("health.cpu-capped", ProbeResult.FAIL),
            org.assertj.core.groups.Tuple.tuple("health.memory", ProbeResult.PASS),
            // The fixture's root is a folder on the computer running the tests: some disk's space.
            org.assertj.core.groups.Tuple.tuple(
                "health.root-free",
                results.get(3).status().equals(ProbeResult.FAIL)
                    ? ProbeResult.FAIL
                    : ProbeResult.PASS));
    assertThat(results.get(0).value()).isEqualTo("12.2");
  }

  /** The Raspberry Pi pack, as the agent reads it. */
  static Pack raspberryPi() throws IOException {
    return Pack.parseYaml(
        Files.readString(
            Fixture.PROJECT.resolve("packs/raspberry-pi.yaml"), StandardCharsets.UTF_8),
        "raspberry-pi.yaml");
  }

  /**
   * The agent's probe runner, its programs run for real as the agent runs them, but against
   * fixtures: {@code bin} first on their PATH (a stand-in journalctl), and {@code hwmon} in place
   * of {@code /sys/class/hwmon}.
   */
  static ProbeRunner runner(ProcessCommands real, Path dir, Path bin, Path hwmon) {
    Commands commands =
        (command, timeout, maxLines, maxBytes) -> {
          List<String> argv =
              new ArrayList<>(List.of("env", "PATH=" + bin + ":" + System.getenv("PATH")));
          command.forEach(arg -> argv.add(arg.replace("/sys/class/hwmon", hwmon.toString())));
          return real.run(argv, timeout, maxLines, maxBytes);
        };
    Host host =
        new Host(
            dir,
            Limits.DEFAULT,
            false,
            commands,
            path -> path,
            path -> new Host.Owner(Configuration.ROOT, 0644),
            List::of,
            () -> 0L,
            message -> {});
    return new ProbeRunner(host, Map::of);
  }

  /**
   * A Raspberry Pi's under-voltage episodes since boot, counted from the kernel's log as a stand-in
   * journalctl gives it: each "Undervoltage detected!" the hwmon driver logs, the latest's time
   * after boot with them.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aRaspberryPisUnderVoltageIsCountedSinceBootFromTheKernelsLog() throws IOException {
    Probe sinceBoot = raspberryPi().probes().get(0);
    assertThat(sinceBoot.id()).isEqualTo("raspberry-pi.under-voltage-since-boot");
    Path bin = dir.resolve("bin");
    Path log = dir.resolve("kernel.log");
    write(
        bin.resolve("journalctl"),
        "#!/bin/sh\n"
            + "[ \"$*\" = \"-k -b -q --no-pager -o short-monotonic\" ]"
            + " || { echo \"journalctl: asked $*\" >&2; exit 3; }\n"
            + "[ -e '"
            + log
            + "' ] || { echo 'No journal files were found.' >&2; exit 1; }\n"
            + "cat '"
            + log
            + "'\n");
    Files.setPosixFilePermissions(
        bin.resolve("journalctl"), PosixFilePermissions.fromString("rwxr-xr-x"));
    try (ProcessCommands real = new ProcessCommands()) {
      ProbeRunner runner = runner(real, dir, bin, dir.resolve("hwmon"));
      write(
          log,
          "[    2.000000] pi kernel: Booting Linux on physical CPU 0x0000000000\n"
              + "[   12.300000] pi kernel: usb 1-1: new high-speed USB device number 2\n");
      ProbeResult none = runner.run(sinceBoot);
      assertThat(none.status()).as("%s", none).isEqualTo(ProbeResult.PASS);
      assertThat(none.value()).isEqualTo("0");

      Files.writeString(
          log,
          "[  301.250000] pi kernel: hwmon hwmon1: Undervoltage detected!\n"
              + "[  303.250000] pi kernel: hwmon hwmon1: Voltage normalised\n"
              + "[ 1234.560000] pi kernel: hwmon hwmon1: Undervoltage detected!\n",
          StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.APPEND);
      ProbeResult twice = runner.run(sinceBoot);
      assertThat(twice.status()).isEqualTo(ProbeResult.FAIL);
      assertThat(twice.value()).isEqualTo("2, latest 1234.6 s after boot");
      assertThat(twice.detail()).isEqualTo("exit 1, not 0: under-voltage since boot");

      Files.delete(log);
      ProbeResult unread = runner.run(sinceBoot);
      assertThat(unread.status()).isEqualTo(ProbeResult.FAIL);
      // Why journalctl failed comes first, in its own words.
      assertThat(unread.detail()).isEqualTo("exit 2, not 0: No journal files were found.");
    }
  }

  /**
   * A Raspberry Pi's under-voltage now: the hwmon named rpi_volt found whatever its number, its
   * alarm read, against a tree of hwmons in place of {@code /sys/class/hwmon}.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aRaspberryPisUnderVoltageNowIsFoundByItsHwmonsName() throws IOException {
    Probe now = raspberryPi().probes().get(1);
    assertThat(now.id()).isEqualTo("raspberry-pi.under-voltage-now");
    Path hwmon = dir.resolve("hwmon");
    write(hwmon.resolve("hwmon0/name"), "cpu_thermal\n");
    write(hwmon.resolve("hwmon3/name"), "rpi_volt\n");
    write(hwmon.resolve("hwmon3/in0_lcrit_alarm"), "0\n");
    try (ProcessCommands real = new ProcessCommands()) {
      ProbeRunner runner = runner(real, dir, dir.resolve("bin"), hwmon);
      ProbeResult fine = runner.run(now);
      assertThat(fine.status()).as("%s", fine).isEqualTo(ProbeResult.PASS);
      assertThat(fine.value()).isEqualTo("0");

      write(hwmon.resolve("hwmon3/in0_lcrit_alarm"), "1\n");
      ProbeResult low = runner.run(now);
      assertThat(low.status()).isEqualTo(ProbeResult.FAIL);
      assertThat(low.value()).isEqualTo("1");
      assertThat(low.detail())
          .isEqualTo(
              "exit 1, not 0: under-voltage: the supply dropped below the threshold in the last"
                  + " 2 s");

      Files.delete(hwmon.resolve("hwmon3/in0_lcrit_alarm"));
      Files.delete(hwmon.resolve("hwmon3/name"));
      ProbeResult none = runner.run(now);
      assertThat(none.status()).isEqualTo(ProbeResult.FAIL);
      assertThat(none.detail()).contains("no rpi_volt hwmon");
    }
  }

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }
}
