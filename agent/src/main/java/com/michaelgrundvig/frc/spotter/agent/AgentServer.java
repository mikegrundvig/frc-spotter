package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.JsonSink;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;

/**
 * The agent's protocol, version 2, on the JDK's own server: every answer a {@code spotter.proto}
 * message, in protobuf, or in QuickBuffers' JSON form of it when the request's {@code Accept} asks
 * for {@code application/json}; every response carries {@code Spotter-Protocol: 2.0}, refusals and
 * failures included, which answer a {@code Problem}. The only class that touches the web server.
 *
 * <p>Reading is open to anyone on the robot's network. A write (starting an action, cancelling a
 * run, pushing packs) is taken only from the robot controller's address; is refused, every one,
 * while the board's {@code agent.json} is there but can't be read; and must be signed when the
 * board lists trusted keys.
 *
 * <p>Each request has a thread of its own, so a client that's slow to send (or never finishes)
 * holds up nobody else. It answers only requests addressed to it (by a robot-network, link-local,
 * or loopback address, or its own name), so a web page elsewhere can't reach it through a name that
 * resolves to it.
 */
final class AgentServer implements AutoCloseable {
  /**
   * How many streams the robot controller's address may have open at once: its manager's, and one
   * it's replacing. One more closes its oldest, which it can't still be reading.
   */
  static final int CONTROLLER_STREAMS = 2;

  /** How many streams others may have open at once: one more is refused. */
  static final int OTHER_STREAMS = 2;

  /** How long a stream's write may stall, its reader not reading, before the stream is closed. */
  static final Duration STALL = Duration.ofSeconds(10);

  /** How many requests one address may have under way at once: one more is refused. */
  static final int PER_ADDRESS = 8;

  /** How long between log lines about refused writes, in microseconds. */
  static final long REFUSAL_LOG_MICROS = 10_000_000;

  /** How long after answering a push the agent ends, for systemd to start it again. */
  static final Duration EXIT_DELAY = Duration.ofMillis(300);

  /** JSON lines: a stream's form when JSON is asked for. */
  static final String JSON_LINES = "application/x-ndjson";

  /** The robot network (10/8), link-local (169.254/16), and loopback (127/8) addresses. */
  private static final Pattern OWN_ADDRESS =
      Pattern.compile("(10|127)(\\.[0-9]{1,3}){3}|169\\.254(\\.[0-9]{1,3}){2}");

  private final Agent agent;
  private final HttpServer server;
  private final Semaphore pushes = new Semaphore(1);
  private final List<Open> open = new ArrayList<>();
  private final Map<String, Integer> underWay = new HashMap<>();
  private final long stallNanos;
  private final ScheduledExecutorService watch =
      Executors.newSingleThreadScheduledExecutor(
          work -> {
            Thread thread = new Thread(work, "spotter-streams");
            thread.setDaemon(true);
            return thread;
          });

  /** An open stream: whose, and the thread serving it, which closing it interrupts. */
  private static final class Open {
    final boolean controller;
    final Thread thread;
    volatile @Nullable Stream stream;
    boolean closed;

    Open(boolean controller, Thread thread) {
      this.controller = controller;
      this.thread = thread;
    }
  }

  private final RateLimitedLog refusals;
  private final RateLimitedLog failures;

  /**
   * A thread for each request, made when one's needed: the server takes at most {@code
   * jdk.httpserver.maxConnections} at once, so there are never more.
   */
  private final ExecutorService threads =
      Executors.newCachedThreadPool(
          work -> {
            Thread thread = new Thread(work, "spotter-request");
            thread.setDaemon(true);
            return thread;
          });

  /** A request that can't be answered as asked, with its status. */
  private static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;
    final String pushedPacks;

    Refused(int status, String reason) {
      this(status, reason, "");
    }

