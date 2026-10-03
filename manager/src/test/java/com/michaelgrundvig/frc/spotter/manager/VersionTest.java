package com.michaelgrundvig.frc.spotter.manager;

import static com.michaelgrundvig.frc.spotter.manager.Boards.await;
import static com.michaelgrundvig.frc.spotter.manager.Boards.value;
import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The protocol's version is checked before a byte of the stream is read: another major version (or
 * none) means the board's values aren't used, with the highest alert naming both versions; another
 * minor version works as usual, and the board's protocol shows it. Each board here is a stand-in
 * that answers with the version a test gives it.
 */
class VersionTest {
  @Nullable HttpServer server;
  final ExecutorService threads = Executors.newCachedThreadPool();
  @Nullable Manager manager;

  @AfterEach
  void stop() {
    if (manager != null) {
      manager.close();
    }
    if (server != null) {
      server.stop(0);
    }
    threads.shutdownNow();
  }

  /** A stand-in board answering its stream with a version, or none; serving a stream on 2.x. */
  private String board(@Nullable String version) throws IOException {
    HttpServer started = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    started.setExecutor(threads);
    started.createContext(Protocol.STREAM, exchange -> answer(exchange, version));
    started.start();
    server = started;
    return "127.0.0.1:" + started.getAddress().getPort();
  }

  private static void answer(HttpExchange exchange, @Nullable String version) throws IOException {
    try (exchange) {
      if (version != null) {
        exchange.getResponseHeaders().set(Protocol.HEADER, version);
      }
      if (version == null || !version.startsWith("2.")) {
        byte[] page = "<html>Not here</html>".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(404, page.length);
        exchange.getResponseBody().write(page);
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", Protocol.PROTOBUF);
      exchange.sendResponseHeaders(200, 0);
      OutputStream out = exchange.getResponseBody();
      Spotter.Description description = Spotter.Description.newInstance().setRevision(7);
      description.getMutableIdentity().setHostname("next-year");
      description.addValues(
          Spotter.FieldDeclaration.newInstance()
              .setId("future.health.fps")
              .setType(Spotter.FieldType.FIELD_TYPE_NUMBER));
      send(out, Spotter.Event.newInstance().setDescribed(description));
      Spotter.Values values = Spotter.Values.newInstance().setRevision(7).setComplete(true);
      values.addValues(Spotter.FieldValue.newInstance().setIndex(0).setNumber(90));
      send(out, Spotter.Event.newInstance().setValues(values));
      while (true) {
        send(
            out,
            Spotter.Event.newInstance()
                .setHeartbeat(Spotter.Heartbeat.newInstance().setTimeNanos(System.nanoTime())));
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }

  /** An event, preceded by its length: protobuf's delimited form. */
  static void send(OutputStream out, Spotter.Event event) throws IOException {
    byte[] bytes = event.toByteArray();
    int length = bytes.length;
    while (length >= 0x80) {
      out.write((length & 0x7f) | 0x80);
      length >>>= 7;
    }
    out.write(length);
    out.write(bytes);
    out.flush();
  }

  private Manager manage(String address) {
    Manager started = new Manager(Boards.robot(), List.of(address));
    manager = started;
    started.start();
    return started;
  }

  @Test
  void anotherMajorVersionIsNotUsedAndRaisesTheHighestAlert() throws Exception {
    String address = board("3.0");
    Manager manager = manage(address);
    Board board = manager.boards().get(0);
    await(manager, "another protocol", () -> board.connection() == Connection.OTHER_PROTOCOL);
    assertThat(board.protocol()).isEqualTo("3.0");
    assertThat(board.values()).isEmpty();
    assertThat(board.description().getRevision()).isZero();
    assertThat(manager.alerts())
        .containsExactly(
            new Alert(
                Level.FAILING,
                address,
                address + ": it speaks Spotter protocol 3.0, the robot " + Protocol.VERSION));
  }

  @Test
  void noVersionAtAllIsAnotherProtocol() throws Exception {
    String address = board(null);
    Manager manager = manage(address);
    Board board = manager.boards().get(0);
    await(manager, "another protocol", () -> board.connection() == Connection.OTHER_PROTOCOL);
    assertThat(board.protocol()).isEmpty();
    assertThat(board.why())
        .isEqualTo("it answers without Spotter's protocol (the robot speaks 2.0)");
    assertThat(manager.alerts()).hasSize(1);
    assertThat(manager.alerts().get(0).level()).isEqualTo(Level.FAILING);
  }

  @Test
  void anotherMinorVersionWorksAsUsualAndShows() throws Exception {
    String address = board("2.7");
    Manager manager = manage(address);
    Board board = manager.boards().get(0);
    await(
        manager,
        "its values",
        () ->
            board.value("future.health.fps") != null
                && value(board, "future.health.fps").available());
    assertThat(board.connection()).isEqualTo(Connection.CONNECTED);
    assertThat(board.protocol()).isEqualTo("2.7");
    assertThat(board.name()).isEqualTo("next-year");
    assertThat(value(board, "future.health.fps").number()).isEqualTo(90);
    assertThat(manager.alerts()).isEmpty();
  }

  @Test
  void aRefusalSaysWhyAndTheBoardIsTriedAgain() throws Exception {
    HttpServer started = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    started.setExecutor(threads);
    started.createContext(
        Protocol.STREAM,
        exchange -> {
          try (exchange) {
            byte[] problem = Spotter.Problem.newInstance().setMessage("not now").toByteArray();
            exchange.getResponseHeaders().set(Protocol.HEADER, Protocol.VERSION);
            exchange.sendResponseHeaders(503, problem.length);
            exchange.getResponseBody().write(problem);
          }
        });
    started.start();
    server = started;
    Manager manager = manage("127.0.0.1:" + started.getAddress().getPort());
    Board board = manager.boards().get(0);
    await(manager, "a refusal", () -> !board.why().isEmpty());
    assertThat(board.why()).isEqualTo("it answered 503: not now");
    assertThat(board.connection()).isNotEqualTo(Connection.CONNECTED);
  }

  @Test
  void theMajorVersionIsWhatsBeforeTheDot() {
    assertThat(Link.sameMajor("2.0")).isTrue();
    assertThat(Link.sameMajor("2.13")).isTrue();
    assertThat(Link.sameMajor("2")).isTrue();
    assertThat(Link.sameMajor("3.0")).isFalse();
    assertThat(Link.sameMajor("20.0")).isFalse();
    assertThat(Link.sameMajor("")).isFalse();
  }
}
