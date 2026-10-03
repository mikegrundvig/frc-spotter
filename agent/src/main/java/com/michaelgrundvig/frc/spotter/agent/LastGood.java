package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The last good start, so a pushed pack that crashes the agent as it loads, or reboots the board,
 * can't keep the board from serving and taking the next push. Before the agent loads pushed packs,
 * it leaves a marker, {@link #MARKER}; once it has been up {@link #HEALTHY} it takes it away. A
 * start that finds the marker still there knows the last start with them didn't stay up: it sets
 * them aside for this start (it serves, takes pushes, and says why in its problems), and once it
 * has been up {@link #HEALTHY} it takes the marker away, so the next start tries them again. A push
 * takes it away too: new packs get a fresh try. The marker is kept with the pushed packs, so a
 * reboot doesn't forget it.
 */
final class LastGood {
  /** The marker: there while a start with pushed packs hasn't yet stayed up. */
  static final String MARKER = "/var/lib/frc-spotter/starting";

  /** How long a start must stay up to count as good. */
  static final Duration HEALTHY = Duration.ofSeconds(60);

  private LastGood() {}

  /**
   * Whether this start may load the board's pushed packs: yes, leaving the marker, unless the last
   * start with them left it there.
   */
  static boolean begin(Host host) {
    Path marker = host.path(MARKER);
    if (Files.exists(marker)) {
      return false;
    }
    try {
      Files.writeString(
          marker,
          "The agent is starting with its pushed packs: if it doesn't stay up "
              + HEALTHY.toSeconds()
              + " s, its next start sets them aside.\n",
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      // Not writable: no push could have put packs there either. They load as they are.
      host.log("Couldn't leave " + MARKER + " (" + e + "): a start that fails isn't noticed");
    }
    return true;
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
        + ": set aside for this start, as the agent didn't stay up "
        + HEALTHY.toSeconds()
        + " s with them the last time it started; the next start tries them again, and a push"
        + " replaces them";
  }
}