    Refused(int status, String reason, String pushedPacks) {
      super(reason);
      this.status = status;
      this.pushedPacks = pushedPacks;
    }
  }

  /** A request, as the routes see it. */
  private static final class Request {
    final HttpExchange exchange;
    final String path;
    final String method;
    final boolean json;

    Request(HttpExchange exchange, boolean json) {
      this.exchange = exchange;
      this.path = exchange.getRequestURI().getRawPath();
      this.method = exchange.getRequestMethod();
      this.json = json;
    }
  }

  AgentServer(Agent agent, InetSocketAddress address) throws IOException {
    this(agent, address, STALL);
  }

  /**
   * @param stall how long a stream's write may stall before it's closed: {@link #STALL}, or a
   *     test's
   */
  AgentServer(Agent agent, InetSocketAddress address, Duration stall) throws IOException {
    this.agent = agent;
    this.stallNanos = stall.toNanos();
    long every = Math.max(10, Math.min(1000, stall.toMillis() / 2));
    watch.scheduleWithFixedDelay(this::closeStalled, every, every, TimeUnit.MILLISECONDS);
    this.refusals = new RateLimitedLog(agent::log, () -> agent.nanos() / 1000, REFUSAL_LOG_MICROS);
    this.failures = new RateLimitedLog(agent::log, () -> agent.nanos() / 1000, REFUSAL_LOG_MICROS);
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
    watch.shutdownNow();
    server.stop(0);
    threads.shutdownNow();
    agent.close();
  }

  /** How many streams are open: for tests. */
  int streams() {
    synchronized (open) {
      return open.size();
    }
  }

  /** Closes each stream whose write has stalled past its bound: its reader stopped reading. */
  private void closeStalled() {
    long now = System.nanoTime();
    synchronized (open) {
      for (Open each : open) {
        Stream stream = each.stream;
        if (stream != null && stream.stalledNanos(now) > stallNanos) {
          close(each);
        }
      }
    }
  }

  /**
   * Closes a stream: its serving thread interrupted, which ends its wait, or its write (a socket's
   * channel closes when a thread blocked writing it is interrupted). Only while it's open, under
   * the lock its thread takes to leave: so no other request of that thread's is ever interrupted.
   */
  private void close(Open stream) {
    if (!stream.closed) {
      stream.closed = true;
      stream.thread.interrupt();
    }
  }

  /** A request from an address, under way: false when it has as many under way as may. */
  boolean enter(String from) {
    synchronized (underWay) {
      int now = underWay.getOrDefault(from, 0);
      if (now >= PER_ADDRESS) {
        return false;
      }
      underWay.put(from, now + 1);
      return true;
    }
  }

  /** A request from an address, done. */
  void leave(String from) {
    synchronized (underWay) {
      int now = underWay.getOrDefault(from, 1) - 1;
      if (now <= 0) {
        underWay.remove(from);
      } else {
        underWay.put(from, now);
      }
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      Request request =
          new Request(exchange, wantsJson(exchange.getRequestHeaders().getFirst("Accept")));
      exchange.getResponseHeaders().set(Protocol.HEADER, Protocol.VERSION);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      String from = exchange.getRemoteAddress().getAddress().getHostAddress();
      boolean entered = false;
      try {
        if (!addressedHere(exchange.getRequestHeaders().getFirst("Host"))) {
          throw new Refused(421, "this agent answers requests addressed to it only");
        }
        entered = enter(from);
        if (!entered) {
          throw new Refused(
              503, from + " has " + PER_ADDRESS + " requests under way, as many as one may");
        }
        route(request);
      } catch (Refused e) {
        Spotter.Problem problem = problem(String.valueOf(e.getMessage()));
        if (e.status == 409 && request.path.equals(Protocol.STREAM)) {
          problem.setPushedPacks(e.pushedPacks);
        }
        if (e.status == 503) {
          exchange.getResponseHeaders().set("Retry-After", "1");
        }
        send(exchange, e.status, problem, request.json);
      } catch (IOException | RuntimeException | Error e) {
        // The detail (which may name files) goes to the journal, at most a line each 10 s; the
        // answer says only that.
        failures.log("Answering " + request.method + " " + request.path + " failed: " + e);
        send(
            exchange,
            500,
            problem("the agent couldn't answer; its journal says why"),
            request.json);
      } finally {
        if (entered) {
          leave(from);
        }
      }
    } catch (IOException e) {
      // The client went away mid-answer, or an answer failed after it began: nothing to do.
    }
  }

  private void route(Request request) throws Refused, IOException {
    String path = request.path;
    if (path.equals(Protocol.DESCRIBE)) {
      only(request, "GET");
      send(request.exchange, 200, agent.description(), request.json);
    } else if (path.equals(Protocol.VALUES)) {
      only(request, "GET");
      send(request.exchange, 200, agent.values(), request.json);
    } else if (path.equals(Protocol.STREAM)) {
      only(request, "GET");
      stream(request);
    } else if (path.equals(Protocol.PACKS)) {
      only(request, "POST");
      push(request);
    } else if (path.startsWith(Protocol.LOGS)) {
      String id = name(path.substring(Protocol.LOGS.length()));
      only(request, "GET");
      Logs.Paging paging = paging(request);
      try {
        send(request.exchange, 200, agent.logs().page(id, paging), request.json);
      } catch (Logs.Refused e) {
        throw new Refused(e.status, String.valueOf(e.getMessage()));
      }
    } else if (path.startsWith(Protocol.ACTIONS)) {
      String id = name(path.substring(Protocol.ACTIONS.length()));
      only(request, "POST");
      action(request, id);
    } else if (path.startsWith(Protocol.RUNS)) {
      runs(request, path.substring(Protocol.RUNS.length()).split("/", -1));
    } else {
      throw new Refused(404, "no such resource: " + path);
    }
  }

  /** Refuses a request whose method isn't the one its path takes. */
  private static void only(Request request, String method) throws Refused {
    if (!request.method.equals(method)) {
      request.exchange.getResponseHeaders().set("Allow", method);
      throw new Refused(405, request.path + " takes " + method + " only");
    }
  }

  /** A name in a path, as packs name things; {@code 404} for anything else. */
  private static String name(String text) throws Refused {
    if (!PackReader.ID.matcher(text).matches()) {
      throw new Refused(404, "no such resource: " + text);
    }
    return text;
  }

  // ---- the stream ----

  private void stream(Request request) throws Refused, IOException {
    Map<String, String> query = query(request.exchange.getRequestURI().getRawQuery());
    String asked = query.getOrDefault("heartbeat", "");
    Duration heartbeat =
        Stream.heartbeat(asked)
            .orElseThrow(
                () -> new Refused(400, "heartbeat is a duration, such as 250ms, not " + asked));
    String packs = query.getOrDefault("packs", "");
    String ours = agent.configuration().packs().pushedHash();
    if (!packs.isEmpty() && agent.configuration().acceptsPushes() && !packs.equals(ours)) {
      throw new Refused(
          409,
          "this board's pushed packs differ from the robot's: "
              + (ours.isEmpty() ? "it has none" : "it has " + ours),
          ours);
    }
    Open entry = admit(request);
    String challenge = agent.signatures().open();
    Stream stream = null;
    try {
      HttpExchange exchange = request.exchange;
      exchange.getResponseHeaders().set(Protocol.CHALLENGE, challenge);
      exchange
          .getResponseHeaders()
          .set("Content-Type", request.json ? JSON_LINES : Protocol.PROTOBUF);
      exchange.sendResponseHeaders(200, 0);
      OutputStream out = new BufferedOutputStream(exchange.getResponseBody(), 16 * 1024);
      ProtoSink protobuf = ProtoSink.newInstance(out);
      Stream.Sink sink;
      if (request.json) {
        sink =
            event -> {
              JsonSink json = JsonSink.newInstance();
              json.writeMessage(event);
              out.write(json.getBytes().toArray());
              out.write('\n');
              out.flush();
            };
      } else {
        sink =
            event -> {
              event.writeDelimitedTo(protobuf);
              out.flush();
            };
      }
      stream = new Stream(agent, heartbeat, sink);
      entry.stream = stream;
      agent.events().subscribe(stream);
      stream.serve();
    } catch (InterruptedException e) {
      // Closed: stalled, or replaced by the controller's newer stream. Still interrupted, its
      // answer's end isn't written to a reader that may not read it: its connection just closes.
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // The manager went away, or the stream was closed as it wrote: the stream ends.
    } finally {
      if (stream != null) {
        agent.events().unsubscribe(stream);
      }
      agent.signatures().close(challenge);
      synchronized (open) {
        open.remove(entry);
      }
    }
  }

  /**
   * Opens a stream, if one more may be: the robot controller's address may have {@link
   * #CONTROLLER_STREAMS}, its oldest closed for one more; anyone else's together {@link
   * #OTHER_STREAMS}, one more refused. So a reader that holds streams it no longer reads (a laptop
   * unplugged mid-stream) can never keep the robot's manager out.
   */
  private Open admit(Request request) throws Refused {
    String from = request.exchange.getRemoteAddress().getAddress().getHostAddress();
    boolean controller;
    try {
      controller = from.equals(agent.controller().address());
    } catch (IOException e) {
      controller = false;
    }
    synchronized (open) {
      List<Open> theirs = new ArrayList<>();
      for (Open each : open) {
        if (each.controller == controller && !each.closed) {
          theirs.add(each);
        }
      }
      if (controller && theirs.size() >= CONTROLLER_STREAMS) {
        close(theirs.get(0));
      } else if (!controller && theirs.size() >= OTHER_STREAMS) {
        throw new Refused(
            503,
            OTHER_STREAMS
                + " streams are open to others than the robot controller, as many as may");
      }
      Open entry = new Open(controller, Thread.currentThread());
      open.add(entry);
      return entry;
    }
  }

  // ---- writes ----

  /**
   * Refuses a write unless the board's settings can be read and it comes from the robot
   * controller's address; and, but for a built-in action, unless the controller is named.
   */
  private void writer(Request request, String what) throws Refused, IOException {
    writer(request, what, false);
  }

  /**
   * Refuses a write unless the board's settings can be read and it comes from the robot
   * controller's address; and, unless it's starting a built-in action, unless that controller is
   * named (in agent.json, or on the command line), not only worked out from the board's own
   * address, which on a school's or a home's 10.x network could be anyone's.
   *
   * @param builtIn whether it starts a built-in action (power-off, reboot)
   */
  private void writer(Request request, String what, boolean builtIn) throws Refused, IOException {
    String unreadable = agent.configuration().unreadable();
    if (!unreadable.isEmpty()) {
      throw new Refused(503, "every write is refused while " + unreadable);
    }
    String from = request.exchange.getRemoteAddress().getAddress().getHostAddress();
    Agent.Controller controller = agent.controller();
    if (controller.address().isEmpty()) {
      refusals.log("Refused a write (" + what + ") from " + from + ": " + controller.why());
      throw new Refused(403, "writes are taken from nobody: " + controller.why());
    }
    if (!from.equals(controller.address())) {
      refusals.log("Refused a write (" + what + ") from " + from + ": not the robot controller");
      throw new Refused(
          403, "only the robot controller (" + controller.address() + ") may " + what);
    }
    if (!may(controller, builtIn)) {
      refusals.log(
          "Refused a write (" + what + ") from " + from + ": a controller only worked out");
      throw new Refused(
          403,
          "this board takes only its built-in actions from a robot controller it works out from"
              + " its own address ("
              + controller.address()
              + "): name the controller, or the team, in "
              + AgentConfig.PATH
              + " to "
              + what);
    }
  }

  /**
   * Whether the controller may make a write: one that's named, any; one only worked out from the
   * board's own address, the start of a built-in action.
   */
  static boolean may(Agent.Controller controller, boolean builtIn) {
    return builtIn || controller.named();
  }

  /** Checks a write's signature, when the board requires them. */
  private void signed(Request request, byte[] bodyHash) throws Refused {
    if (!agent.signatures().required()) {
      return;
    }
    try {
      agent
          .signatures()
          .verify(
              request.method,
              request.path,
              bodyHash,
              request.exchange.getRequestHeaders().getFirst(Protocol.SIGNATURE));
    } catch (Signatures.Rejected e) {
      refusals.log("Refused a write, unsigned or wrongly signed: " + e.getMessage());
      throw new Refused(401, String.valueOf(e.getMessage()));
    }
  }

  /**
   * Reads a write's body into a file, at most {@code max} bytes ({@code 413} past them): its
   * SHA-256, for its signature.
   */
  private static byte[] body(Request request, Path into, long max) throws Refused, IOException {
    MessageDigest digest = Signatures.digest();
    long total = 0;
    try (InputStream in = request.exchange.getRequestBody();
        OutputStream out = Files.newOutputStream(into)) {
      byte[] chunk = new byte[64 * 1024];
      int read;
      while ((read = in.read(chunk)) != -1) {
        total += read;
        if (total > max) {
          throw new Refused(
              413, "the body is larger than the " + max / (1024 * 1024) + " MiB taken");
        }
        digest.update(chunk, 0, read);
        out.write(chunk, 0, read);
      }
    }
    return digest.digest();
  }

  private void action(Request request, String id) throws Refused, IOException {
    writer(request, "run an action", id.startsWith(Describer.CORE + "."));
    Runs.Declared declared =
        agent
            .runs()
            .action(id)
            .orElseThrow(() -> new Refused(404, "this board has no action " + id));
    Path input = agent.runs().input();
    try {
      byte[] hash = body(request, input, Runs.MAX_INPUT);
      signed(request, hash);
      boolean none = declared.action().input() == Spotter.Input.INPUT_NONE;
      if (none && Files.size(input) > 0) {
        throw new Refused(400, id + " takes no input");
      }
      Spotter.Started started = agent.runs().start(id, none ? null : input);
      send(request.exchange, 202, started, request.json);
    } catch (Runs.Refused e) {
      throw new Refused(e.status, String.valueOf(e.getMessage()));
    } finally {
      Files.deleteIfExists(input);
    }
  }

  private void runs(Request request, String[] parts) throws Refused, IOException {
    String run = parts[0];
    if (!Runs.ID.matcher(run).matches()) {
      throw new Refused(404, "no run " + run + " is kept");
    }
    try {
      if (parts.length == 1) {
        if (request.method.equals("DELETE")) {
          writer(request, "cancel a run");
          signed(request, Signatures.sha256(request.exchange.getRequestBody().readNBytes(1024)));
          agent.runs().cancel(run);
          request.exchange.sendResponseHeaders(204, -1);
          return;
        }
        if (!request.method.equals("GET")) {
          request.exchange.getResponseHeaders().set("Allow", "GET, DELETE");
          throw new Refused(405, request.path + " takes GET or DELETE only");
        }
        Spotter.RunState state =
            agent
                .runs()
                .state(run)
                .orElseThrow(() -> new Refused(404, "no run " + run + " is kept"));
        send(request.exchange, 200, state, request.json);
      } else if (parts.length == 2 && parts[1].equals("log")) {
        only(request, "GET");
        Logs.Paging paging = paging(request);
        send(request.exchange, 200, agent.runs().log(run, paging), request.json);
      } else if (parts.length == 3 && parts[1].equals("files")) {
        only(request, "GET");
        download(request.exchange, agent.runs().file(run, name(parts[2])));
      } else {
        throw new Refused(404, "no such resource: " + request.path);
      }
    } catch (Runs.Refused e) {
      throw new Refused(e.status, String.valueOf(e.getMessage()));
    }
  }

  private void push(Request request) throws Refused, IOException {
    writer(request, "push packs");
    if (!agent.configuration().acceptsPushes()) {
      throw new Refused(403, "this board refuses pushes: " + agent.configuration().pushesRefused());
    }
    if (!pushes.tryAcquire()) {
      throw new Refused(503, "a push is under way");
    }
    Path bundle = null;
    try {
      bundle = agent.push().bundle();
      byte[] hash = body(request, bundle, Push.MAX_BUNDLE);
      signed(request, hash);
      agent.push().apply(bundle);
    } catch (Push.Rejected e) {
      throw new Refused(400, String.valueOf(e.getMessage()));
    } catch (IOException e) {
      agent.log("A push failed: " + e);
      throw new Refused(500, "the push failed, and the packs are as they were: " + e.getMessage());
    } finally {
      if (bundle != null) {
        Files.deleteIfExists(bundle);
      }
      pushes.release();
    }
    request.exchange.sendResponseHeaders(202, -1);
    request.exchange.close();
    Thread exit =
        new Thread(
            () -> {
              try {
                Thread.sleep(EXIT_DELAY.toMillis());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              agent.exit();
            },
            "spotter-restart");
    exit.setDaemon(true);
    exit.start();
  }

  private static Logs.Paging paging(Request request) throws Refused {
    try {
      return Logs.Paging.of(query(request.exchange.getRequestURI().getRawQuery()));
    } catch (Logs.Refused e) {
      throw new Refused(e.status, String.valueOf(e.getMessage()));
    }
  }

  // ---- answers ----

  /** Whether a request asks for JSON rather than protobuf. */
  static boolean wantsJson(@Nullable String accept) {
    if (accept == null) {
      return false;
    }
    String asked = accept.toLowerCase(Locale.ROOT);
    return asked.contains(Protocol.JSON) && !asked.contains(Protocol.PROTOBUF);
  }

  private static Spotter.Problem problem(String message) {
    return Spotter.Problem.newInstance().setMessage(message);
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

  /** Sends a message, in protobuf or in JSON. */
  private static void send(HttpExchange exchange, int status, ProtoMessage<?> message, boolean json)
      throws IOException {
    byte[] bytes;
    if (json) {
      JsonSink sink = JsonSink.newPrettyInstance();
      sink.writeMessage(message);
      bytes = sink.getBytes().toArray();
      exchange.getResponseHeaders().set("Content-Type", Protocol.JSON + "; charset=utf-8");
    } else {
      bytes = message.toByteArray();
      exchange.getResponseHeaders().set("Content-Type", Protocol.PROTOBUF);
    }
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  /** Sends a run's file field, as its bytes. */
  private static void download(HttpExchange exchange, Runs.Download file) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
    exchange
        .getResponseHeaders()
        .set(
            "Content-Disposition",
            "attachment; filename=\"" + file.name().replaceAll("[\"\\\\\\r\\n]", "_") + "\"");
    long size = Files.size(file.path());
    exchange.sendResponseHeaders(200, size == 0 ? -1 : size);
    if (size > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        Files.copy(file.path(), out);
      }
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
}
