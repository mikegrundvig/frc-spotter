package com.michaelgrundvig.frc.spotter.manager;

/**
 * The names of what the catalog's packs (the repository's {@code packs/}) give a board, for robot
 * code to read them by: {@code board.value(CatalogPacks.Debian.CPU_BUSIEST)}. Spotter owns these
 * packs, so it owns their names: a renamed field here is a compile error in robot code, not a value
 * that quietly reads as unavailable. Each is a value's id, {@code pack.collector.field}, or a
 * log's, {@code pack.log}; the catalog's tests check each against its pack.
 */
public final class CatalogPacks {
  private CatalogPacks() {}

  /** The {@code debian} pack: a Debian-family system's own health, and its journal. */
  public static final class Debian {
    private Debian() {}

    /** The busiest core's use, in %. */
    public static final String CPU_BUSIEST = "debian.cpu.busiest";

    /** Whether the CPU is slowed for heat or power. */
    public static final String CPU_CAPPED = "debian.cpu.capped";

    /** The hottest thermal zone, in °C. */
    public static final String THERMAL_HOTTEST = "debian.thermal.hottest";

    /** The margin to the nearest trip point, in °C. */
    public static final String THERMAL_MARGIN = "debian.thermal.margin";

    /** The memory available, in %. */
    public static final String MEMORY_AVAILABLE = "debian.memory.available";

    /** The root disk's free space, in %. */
    public static final String DISK_ROOT_FREE = "debian.disk.root-free";

    /** Whether the Ethernet link is up. */
    public static final String NETWORK_UP = "debian.network.up";

    /** The link's speed, in Mb/s. */
    public static final String NETWORK_SPEED = "debian.network.speed";

    /** How often the link dropped since boot. */
    public static final String NETWORK_DROPS = "debian.network.drops";

    /** The USB devices plugged in, each by its port and product. */
    public static final String USB_DEVICES = "debian.usb.devices";

    /** How often a USB device was unplugged since boot. */
    public static final String USB_DISCONNECTS = "debian.usb.disconnects";

    /** Whether the clock is synchronized. */
    public static final String CLOCK_SYNCED = "debian.clock.synced";

    /** The clock's offset, in seconds. */
    public static final String CLOCK_OFFSET = "debian.clock.offset";

    /** The drive's wear, in %. */
    public static final String DRIVE_WEAR = "debian.drive.wear";

    /** How many unsafe shutdowns the drive has seen. */
    public static final String DRIVE_UNSAFE_SHUTDOWNS = "debian.drive.unsafe-shutdowns";

    /** How long the board has been up, in seconds. */
    public static final String UPTIME_SECONDS = "debian.uptime.seconds";

    /** The system journal: a log. */
    public static final String JOURNAL = "debian.journal";
  }

  /** The {@code raspberry-pi} pack: a Raspberry Pi's power. */
  public static final class RaspberryPi {
    private RaspberryPi() {}

    /** How many under-voltage episodes since boot. */
    public static final String POWER_EPISODES = "raspberry-pi.power.episodes";

    /**
     * The power's status: ok with none since boot, failing with any, saying when the latest was.
     */
    public static final String POWER_SUMMARY = "raspberry-pi.power.summary";
  }
}
