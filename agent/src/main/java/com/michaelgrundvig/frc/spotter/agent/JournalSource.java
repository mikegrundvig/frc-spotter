package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.JournalEntry;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.JournalSummary;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The journal, through {@code journalctl -o json}: pages of it, this boot's trouble counted, and
 * whether the boot before ended cleanly. Every read is bounded: a page by its entries, a count by
 * how much it reads each time (it picks up where it left off, by cursor, the next time).
 */
final class JournalSource {
  /** The fields read: journalctl always adds the cursor, the timestamps, and the boot. */
  static final String FIELDS =
      "--output-fields=MESSAGE,PRIORITY,_SYSTEMD_UNIT,SYSLOG_IDENTIFIER,_TRANSPORT";

  /** The most entries one count reads; the rest wait for the next. */
  static final int SCAN_LINES = 2000;

  /** How many of the latest counted entries a summary keeps. */
  static final int LATEST = 3;

  /** The longest a summary's entry's message is: a page of the journal has them whole. */
  static final int SUMMARY_MESSAGE = 200;

  /** A unit's name, as the agent serves a unit's entries by. */
  static final Pattern UNIT = Pattern.compile("[A-Za-z0-9:_.\\\\@-]{1,200}\\.[a-z]+");

  /** A cursor a request may give: journald's own characters only. */
  static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9=;_+/.:-]{1,512}");

  private final Host host;
  private final Duration timeout;

  // The count so far, this boot.
  private String countedCursor = "";
  private final Map<String, Integer> counts = new LinkedHashMap<>();
  private final Deque<JournalEntry> latest = new ArrayDeque<>();
  private boolean previousBootRead;
  private @Nullable Boolean previousBootClean;

  JournalSource(Host host, Duration timeout) {
    this.host = host;
    this.timeout = timeout;
  }

  /**
   * This boot's trouble, counted: reads on from where the last count stopped. The kernel's messages
   * and anything at priority 3 (error) or worse are counted, each by {@link #category}.
   */
  synchronized JournalSummary summary(String bootId) throws IOException {
    List<String> command =
        new ArrayList<>(List.of("journalctl", "-b", "-o", "json", "--no-pager", FIELDS));
    if (!countedCursor.isEmpty()) {
      command.add("--after-cursor=" + countedCursor);
    }
    // The kernel's messages, or (+) priorities 0 to 3 from anyone.
    command.addAll(
        List.of("_TRANSPORT=kernel", "+", "PRIORITY=0", "PRIORITY=1", "PRIORITY=2", "PRIORITY=3"));
    Commands.Output output =
        host.commands().run(command, timeout, SCAN_LINES, host.limits().maxRead());
    if (!output.ok()) {
      throw new IOException("journalctl " + (output.timedOut() ? "timed out" : "failed"));
    }
    List<String> lines = output.lines();
    if (output.truncated() && !lines.isEmpty()) {
      lines = lines.subList(0, lines.size() - 1); // the last may be cut off
    }
    for (String line : lines) {
      JournalEntry entry = entry(line);
      if (entry == null) {
        continue;
      }
      countedCursor = entry.cursor();
      if (!bootId.isEmpty()
          && !entry.bootId().isEmpty()
          && !entry.bootId().replace("-", "").equals(bootId.replace("-", ""))) {
        continue;
      }
      if (entry.category().isEmpty()) {
        continue;
      }
      counts.merge(entry.category(), 1, Integer::sum);
      latest.addLast(brief(entry));
      while (latest.size() > LATEST) {
        latest.removeFirst();
      }
    }
    Map<String, Integer> all = new LinkedHashMap<>();
    for (String category : JournalSummary.CATEGORIES) {
      all.put(category, counts.getOrDefault(category, 0));
    }
    return new JournalSummary(all, List.copyOf(latest));
  }

  /**
   * journalctl's matches for these units, or-ed together ({@code +}): the kernel's transport, and
   * for a unit both what it logged and what systemd logged about it.
   */
  static List<String> matches(List<String> units) {
    List<String> matches = new ArrayList<>();
    for (String unit : units) {
      if (!unit.equals("kernel") && !UNIT.matcher(unit).matches()) {
        throw new IllegalArgumentException("not a unit's name: " + unit);
      }
      if (!matches.isEmpty()) {
        matches.add("+");
      }
      if (unit.equals("kernel")) {
        matches.add("_TRANSPORT=kernel");
      } else {
        matches.addAll(List.of("_SYSTEMD_UNIT=" + unit, "+", "UNIT=" + unit));
      }
    }
    return matches;
  }

  /** An entry as a summary keeps it: its message cut short, and its boot (this one) left out. */
  private static JournalEntry brief(JournalEntry entry) {
    String message = entry.message();
    return new JournalEntry(
        entry.cursor(),
        entry.realtimeMicros(),
        entry.monotonicMicros(),
        "",
        entry.priority(),
        entry.unit(),
        entry.identifier(),
        message.length() > SUMMARY_MESSAGE ? message.substring(0, SUMMARY_MESSAGE) : message,
        entry.category());
  }

  /** Where a page of the journal is. */
  record Position(Kind kind, String cursor) {
    /** Where a page is, against a cursor or none. */
    enum Kind {
      /** The latest entries. */
      LATEST,
      /** The entries after the cursor. */
      AFTER,
      /** The entries before the cursor, the newest of them. */
      BEFORE,
      /** The first entries of this boot. */
      BOOT
    }

    static final Position LATEST = new Position(Kind.LATEST, "");
    static final Position BOOT = new Position(Kind.BOOT, "");

    static Position after(String cursor) {
      return new Position(Kind.AFTER, cursor);
    }

    static Position before(String cursor) {
      return new Position(Kind.BEFORE, cursor);
    }
  }

  /**
   * A page of the journal, as {@code GET /v1/journal} asks: the latest entries, those after or
   * before a cursor, or the first of this boot; at a priority or worse, of some of the units it
   * serves (the caller checks which); at most {@code limit} entries, oldest first. Its cursor is
   * the one to ask with to go on the same way: the newest entry's for later pages, the oldest's for
   * earlier ones ({@code before}).
   */
  JournalPage page(Position position, int priority, List<String> units, int limit)
      throws IOException {
    List<String> command =
        new ArrayList<>(List.of("journalctl", "-o", "json", "--no-pager", FIELDS));
    if (priority >= 0) {
      command.add("--priority=" + priority);
    }
    switch (position.kind()) {
      case LATEST -> command.add("--lines=" + limit);
      case AFTER -> command.add("--after-cursor=" + position.cursor());
      // Newest first, from just before the cursor: read limit of them, and turned around below.
      case BEFORE -> command.addAll(List.of("--reverse", "--after-cursor=" + position.cursor()));
      case BOOT -> command.add("--boot");
    }
    command.addAll(matches(units));
    Commands.Output output =
        host.commands().run(command, timeout, limit + 1, host.limits().maxRead());
    if (!output.ok()) {
      throw new IOException("journalctl " + (output.timedOut() ? "timed out" : "failed"));
    }
    List<JournalEntry> entries = new ArrayList<>();
    boolean more = false;
    for (String line : output.lines()) {
      JournalEntry entry = entry(line);
      if (entry == null) {
        continue;
      }
      if (entries.size() == limit) {
        more = true;
        break;
      }
      entries.add(entry);
    }
    more |= output.truncated();
    String next;
    if (position.kind() == Position.Kind.BEFORE) {
      Collections.reverse(entries);
      next = entries.isEmpty() ? position.cursor() : entries.get(0).cursor();
    } else {
      next = entries.isEmpty() ? position.cursor() : entries.get(entries.size() - 1).cursor();
    }
    return new JournalPage(entries, next, more);
  }

  /**
   * Whether the boot before this one shut down cleanly: its journal ends with journald stopping, or
   * systemd reaching its power-off, reboot, or shutdown target. Null when that boot left no
   * journal. Read once; it can't change. A power cut leaves neither, which is the point; that a
   * clean shutdown always leaves one on these boards is unverified.
   */
  synchronized @Nullable Boolean previousBootClean() throws IOException {
    if (previousBootRead) {
      return previousBootClean;
    }
    Commands.Output output =
        host.commands()
            .run(
                List.of("journalctl", "-b", "-1", "-n", "40", "-o", "json", "--no-pager", FIELDS),
                timeout,
                41,
                host.limits().maxRead());
    if (output.timedOut()) {
      throw new IOException("journalctl timed out");
    }
    Boolean clean = null;
    if (output.exit() == 0 && !output.lines().isEmpty()) {
      clean = false;
      for (String line : output.lines()) {
        JournalEntry entry = entry(line);
        if (entry != null && endsABoot(entry)) {
          clean = true;
        }
      }
    }
    previousBootClean = clean;
    previousBootRead = true;
    return clean;
  }

  private static boolean endsABoot(JournalEntry entry) {
    String message = entry.message();
    return (entry.identifier().equals("systemd-journald") && message.startsWith("Journal stopped"))
        || message.startsWith("Reached target System Power Off")
        || message.startsWith("Reached target System Reboot")
        || message.startsWith("Reached target System Shutdown")
        || message.startsWith("Reached target Power-Off")
        || message.startsWith("Reached target Shutdown");
  }

  /** One line of {@code journalctl -o json}, or null if it isn't one. */
  static @Nullable JournalEntry entry(String line) {
    JsonValue.Obj json;
    try {
      json = Json.parse(line).asObject("journal entry");
    } catch (JsonException e) {
      return null;
    }
    String message = text(json.get("MESSAGE"));
    if (message.length() > AgentApi.MAX_MESSAGE) {
      message = message.substring(0, AgentApi.MAX_MESSAGE);
    }
    String identifier = text(json.get("SYSLOG_IDENTIFIER"));
    String transport = text(json.get("_TRANSPORT"));
    int priority = (int) number(json.get("PRIORITY"), 6);
    return new JournalEntry(
        text(json.get("__CURSOR")),
        number(json.get("__REALTIME_TIMESTAMP"), 0),
        number(json.get("__MONOTONIC_TIMESTAMP"), 0),
        text(json.get("_BOOT_ID")),
        priority,
        text(json.get("_SYSTEMD_UNIT")),
        identifier,
        message,
        category(transport.equals("kernel") || identifier.equals("kernel"), priority, message));
  }

  /**
   * What an entry is about, or empty: {@code oom}, {@code thermal}, {@code uvc}, {@code usb},
   * {@code filesystem} (each from the kernel), or {@code error} for anything else at priority 3 or
   * worse. A USB device simply connecting isn't counted; its disconnects, resets, and errors are.
   */
  static String category(boolean kernel, int priority, String message) {
    String text = message.toLowerCase(Locale.ROOT);
    if (kernel) {
      if (text.contains("out of memory")
          || text.contains("oom-kill")
          || text.contains("oom_reaper")
          || text.contains("killed process")) {
        return "oom";
      }
      if (text.contains("thermal")
          || text.contains("overheat")
          || text.contains("throttl")
          || text.contains("critical temperature")) {
        return "thermal";
      }
      if (text.contains("uvcvideo") || text.startsWith("uvc")) {
        return trouble(text) || priority <= 4 ? "uvc" : "";
      }
      if (text.startsWith("usb ")
          || text.contains("xhci")
          || text.contains("ehci")
          || text.contains("ohci")
          || text.startsWith("hub ")) {
        return trouble(text) || priority <= 4 ? "usb" : "";
      }
      if (text.contains("ext4-fs")
          || text.contains("i/o error")
          || text.contains("blk_update_request")
          || text.contains("nvme") && trouble(text)
          || text.contains("mmc") && trouble(text)
          || text.contains("remount") && text.contains("read-only")) {
        return "filesystem";
      }
    }
    return priority <= 3 ? "error" : "";
  }

  private static boolean trouble(String text) {
    return text.contains("disconnect")
        || text.contains("reset")
        || text.contains("error")
        || text.contains("fail")
        || text.contains("unable")
        || text.contains("over-current")
        || text.contains("timeout")
        || text.contains("timed out")
        || text.contains("cannot")
        || text.contains("not accepting");
  }

  /** A journal field's text: a string, or (for binary messages) an array of bytes. */
  private static String text(@Nullable JsonValue value) {
    if (value instanceof JsonValue.Str string) {
      return string.value();
    }
    if (value instanceof JsonValue.Arr array) {
      if (!array.items().isEmpty() && array.items().get(0) instanceof JsonValue.Str first) {
        return first.value(); // a field given more than once: the first
      }
      byte[] bytes = new byte[array.items().size()];
      for (int i = 0; i < bytes.length; i++) {
        bytes[i] = (byte) array.items().get(i).asLong("MESSAGE[]");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return "";
  }

  private static long number(@Nullable JsonValue value, long fallback) {
    try {
      return Long.parseLong(text(value));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
