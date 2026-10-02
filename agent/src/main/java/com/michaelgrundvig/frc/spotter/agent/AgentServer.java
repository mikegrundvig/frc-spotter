package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.ShutdownAnswer;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.settings.SettingsFiles;
import com.michaelgrundvig.frc.spotter.settings.SettingsRow;
import com.michaelgrundvig.frc.spotter.settings.SettingsText;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * The agent's HTTP API, version 1, on the JDK's own server: JSON answers, each bounded; nothing a
 * request says is run. The one action, {@code POST /v1/shutdown}, is the robot controller's alone.
 * The only class that touches the web server.
 *
 * <p>Each request has a virtual thread of its own, so a client that's slow to send (or never
 * finishes) holds up nobody else. The heavy requests (the journal and the settings) take turns, and
 * a request that finds one under way is refused as busy (503) at once, so the light ones (health,
 * stamp, shutdown) never wait behind them. It answers only requests addressed to it (by a
 * robot-network, link-local, or loopback address, or its own name), so a web page elsewhere can't
 * reach it through a name that resolves to it.
 */
final class AgentServer implements AutoCloseable {
  /** How many heavy requests are answered at once. */
  static final int HEAVY = 1;

  /** How long between log lines about refused shutdowns, in microseconds. */
  static final long REFUSAL_LOG_MICROS = 10_000_000;

  private static final Set<String> PATHS =
      Set.of(
          AgentApi.STAMP,
          AgentApi.HEALTH,
          AgentApi.JOURNAL,
          AgentApi.SETTINGS,
          AgentApi.SETTINGS_ZIP,
          AgentApi.SHUTDOWN);

  private static final Set<String> HEAVY_PATHS =
      Set.of(AgentApi.JOURNAL, AgentApi.SETTINGS, AgentApi.SETTINGS_ZIP);

  private static final Map<String, Integer> PRIORITIES =
      Map.of(
          "emerg", 0, "alert", 1, "crit", 2, "err", 3, "warning", 4, "notice", 5, "info", 6,
          "debug", 7);

  /** The robot network (10/8), link-local (169.254/16), and loopback (127/8) addresses. */
  private static final Pattern OWN_ADDRESS =
      Pattern.compile("(10|127)(\\.[0-9]{1,3}){3}|169\\.254(\\.[0-9]{1,3}){2}");

  private final Agent agent;
  private final HttpServer server;
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore heavy = new Semaphore(HEAVY);
  private final RateLimitedLog refusals;

  /** A request that can't be answered as asked, with its status. */
  private static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;
    final String reason;

