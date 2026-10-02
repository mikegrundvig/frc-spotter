package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.api.ShutdownAnswer;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The agent's HTTP API, version 1, on the JDK's own server: JSON answers, each bounded; nothing a
 * request says is run, only probes its packs define, by name. The one action, {@code POST
 * /v1/shutdown}, is the controller's alone. The only class that touches the web server.
 *
 * <p>Each request has a thread of its own, so a client that's slow to send (or never finishes)
 * holds up nobody else. The heavy request (a page of the journal) takes turns, and one that finds
 * another under way is refused as busy (503) at once, so the light ones (health, stamp, probes,
 * shutdown) never wait behind it. It answers only requests addressed to it (by a robot-network,
 * link-local, or loopback address, or its own name), so a web page elsewhere can't reach it through
 * a name that resolves to it.
 */
final class AgentServer implements AutoCloseable {
  /** How many heavy requests are answered at once. */
  static final int HEAVY = 1;

  /** How long between log lines about refused shutdowns, in microseconds. */
  static final long REFUSAL_LOG_MICROS = 10_000_000;

  private static final Set<String> PATHS =
      Set.of(AgentApi.STAMP, AgentApi.HEALTH, AgentApi.JOURNAL, AgentApi.PROBES, AgentApi.SHUTDOWN);

  private static final Map<String, Integer> PRIORITIES =
      Map.of(
          "emerg", 0, "alert", 1, "crit", 2, "err", 3, "warning", 4, "notice", 5, "info", 6,
          "debug", 7);

  /** The robot network (10/8), link-local (169.254/16), and loopback (127/8) addresses. */
  private static final Pattern OWN_ADDRESS =
      Pattern.compile("(10|127)(\\.[0-9]{1,3}){3}|169\\.254(\\.[0-9]{1,3}){2}");

  private final Agent agent;
  private final HttpServer server;

  /**
   * A thread for each request, made when one's needed: the server takes at most {@code
   * jdk.httpserver.maxConnections} (32) at once, so there are never more. Platform threads, as Java
   * 17 has no others; each reserves its stack ({@code -Xss512k}) only as it's used.
   */
  private final ExecutorService threads =
      Executors.newCachedThreadPool(
          work -> {
            Thread thread = new Thread(work, "spotter-request");
            thread.setDaemon(true);
            return thread;
          });

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
    agent.close();
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
        String probe = under(path, AgentApi.PROBES);
        if (!PATHS.contains(path) && probe == null) {
          throw new Refused(404, "no such resource: " + path);
        }
        boolean shutdown = path.equals(AgentApi.SHUTDOWN);
        if (shutdown ? !method.equals("POST") : !method.equals("GET")) {
          exchange.getResponseHeaders().set("Allow", shutdown ? "POST" : "GET");
          throw new Refused(405, path + " takes " + (shutdown ? "POST" : "GET") + " only");
        }
        if (shutdown) {
          shutdown(exchange);
        } else if (path.equals(AgentApi.JOURNAL)) {
          heavy(exchange, () -> journal(exchange));
        } else if (probe != null) {
          probe(exchange, probe);
        } else if (path.equals(AgentApi.PROBES)) {
          json(
              exchange,
              200,
              JsonValue.Obj.builder()
                  .put("probes", JsonValue.array(agent.probes().results(), ProbeResult::toJson))
                  .build());
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

  /**
   * The name after a path's folder ({@code /v1/probes/<name>}), decoded; null when it isn't one.
   */
  private static @Nullable String under(String path, String folder) throws Refused {
    if (!path.startsWith(folder + "/") || path.length() == folder.length() + 1) {
      return null;
    }
    try {
      return URLDecoder.decode(
          path.substring(folder.length() + 1).replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new Refused(400, "the path isn't URL-encoded");
    }
  }

  /**
   * Runs one probe now, unless it ran within the last couple of seconds, and answers its result.
   */
  private void probe(HttpExchange exchange, String id) throws IOException, Refused {
    try {
      ProbeResult result =
          agent
              .probes()
              .runNow(id)
              .orElseThrow(() -> new Refused(404, "this computer has no probe named " + id));
      json(exchange, 200, result.toJson());
    } catch (Probes.Busy e) {
      exchange.getResponseHeaders().set("Retry-After", "1");
      throw new Refused(503, String.valueOf(e.getMessage()));
    }
  }

  /** Work a heavy request does, once it has its turn. */
  private interface Heavy {
    void answer() throws IOException, Refused;
  }

  /** Answers a heavy request, if none is under way; refuses it as busy otherwise. */
  private void heavy(HttpExchange exchange, Heavy work) throws IOException, Refused {
    if (!heavy.tryAcquire()) {
      exchange.getResponseHeaders().set("Retry-After", "1");
      throw new Refused(503, "busy with another journal request; ask again shortly");
    }
    try {
      work.answer();
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
    List<String> served = agent.journalUnits();
    if (!unit.isEmpty() && !served.contains(unit)) {
      throw new Refused(400, "unit must be one of " + String.join(", ", served));
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
    List<String> units = unit.isEmpty() ? served : List.of(unit);
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

  private void shutdown(HttpExchange exchange) throws IOException, Refused {
    String from = exchange.getRemoteAddress().getAddress().getHostAddress();
    Agent.Controller controller = agent.controller();
    if (controller.address().isEmpty()) {
      refusals.log("Refused a shutdown from " + from + ": " + controller.why());
      throw new Refused(403, "a shutdown is taken from nobody: " + controller.why());
    }
    if (!from.equals(controller.address())) {
      refusals.log("Refused a shutdown from " + from + ": not the robot controller");
      throw new Refused(
          403,
          "only the robot controller (" + controller.address() + ") may shut this computer down");
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
