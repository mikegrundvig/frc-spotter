package com.michaelgrundvig.frc.spotter.manager;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Which page of a log to ask for ({@link Board#log}): the latest entries, or those before or after
 * a cursor (an entry's, or a page's {@code before} and {@code after}), at most {@code limit}, at a
 * level or more severe.
 *
 * @param from {@code latest}, {@code before} or {@code after}
 * @param cursor where to page from; empty for the latest
 * @param limit how many entries at most: 1 to {@value #MAX_LIMIT}
 * @param level the least severe level wanted ({@code error}, {@code warning}, {@code info}, {@code
 *     debug}, or syslog's 0 to 7); empty for every entry
 */
public record LogQuery(String from, String cursor, int limit, String level) {
  /** The most entries one page holds. */
  public static final int MAX_LIMIT = 1000;

  public LogQuery {
    if (!from.equals("latest") && !from.equals("before") && !from.equals("after")) {
      throw new IllegalArgumentException("from is latest, before or after, not " + from);
    }
    if (!from.equals("latest") && cursor.isEmpty()) {
      throw new IllegalArgumentException("from " + from + " pages from a cursor");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit is 1 to " + MAX_LIMIT + ", not " + limit);
    }
  }

  /** The latest entries, at most {@code limit}. */
  public static LogQuery latest(int limit) {
    return new LogQuery("latest", "", limit, "");
  }

  /** The entries before a cursor, at most {@code limit}. */
  public static LogQuery before(String cursor, int limit) {
    return new LogQuery("before", cursor, limit, "");
  }

  /** The entries after a cursor, at most {@code limit}. */
  public static LogQuery after(String cursor, int limit) {
    return new LogQuery("after", cursor, limit, "");
  }

  /** This page, with only entries at a level or more severe: {@code warning}, say. */
  public LogQuery atLeast(String level) {
    return new LogQuery(from, cursor, limit, level);
  }

  /** As a request's query: {@code ?from=latest&limit=100}. */
  String query() {
    StringBuilder query = new StringBuilder("?from=").append(from).append("&limit=").append(limit);
    if (!cursor.isEmpty()) {
      query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
    }
    if (!level.isEmpty()) {
      query.append("&level=").append(URLEncoder.encode(level, StandardCharsets.UTF_8));
    }
    return query.toString();
  }
}