    Refused(int status, String reason) {
      super(reason);
      this.status = status;
      this.reason = reason;
    }
  }

  AgentServer(Agent agent, InetSocketAddress address, RateLimitedLog refusals) throws IOException {
    this.agent = agent;
    this.refusals = refusals;
    this.server = HttpServer.create(address, 32);
    server.setExecutor(threads);
    server.createContext("/", this::handle);
    server.start();
  }

  /** The port it's serving on. */
  int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    threads.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getRawPath();
      String method = exchange.getRequestMethod();
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      try {
        if (!addressedHere(exchange.getRequestHeaders().getFirst("Host"))) {
          throw new Refused(421, "this agent answers requests addressed to it only");
        }
        if (!PATHS.contains(path)) {
          throw new Refused(404, "no such resource: " + path);
        }
        boolean shutdown = path.equals(AgentApi.SHUTDOWN);
        if (shutdown ? !method.equals("POST") : !method.equals("GET")) {
          exchange.getResponseHeaders().set("Allow", shutdown ? "POST" : "GET");
          throw new Refused(405, path + " takes " + (shutdown ? "POST" : "GET") + " only");
        }
        if (HEAVY_PATHS.contains(path)) {
          heavy(exchange, path);
        } else if (shutdown) {
          shutdown(exchange);
        } else {
          json(
              exchange,
              200,
              path.equals(AgentApi.STAMP) ? agent.stamp().toJson() : agent.health().toJson());
        }
      } catch (Refused e) {
        json(exchange, e.status, error(e.reason));
      } catch (IOException | RuntimeException | Error e) {
        // The detail (which may name files) goes to the journal; the answer says only that.
        agent.log("Answering " + method + " " + path + " failed: " + e);
        json(exchange, 500, error("the agent couldn't answer; its journal says why"));
      }
    } catch (IOException e) {
      // The client went away mid-answer, or an answer failed after it began: nothing to do.
    }
  }

  /** Answers a heavy request, if none is under way; refuses it as busy otherwise. */
  private void heavy(HttpExchange exchange, String path) throws IOException, Refused {
    if (!heavy.tryAcquire()) {
      exchange.getResponseHeaders().set("Retry-After", "1");
      throw new Refused(503, "busy with another journal or settings request; ask again shortly");
    }
    try {
      switch (path) {
        case AgentApi.JOURNAL -> journal(exchange);
        case AgentApi.SETTINGS -> settings(exchange);
        default -> settingsZip(exchange);
      }
    } finally {
      heavy.release();
    }
  }

  /**
   * Whether the request was addressed to this computer: by an address on the robot's network, a
   * link-local or loopback address, {@code localhost}, or its own name. A request with no Host is
   * an old client's, and allowed.
   */
  private boolean addressedHere(@Nullable String header) throws IOException {
    if (header == null) {
      return true;
    }
    String host = header.strip().toLowerCase(Locale.ROOT);
    if (host.startsWith("[")) {
      return host.startsWith("[::1]");
    }
    int colon = host.indexOf(':');
    if (colon >= 0) {
      host = host.substring(0, colon);
    }
    return OWN_ADDRESS.matcher(host).matches()
        || host.equals("localhost")
        || agent.names().contains(host);
  }

  private void journal(HttpExchange exchange) throws IOException, Refused {
    Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
    JournalSource.Position position = position(query);
    String unit = query.getOrDefault("unit", "");
    if (!unit.isEmpty() && !AgentApi.JOURNAL_UNITS.contains(unit)) {
      throw new Refused(400, "unit must be one of " + String.join(", ", AgentApi.JOURNAL_UNITS));
    }
    int priority = -1;
    String level = query.getOrDefault("priority", "").toLowerCase(Locale.ROOT);
    if (!level.isEmpty()) {
      Integer named = PRIORITIES.get(level);
      priority = named != null ? named : number(level, 0, 7, "priority");
    }
    int limit = AgentApi.DEFAULT_JOURNAL_PAGE;
    if (query.containsKey("limit")) {
      limit = number(query.getOrDefault("limit", ""), 1, AgentApi.MAX_JOURNAL_PAGE, "limit");
    }
    List<String> units = unit.isEmpty() ? AgentApi.JOURNAL_UNITS : List.of(unit);
    json(exchange, 200, agent.journal(position, priority, units, limit).toJson());
  }

  /**
   * Where the page is: after {@code cursor}, before {@code before}, from the start of this boot
   * ({@code from=boot}), or (with none of them) the latest. At most one may be asked.
   */
  private static JournalSource.Position position(Map<String, String> query) throws Refused {
    String cursor = query.getOrDefault("cursor", "");
    String before = query.getOrDefault("before", "");
    String from = query.getOrDefault("from", "");
    if ((cursor.isEmpty() ? 0 : 1) + (before.isEmpty() ? 0 : 1) + (from.isEmpty() ? 0 : 1) > 1) {
      throw new Refused(400, "ask with one of cursor, before, and from");
    }
    for (String given : List.of(cursor, before)) {
      if (!given.isEmpty() && !JournalSource.CURSOR.matcher(given).matches()) {
        throw new Refused(400, "cursor and before must be journal cursors");
      }
    }
    if (!from.isEmpty() && !from.equals("boot")) {
      throw new Refused(400, "from may only be boot");
    }
    if (!cursor.isEmpty()) {
      return JournalSource.Position.after(cursor);
    }
    if (!before.isEmpty()) {
      return JournalSource.Position.before(before);
    }
    return from.isEmpty() ? JournalSource.Position.LATEST : JournalSource.Position.BOOT;
  }

  /**
   * The settings as JSON, the same as {@code Settings.toJson()}, written a row at a time: the hash
   * first (a pass over the rows), then each row, parsed one at a time.
   */
  private void settings(HttpExchange exchange) throws IOException, Refused {
    Optional<Boolean> sent =
        send(
            text -> {
              String hash = text.hash();
              exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
              exchange.sendResponseHeaders(200, 0);
              try (Writer out =
                  new BufferedWriter(
                      new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8))) {
                out.write("{\"hash\":" + Json.compact(JsonValue.of(hash)));
                out.write(",\"userVersion\":" + text.userVersion() + ",\"rows\":[");
                boolean first = true;
                for (SettingsText.Row row : text.rows()) {
                  SettingsRow parsed = row.parse();
                  out.write(first ? "" : ",");
                  first = false;
                  Json.writeCompact(
                      JsonValue.Obj.builder()
                          .put("table", parsed.table())
                          .put("key", parsed.key())
                          .put("columns", parsed.columns())
                          .build(),
                      out);
                }
                out.write("]}");
              }
              return true;
            });
    if (sent.isEmpty()) {
      throw new Refused(404, "PhotonVision has no settings database yet");
    }
  }

  /**
   * The settings as a zip laid out like the repository: {@code coprocessors/<computer>/settings/}
   * and its files, so unzipping it at the repository's root puts them in place; and last, beside
   * that folder, {@code settings.sha256}: every file's SHA-256, as {@code sha256sum} writes them,
   * so a zip that was cut short shows it ({@code sha256sum -c} from the repository's root).
   */
  private void settingsZip(HttpExchange exchange) throws IOException, Refused {
    String name = agent.stampFile().stamp().name();
    String computer = name.isEmpty() ? "coprocessor" : name;
    String folder = SettingsFiles.folder(computer);
    Optional<Boolean> sent =
        send(
            text -> {
              List<String> paths =
                  text.rows().stream()
                      .map(row -> SettingsFiles.path(row.table(), row.key()))
                      .toList();
              SettingsFiles.checkCase(paths);
              exchange.getResponseHeaders().set("Content-Type", "application/zip");
              exchange
                  .getResponseHeaders()
                  .set(
                      "Content-Disposition",
                      "attachment; filename=\"" + computer + "-settings.zip\"");
              exchange.sendResponseHeaders(200, 0);
              StringBuilder sums = new StringBuilder();
              try (ZipOutputStream zip = new ZipOutputStream(exchange.getResponseBody())) {
                String database = folder + "/" + SettingsFiles.DATABASE_FILE;
                sums.append(
                        entry(
                            zip,
                            database,
                            out -> out.append(SettingsFiles.database(text.userVersion()))))
                    .append("  ")
                    .append(database)
                    .append('\n');
                for (int i = 0; i < paths.size(); i++) {
                  SettingsRow row = text.rows().get(i).parse();
                  String path = folder + "/" + paths.get(i);
                  sums.append(entry(zip, path, out -> SettingsFiles.write(row, out)))
                      .append("  ")
                      .append(path)
                      .append('\n');
                }
                entry(zip, folder + ".sha256", out -> out.append(sums));
              }
              return true;
            });
    if (sent.isEmpty()) {
      throw new Refused(404, "PhotonVision has no settings database yet");
    }
  }

  private interface Content {
    void write(Appendable out) throws IOException;
  }

  /** Writes one zip entry as UTF-8 text; its SHA-256. */
  private static String entry(ZipOutputStream zip, String path, Content content)
      throws IOException {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
    zip.putNextEntry(new ZipEntry(path));
    // Not closing the zip when the entry's writer is done.
    OutputStream entry =
        new FilterOutputStream(zip) {
          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
          }

          @Override
          public void close() throws IOException {
            flush();
          }
        };
    Writer out =
        new BufferedWriter(
            new OutputStreamWriter(new DigestOutputStream(entry, sha256), StandardCharsets.UTF_8));
    content.write(out);
    out.close();
    zip.closeEntry();
    return HexFormat.of().formatHex(sha256.digest());
  }

  private <T> Optional<T> send(SettingsSource.Sending<T> sending) throws IOException, Refused {
    try {
      return agent.sendSettings(sending);
    } catch (SettingsSource.Busy e) {
      throw new Refused(503, "busy sending the settings; ask again shortly");
    }
  }

  private void shutdown(HttpExchange exchange) throws IOException, Refused {
    String from = exchange.getRemoteAddress().getAddress().getHostAddress();
    Optional<String> controller = agent.controller();
    if (controller.isEmpty()) {
      refusals.log("Refused a shutdown from " + from + ": this computer has no stamp");
      throw new Refused(
          403, "this computer has no stamp, so no robot controller to take a shutdown from");
    }
    if (!from.equals(controller.get())) {
      refusals.log("Refused a shutdown from " + from + ": not the robot controller");
      throw new Refused(
          403, "only the robot controller (" + controller.get() + ") may shut this computer down");
    }
    boolean first = agent.shutdown(from);
    json(exchange, 202, new ShutdownAnswer(!first).toJson());
  }

  private static JsonValue.Obj error(String message) {
    return JsonValue.Obj.builder().put("error", message).build();
  }

  private void json(HttpExchange exchange, int status, JsonValue body) throws IOException {
    byte[] bytes = Json.compact(body).getBytes(StandardCharsets.UTF_8);
    int sent = status;
    if (bytes.length > agent.limits().maxAnswer()) {
      agent.log("An answer of " + bytes.length + " bytes was more than the agent sends");
      sent = 500;
      bytes =
          Json.compact(error("the answer is larger than the agent sends"))
              .getBytes(StandardCharsets.UTF_8);
    }
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(sent, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static Map<String, String> query(@Nullable String raw) throws Refused {
    Map<String, String> query = new HashMap<>();
    if (raw == null || raw.isEmpty()) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      try {
        query.put(
            URLDecoder.decode(key, StandardCharsets.UTF_8),
            URLDecoder.decode(value, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        throw new Refused(400, "the query isn't URL-encoded");
      }
    }
    return query;
  }

  private static int number(String text, int min, int max, String what) throws Refused {
    try {
      int value = Integer.parseInt(text);
      if (value >= min && value <= max) {
        return value;
      }
    } catch (NumberFormatException e) {
      // refused below
    }
    throw new Refused(400, what + " must be a number from " + min + " to " + max);
  }
}
