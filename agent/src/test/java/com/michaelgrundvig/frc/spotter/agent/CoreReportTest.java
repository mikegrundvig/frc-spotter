package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.api.ClockSync;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.NetworkLink;
import com.michaelgrundvig.frc.spotter.api.UsbDevice;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the agent reports of any Linux board, read from an RK3588's fixture tree: its network link,
 * its USB devices with their disconnects, and its clock.
 */
class CoreReportTest {
  @TempDir Path dir;
  Fixture fixture;

  @BeforeEach
  void aCoprocessor() throws IOException {
    fixture = new Fixture(dir);
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"), Fixture.lines("journal-this-boot.json"));
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), Fixture.lines("journal-previous-clean.json"));
  }

  @Test
  void eachLinkIsItsStateSpeedDropsAndErrors() {
    Health health = fixture.agent().health();
    assertThat(health.network())
        .containsExactly(
            new NetworkLink("docker0", false, "down", -1, 0, 0, 0),
            new NetworkLink("end0", true, "up", 1000, 4, 0, 2));
    assertThat(health.network().get(1).up()).isTrue();
    assertThat(health.problems()).isEmpty();
  }

  @Test
  void aLinkThatCantSayItsSpeedHasNone() throws IOException {
    fixture.write("/sys/class/net/end0/speed", "-1\n");
    fixture.write("/sys/class/net/end0/carrier_changes", "lots\n");
    NetworkLink link = new NetworkSource(fixture.host).read().get(1);
    assertThat(link.speedMbps()).isEqualTo(-1);
    assertThat(link.carrierChanges()).isZero();
    // A folder that isn't an interface is passed over.
    fixture.write("/sys/class/net/bonding_masters/x", "");
    assertThat(new NetworkSource(fixture.host).read()).hasSize(2);
  }

  @Test
  void everyUsbDeviceIsByItsPortWithItsDisconnects() {
    Health health = fixture.agent().health();
    // The journal says 7-1 was unplugged once this boot (and is back); root hubs and interfaces
    // aren't devices.
    assertThat(health.usb())
        .containsExactly(
            new UsbDevice("3-1", true, "0c45", "6366", "Arducam OV9281 USB Camera", 480, 0),
            new UsbDevice("7-1", true, "0c45", "6366", "Arducam OV9281 USB Camera", 5000, 1));
  }

  @Test
  void aPortItsDeviceLeftIsReportedEmpty() throws IOException {
    List<UsbDevice> devices =
        new UsbSource(fixture.host).read(Map.of("7-1", 2, "1-1.4", 3, "10-2", 1));
    assertThat(devices)
        .extracting(UsbDevice::port, UsbDevice::present, UsbDevice::disconnects)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("1-1.4", false, 3),
            org.assertj.core.groups.Tuple.tuple("3-1", true, 0),
            org.assertj.core.groups.Tuple.tuple("7-1", true, 2),
            org.assertj.core.groups.Tuple.tuple("10-2", false, 1));
  }

  @Test
  void portsAreInTheOrderAPersonReadsThem() {
    List<String> ports =
        new java.util.ArrayList<>(List.of("10-1", "2-1.10", "2-1.2", "2-1", "x-1"));
    ports.sort(UsbSource.PORTS);
    assertThat(ports).containsExactly("2-1", "2-1.2", "2-1.10", "10-1", "x-1");
  }

  @Test
  void disconnectsAreCountedFromTheKernelsLinesOnly() throws IOException {
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"),
        List.of(
            entry("kernel", "usb 7-1: USB disconnect, device number 2"),
            entry("kernel", "usb 7-1: USB disconnect, device number 3"),
            entry("kernel", "usb 3-1.2: USB disconnect, device number 4"),
            entry("kernel", "usb usb7: USB disconnect, device number 1"),
            entry("someone", "usb 7-1: USB disconnect, device number 9")));
    JournalSource journal = new JournalSource(fixture.host, Duration.ofSeconds(1));
    journal.summary("");
    assertThat(journal.disconnects()).containsOnly(Map.entry("7-1", 2), Map.entry("3-1.2", 1));
  }

  private static String entry(String identifier, String message) {
    return "{\"__CURSOR\":\"c\",\"_TRANSPORT\":\""
        + (identifier.equals("kernel") ? "kernel" : "syslog")
        + "\",\"SYSLOG_IDENTIFIER\":\""
        + identifier
        + "\",\"PRIORITY\":\"3\",\"MESSAGE\":\""
        + message
        + "\"}";
  }

  @Test
  void theClockIsSyncedWithTimesyncdsOffset() {
    ClockSync clock = fixture.agent().health().clock();
    assertThat(clock).isEqualTo(new ClockSync(true, "systemd-timesyncd", -1.523));
  }

  @Test
  void chronysOffsetComesFirst() throws IOException {
    fixture.commands.answer(
        List.of("chronyc", "-c", "tracking"),
        List.of(
            "0A0C2202,10.12.34.2,3,1790000000.123456789,0.000412500,-0.000123,0.000200,-12.345,"
                + "-0.001,0.012,0.001234,0.000567,64.2,Normal"));
    fixture.commands.answer(List.of("timedatectl", "show"), List.of("no"));
    assertThat(new ClockSource(fixture.host, Duration.ofSeconds(1)).read())
        .isEqualTo(new ClockSync(false, "chrony", 0.4125));
  }

  @Test
  void withNeitherDaemonTheOffsetIsUnknown() throws IOException {
    fixture.commands.answer(
        List.of("chronyc", "-c", "tracking"), new Commands.Output(1, List.of(), false, false));
    fixture.commands.answer(
        List.of("timedatectl", "timesync-status"), new Commands.Output(1, List.of(), false, false));
    fixture.commands.answer(List.of("timedatectl", "show"), List.of("maybe"));
    ClockSync clock = new ClockSource(fixture.host, Duration.ofSeconds(1)).read();
    assertThat(clock.synced()).isNull();
    assertThat(clock.source()).isEmpty();
    assertThat(clock.offsetMillis()).isNaN();
  }

  @Test
  void aClockThatCantBeReadIsAProblemAndReadingsAreKeptTenSeconds() throws IOException {
    ClockSource clock = new ClockSource(fixture.host, Duration.ofSeconds(1));
    ClockSync first = clock.read();
    fixture.commands.answer(
        List.of("timedatectl", "show"), new Commands.Output(1, List.of(), false, false));
    assertThat(clock.read()).isSameAs(first);
    fixture.micros.addAndGet(ClockSource.EVERY_MICROS);
    assertThatThrownBy(clock::read).hasMessage("timedatectl failed");
    Health health = fixture.agent().health();
    assertThat(health.problems()).containsExactly("clock: timedatectl failed");
    assertThat(health.clock()).isEqualTo(ClockSync.UNKNOWN);
  }

  @Test
  void timedatectlsSpansAreRead() {
    assertThat(ClockSource.span("+305us")).isEqualTo(0.305);
    assertThat(ClockSource.span("-1.523ms")).isEqualTo(-1.523);
    assertThat(ClockSource.span("+2.5s")).isEqualTo(2500);
    assertThat(ClockSource.span("-1min 2.5s")).isEqualTo(-62_500);
    assertThat(ClockSource.span("1h")).isEqualTo(3_600_000);
    assertThat(ClockSource.span("2d")).isEqualTo(172_800_000);
    assertThat(ClockSource.span("n/a")).isNaN();
    assertThat(ClockSource.span("5ms later")).isNaN();
    assertThat(ClockSource.span("")).isNaN();
  }
}
