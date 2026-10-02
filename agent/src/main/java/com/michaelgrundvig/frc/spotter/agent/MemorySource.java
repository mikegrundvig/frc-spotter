package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Memory;
import java.io.IOException;

/** The computer's memory, from {@code /proc/meminfo}: all of it, and what's available. */
final class MemorySource {
  static final String MEMINFO = "/proc/meminfo";

  private final Host host;

  MemorySource(Host host) {
    this.host = host;
  }

  Memory read() throws IOException {
    String text = host.read(MEMINFO).orElseThrow(() -> new IOException(MEMINFO + " isn't there"));
    long total = -1;
    long available = -1;
    for (String line : text.split("\n")) {
      String[] fields = line.trim().split("\\s+");
      if (fields.length >= 2 && fields[0].equals("MemTotal:")) {
        total = kilobytes(fields[1]);
      } else if (fields.length >= 2 && fields[0].equals("MemAvailable:")) {
        available = kilobytes(fields[1]);
      }
    }
    if (total < 0 || available < 0) {
      throw new IOException(MEMINFO + " has no MemTotal or MemAvailable");
    }
    return new Memory(total / 1024, available / 1024);
  }

  private static long kilobytes(String text) throws IOException {
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw new IOException(MEMINFO + ": not a number of kB: " + text, e);
    }
  }
}
