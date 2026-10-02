package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Boot;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** This boot, and what booted: the uptime, the root filesystem, and the bootloader. */
final class BootSource {
  static final String UPTIME = "/proc/uptime";
  static final String MOUNTS = "/proc/self/mountinfo";
  static final String BOOTLOADER = "/run/coprocessor/spi-uboot-version";

  private final Host host;
  private final StampSource stamp;
  private final JournalSource journal;

  BootSource(Host host, StampSource stamp, JournalSource journal) {
    this.host = host;
    this.stamp = stamp;
    this.journal = journal;
  }

  Boot read() throws IOException {
    double uptime =
        host.line(UPTIME).map(line -> Double.parseDouble(line.split("\\s+")[0])).orElse(Double.NaN);
    Root root = root();
    @Nullable Boolean clean = journal.previousBootClean();
    return new Boot(
        stamp.bootId(),
        uptime,
        host.monotonicMicros(),
        clean,
        root.device(),
        root.readOnly(),
        host.line(BOOTLOADER).orElse(""));
  }

  /** The root filesystem's device, and whether it's mounted read-only. */
  record Root(String device, boolean readOnly) {}

  /**
   * The root filesystem, from the last mount on {@code /} in mountinfo. The kernel may name its
   * source {@code /dev/root}, so the device comes from sysfs by its device number.
   */
  Root root() throws IOException {
    String found = null;
    for (String line : host.read(MOUNTS).orElse("").split("\n")) {
      String[] fields = line.split(" ");
      if (fields.length > 5 && fields[4].equals("/")) {
        found = line;
      }
    }
    if (found == null) {
      return new Root("", false);
    }
    String[] fields = found.split(" ");
    boolean readOnly = Arrays.asList(fields[5].split(",")).contains("ro");
    List<String> all = Arrays.asList(fields);
    int separator = all.indexOf("-");
    String source = separator >= 0 && separator + 2 < fields.length ? fields[separator + 2] : "";
    for (String line :
        host.read("/sys/dev/block/" + fields[2] + "/uevent").orElse("").split("\n")) {
      if (line.startsWith("DEVNAME=")) {
        return new Root("/dev/" + line.substring("DEVNAME=".length()), readOnly);
      }
    }
    return new Root(source, readOnly);
  }
}
