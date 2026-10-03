package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The last good start, so a pushed pack that crashes the agent, or reboots the board, can't keep
 * the board from serving and taking the next push. Before the agent loads pushed packs, it leaves a
 * marker, {@link #MARKER}; once it has been up {@link #HEALTHY} it takes it away. A start that
 * finds the marker still there knows a start with those packs didn't stay up, and sets them aside
 * for itself (it serves, takes pushes, and says why in its problems); once it has been up {@link
 * #HEALTHY} it takes the marker away, so the next start tries them again. A push takes it away too:
 * new packs get a fresh try.
 *
 * <p>What counts, so that ordinary use doesn't: a start in the same boot counts the last one only
 * if the agent didn't stop cleanly (it crashed, or was killed out of memory), as a restart for a
 * change (systemctl restart) stops it cleanly and says so in the marker. A start after a reboot or
 * a power cut counts only the second boot in a row whose start didn't stay up: robots are switched
 * off within a minute often enough, and a pack that reboots the board does it every boot. The
 * marker is kept with the pushed packs, so a reboot doesn't forget it.
 */
final class LastGood {
  /** The marker: there while a start with pushed packs hasn't yet stayed up. */
  static final String MARKER = "/var/lib/frc-spotter/starting";

  /** How long a start must stay up to count as good. */
  static final Duration HEALTHY = Duration.ofSeconds(60);

  /** How many boots in a row may start with the pushed packs and not stay up. */
  static final int BOOTS = 2;

  private LastGood() {}

  /**
   * The marker: the boot its start was in, how many boots in a row a start with the pushed packs
   * hasn't stayed up, and whether its start stopped cleanly.
   */
  private record Marker(String boot, long boots, boolean stopped) {
    String json() {
      Map<String, Object> members = new LinkedHashMap<>();
      members.put("boot", boot);
      members.put("boots", boots);
      members.put("stopped", stopped);
      return JsonText.write(members);
    }

    static Optional<Marker> read(Host host) {
      try {
        Optional<String> text = host.read(MARKER);
        if (text.isEmpty()) {
          return Optional.empty();
        }
        Map<String, Object> members = JsonText.object(text.get());
        Object boot = members.get("boot");
        Object boots = members.get("boots");
        Object stopped = members.get("stopped");
        return Optional.of(
            new Marker(
                boot instanceof String ? (String) boot : "",
                boots instanceof Long ? (Long) boots : 1,
                Boolean.TRUE.equals(stopped)));
      } catch (IOException | RuntimeException e) {
        // Unreadable: a start that didn't finish writing it; counted as one that didn't stay up.
        return Optional.of(new Marker("", BOOTS, false));
      }
    }

    void write(Host host) {
      try {
        Files.writeString(host.path(MARKER), json() + "\n", StandardCharsets.UTF_8);
      } catch (IOException e) {
        // Not writable: no push could have put packs there either. They load as they are.
        host.log("Couldn't leave " + MARKER + " (" + e + "): a start that fails isn't noticed");
      }
    }
  }

  private static String boot(Host host) {
    try {
      return host.line(IdentitySource.BOOT_ID).orElse("");
    } catch (IOException e) {
      return "";
    }
  }

  /**
   * Whether this start may load the board's pushed packs: yes, leaving the marker, unless the last
   * start with them didn't stay up (as counted above), when it sets them aside.
   */
  static boolean begin(Host host) {
    String boot = boot(host);
    Optional<Marker> last = Marker.read(host);
    long boots = 1;
    if (last.isPresent()) {
      Marker marker = last.get();
      boolean sameBoot = !boot.isEmpty() && boot.equals(marker.boot());
      if (sameBoot && !marker.stopped()) {
        return false;
      }
      boots = sameBoot ? marker.boots() : marker.boots() + 1;
      if (boots > BOOTS) {
        return false;
      }
    }
    new Marker(boot, boots, false).write(host);
    return true;
  }

  /**
   * The agent stopped cleanly (its shutdown hook's): a start in this boot doesn't count this one as
   * one that didn't stay up.
   */
  static void stopped(Host host) {
    Optional<Marker> marker = Marker.read(host);
    if (marker.isPresent() && Files.exists(host.path(MARKER))) {
      new Marker(marker.get().boot(), marker.get().boots(), true).write(host);
    }
  }

  /** This start stayed up: the next one may load the pushed packs. */
  static void healthy(Host host) {
    forget(host, "it stayed up");
  }

  /** New packs were pushed: the next start tries them. */
  static void pushed(Host host) {
    forget(host, "packs were pushed");
  }

  private static void forget(Host host, String why) {
    try {
      Files.deleteIfExists(host.path(MARKER));
    } catch (IOException e) {
      host.log("Couldn't remove " + MARKER + " as " + why + ": " + e);
    }
  }

  /** Why the pushed packs were set aside, as the board's problems say it. */
  static String setAside() {
    return Packs.PUSHED
        + ": set aside for this start, as the last start with them didn't stay up "
        + HEALTHY.toSeconds()
        + " s (the agent crashed or was killed, or the board went down in two boots in a row);"
        + " the next start tries them again, and a push replaces them";
  }
}
