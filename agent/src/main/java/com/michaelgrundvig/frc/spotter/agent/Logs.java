package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Pages a pack's logs. What's asked for reaches the log's command as data, in its environment,
 * never on its command line: {@code SPOTTER_FROM} ({@code latest}, {@code before} or {@code
 * after}), {@code SPOTTER_CURSOR}, {@code SPOTTER_LIMIT} and {@code SPOTTER_LEVEL}. The command
 * uses them as it likes, and prints its entries as JSON lines, oldest first; the agent hands them
 * back, at most the limit, with cursors to page further on either side.
 */
final class Logs {
  /** How many logs are paged at once; another asked meanwhile is refused as busy. */
  static final int AT_ONCE = 2;

  /** How long a log's command may take. */
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** The most of a log command's output read: a thousand long entries. */
  static final int MAX_OUTPUT = 4 * 1024 * 1024;

  /** A page's entries unless asked, and at most. */
  static final int DEFAULT_LIMIT = 100;

  static final int MAX_LIMIT = 1000;

  /** The longest cursor taken: a journal's is about 100 characters. */
  static final int MAX_CURSOR = 1024;

  /** A request that can't be answered as asked, with its status. */
  static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;

    Refused(int status, String reason) {
      super(reason);
      this.status = status;
    }
  }

  /**
   * What's asked for: where from, the cursor to page from, how many, and the least important level.
   *
   * @param from {@code latest}, {@code before} or {@code after}
   * @param cursor the cursor to page from, for {@code before} and {@code after}; empty otherwise
   * @param limit the most entries wanted
   * @param level the least important level wanted
   */
  record Paging(String from, String cursor, int limit, Spotter.LogLevel level) {
    /** Paging as a request's query asks for it, checked. */
    static Paging of(Map<String, String> query) throws Refused {
      String from = query.getOrDefault("from", "latest");
      if (!List.of("latest", "before", "after").contains(from)) {
        throw new Refused(400, "from is latest, before or after, not " + from);
      }
      String cursor = query.getOrDefault("cursor", "");
      if (!from.equals("latest") && cursor.isEmpty()) {
        throw new Refused(400, "from=" + from + " pages from a cursor: give cursor=");
      }
      if (!cursor(cursor)) {
        // It reaches a log's command as SPOTTER_CURSOR: anyone's, so only what a cursor is.
        throw new Refused(
            400,
            "a cursor is printable ASCII, with no spaces, at most " + MAX_CURSOR + " characters");
      }
      int limit = DEFAULT_LIMIT;
      String asked = query.getOrDefault("limit", "");
      if (!asked.isEmpty()) {
        try {
          limit = Integer.parseInt(asked);
        } catch (NumberFormatException e) {
          limit = 0;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
          throw new Refused(400, "limit is 1 to " + MAX_LIMIT + ", not " + asked);
        }
      }
      Spotter.LogLevel level = Spotter.LogLevel.LOG_LEVEL_DEBUG;
      String named = query.getOrDefault("level", "");
      if (!named.isEmpty()) {
        level = LogLines.level(named);
        if (level == Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED) {
          throw new Refused(400, "level is error, warning, info, debug, or 0 to 7, not " + named);
        }
      }
      return new Paging(from, from.equals("latest") ? "" : cursor, limit, level);
    }

    /** Whether text is a cursor: printable ASCII, no space, at most {@link #MAX_CURSOR}. */
    static boolean cursor(String text) {
      if (text.length() > MAX_CURSOR) {
        return false;
      }
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c <= ' ' || c > '~') {
          return false;
        }
      }
      return true;
    }

    /** What the log's command is told. */
    Map<String, String> environment() {
      Map<String, String> environment = new LinkedHashMap<>();
      environment.put("SPOTTER_FROM", from);
      environment.put("SPOTTER_CURSOR", cursor);
      environment.put("SPOTTER_LIMIT", Integer.toString(limit));
      environment.put("SPOTTER_LEVEL", LogLines.name(level));
      return environment;
    }
  }

  /** A log and its pack's folder, where its command runs. */
  private record Source(String folder, Pack.Log log) {}

  private final Commands commands;
  private final Duration timeout;
  private final Map<String, Source> logs = new LinkedHashMap<>();
  private final Semaphore turns = new Semaphore(AT_ONCE);

  Logs(Commands commands, List<Pack> packs) {
    this(commands, packs, TIMEOUT);
  }

  /**
   * @param timeout how long a log's command may take: {@link #TIMEOUT}, or a test's
   */
  Logs(Commands commands, List<Pack> packs, Duration timeout) {
    this.timeout = timeout;
    this.commands = commands;
    for (Pack pack : packs) {
      for (Pack.Log log : pack.logs()) {
        logs.put(pack.name() + "." + log.id(), new Source(pack.folder(), log));
      }
    }
  }

  /** A page of a log, by its id ({@code pack.log}). */
  Spotter.LogPage page(String id, Paging paging) throws Refused {
    Source source = logs.get(id);
    if (source == null) {
      throw new Refused(404, "this board has no log " + id);
    }
    if (!turns.tryAcquire()) {
      throw new Refused(503, "as many logs are being paged as may; ask again shortly");
    }
    Commands.Result result;
    try {
      result =
          commands.run(
              source.folder(),
              source.log().run(),
              timeout,
              new Commands.Options(
                  null, paging.environment(), MAX_OUTPUT, 0, line -> {}, Duration.ZERO),
              new Commands.Cancellation());
    } finally {
      turns.release();
    }
    if (!result.completed()) {
      throw new Refused(
          result.outcome() == Spotter.Outcome.OUTCOME_TIMED_OUT ? 504 : 502,
          "log " + id + "'s command: " + result.message());
    }
    List<Spotter.LogEntry> entries = new ArrayList<>();
    for (String line : needed(result.output(), result.truncated(), paging)) {
      LogLines.entry(line, source.log().map(), 0).ifPresent(entries::add);
    }
    if (entries.isEmpty() && result.code() != 0) {
      throw new Refused(
          502,
          "log "
              + id
              + "'s command failed: exit "
              + result.code()
              + (result.errors().isEmpty() ? "" : ": " + result.errors()));
    }
    if (entries.size() > paging.limit()) {
      entries =
          paging.from().equals("after")
              ? entries.subList(0, paging.limit())
              : entries.subList(entries.size() - paging.limit(), entries.size());
    }
    return page(entries, paging);
  }

  /**
   * The lines of a log command's output a page needs, before any is read as an entry: the last
   * {@code limit} that aren't blank, or for {@code after} the first; a last line cut short by the
   * most kept is left out. So a command that prints far more than asked for costs its output's
   * bytes, and no more.
   */
  static List<String> needed(byte[] output, boolean truncated, Paging paging) {
    boolean after = paging.from().equals("after");
    ArrayDeque<int[]> kept = new ArrayDeque<>();
    int start = 0;
    for (int i = 0; i <= output.length; i++) {
      boolean last = i == output.length;
      if (!last && output[i] != '\n') {
        continue;
      }
      if (last && truncated) {
        break;
      }
      if (!blank(output, start, i)) {
        if (after && kept.size() == paging.limit()) {
          break;
        }
        kept.addLast(new int[] {start, i});
        if (kept.size() > paging.limit()) {
          kept.removeFirst();
        }
      }
      start = i + 1;
    }
    List<String> lines = new ArrayList<>();
    for (int[] line : kept) {
      lines.add(new String(output, line[0], line[1] - line[0], StandardCharsets.UTF_8));
    }
    return lines;
  }

  private static boolean blank(byte[] bytes, int from, int to) {
    for (int i = from; i < to; i++) {
      if (bytes[i] != ' ' && bytes[i] != '\t' && bytes[i] != '\r') {
        return false;
      }
    }
    return true;
  }

  /**
   * A page of entries as asked: with the cursor to page back from (empty at the start: fewer than
   * asked for, looking back), and the cursor to page on from.
   */
  static Spotter.LogPage page(List<Spotter.LogEntry> entries, Paging paging) {
    Spotter.LogPage page = Spotter.LogPage.newInstance();
    for (Spotter.LogEntry entry : entries) {
      page.addEntries(entry);
    }
    boolean back = !paging.from().equals("after");
    if (entries.isEmpty()) {
      return page.setBefore(back ? "" : paging.cursor()).setAfter(paging.cursor());
    }
    boolean start = back && entries.size() < paging.limit();
    return page.setBefore(start ? "" : entries.get(0).getCursor())
        .setAfter(entries.get(entries.size() - 1).getCursor());
  }

  /**
   * A page of a run's own log, which the agent keeps: its entries' cursors are their places, from
   * 1, and the agent pages and filters them itself.
   */
  static Spotter.LogPage slice(List<Spotter.LogEntry> all, Paging paging) {
    Slice slice = new Slice(paging);
    for (Spotter.LogEntry entry : all) {
      if (!slice.take(entry)) {
        break;
      }
    }
    return slice.page();
  }

  /**
   * A page of a run's log, its entries taken one at a time as they're read, at most the limit held:
   * the newest wanted, or for {@code after} the first, so a long log costs no more than a page.
   */
  static final class Slice {
    private final Paging paging;
    private final long cursor;
    private final ArrayDeque<Spotter.LogEntry> wanted = new ArrayDeque<>();

    Slice(Paging paging) {
      this.paging = paging;
      long from = 0;
      if (!paging.cursor().isEmpty()) {
        try {
          from = Long.parseLong(paging.cursor());
        } catch (NumberFormatException e) {
          from = paging.from().equals("before") ? 0 : Long.MAX_VALUE;
        }
      }
      this.cursor = from;
    }

    /** Takes an entry, in the log's order: whether any more are wanted. */
    boolean take(Spotter.LogEntry entry) {
      long place;
      try {
        place = Long.parseLong(entry.getCursor());
      } catch (NumberFormatException e) {
        return true;
      }
      boolean before = paging.from().equals("before");
      boolean after = paging.from().equals("after");
      if (before && place >= cursor) {
        return false;
      }
      if ((after && place <= cursor) || !important(entry.getLevel(), paging.level())) {
        return true;
      }
      wanted.addLast(entry);
      if (wanted.size() > paging.limit()) {
        wanted.removeFirst();
      }
      return !(after && wanted.size() == paging.limit());
    }

    /** The page of what was taken. */
    Spotter.LogPage page() {
      return Logs.page(new ArrayList<>(wanted), paging);
    }
  }

  /** Whether an entry's level is as important as the least wanted, or has none. */
  static boolean important(Spotter.LogLevel level, Spotter.LogLevel least) {
    return level == Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED
        || level.getNumber() <= least.getNumber();
  }
}
