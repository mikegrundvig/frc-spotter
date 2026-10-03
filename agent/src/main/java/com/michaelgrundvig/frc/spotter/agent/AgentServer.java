package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.JsonSink;
import us.hebi.quickbuf.ProtoMessage;

/**
 * The agent's protocol, version 2, on the JDK's own server: every answer a {@code spotter.proto}
 * message, in protobuf, or in QuickBuffers' JSON form of it when the request's {@code Accept} asks
 * for {@code application/json}; every response carries {@code Spotter-Protocol: 2.0}, refusals and
 * failures included, which answer a {@code Problem}. The only class that touches the web server.
 *
 * <p>Each request has a thread of its own, so a client that's slow to send (or never finishes)
 * holds up nobody else. It answers only requests addressed to it (by a robot-network, link-local,
 * or loopback address, or its own name), so a web page elsewhere can't reach it through a name that
 * resolves to it.
 */
final class AgentServer implements AutoCloseable {
  /** What each path answers to a {@code GET}. */
  private final Map<String, Answer> reads;

  /** The robot network (10/8), link-local (169.254/16), and loopback (127/8) addresses. */
  private static final Pattern OWN_ADDRESS =
      Pattern.compile("(10|127)(\\.[0-9]{1,3}){3}|169\\.254(\\.[0-9]{1,3}){2}");

  private final Agent agent;
  private final HttpServer server;

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

  /** What a path answers. */
  private interface Answer {
    ProtoMessage<?> answer() throws IOException;
  }

  /** A request that can't be answered as asked, with its status. */
  private static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;

    Refused(int status, String reason) {
      super(reason);
      this.status = status;
    }
  }

  AgentServer(Agent agent, InetSocketAddress address) throws IOException {
    this.agent = agent;
    this.reads = Map.of(Protocol.DESCRIBE, agent::description, Protocol.VALUES, agent::values);
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
      boolean json = wantsJson(exchange.getRequestHeaders().getFirst("Accept"));
      exchange.getResponseHeaders().set(Protocol.HEADER, Protocol.VERSION);
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      try {
        if (!addressedHere(exchange.getRequestHeaders().getFirst("Host"))) {
          throw new Refused(421, "this agent answers requests addressed to it only");
        }
        Answer read = reads.get(path);
        if (read == null) {
          throw new Refused(404, "no such resource: " + path);
        }
        if (!method.equals("GET")) {
          exchange.getResponseHeaders().set("Allow", "GET");
          throw new Refused(405, path + " takes GET only");
        }
        send(exchange, 200, read.answer(), json);
      } catch (Refused e) {
        send(exchange, e.status, problem(String.valueOf(e.getMessage())), json);
      } catch (IOException | RuntimeException | Error e) {
        // The detail (which may name files) goes to the journal; the answer says only that.
        agent.log("Answering " + method + " " + path + " failed: " + e);
        send(exchange, 500, problem("the agent couldn't answer; its journal says why"), json);
      }
    } catch (IOException e) {
      // The client went away mid-answer, or an answer failed after it began: nothing to do.
    }
  }

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
}
