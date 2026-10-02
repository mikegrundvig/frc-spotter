package com.michaelgrundvig.frc.spotter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Asking an agent over HTTP, within the timeouts and a size limit, against a local stand-in. */
class AgentHttpTest {
  private HttpServer server;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/stamp",
        exchange -> {
          byte[] body = "{\"name\":\"vision-front\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v1/big",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(new byte[100_000]);
          }
        });
    server.createContext(
        "/v1/slow",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < 20; i++) {
              out.write(new byte[10_000]);
              out.flush();
              Thread.sleep(50);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (IOException gone) {
            // The client gave up, as it should.
          }
        });
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private AgentHttp agent(double answerSeconds, int maxBytes) {
    return new AgentHttp("127.0.0.1", server.getAddress().getPort(), 0.25, answerSeconds, maxBytes);
  }

  @Test
  void getsAnAnswersBody() throws IOException {
    byte[] body = agent(0.5, 1024).get("/v1/stamp");

    assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"vision-front\"}");
  }

  @Test
  void anAnswerOtherThan200IsNone() {
    assertThatThrownBy(() -> agent(0.5, 1024).get("/v1/nothing"))
        .isInstanceOf(IOException.class)
        .hasMessage("answered 404 to /v1/nothing");
  }

  @Test
  void anAnswerTooLongIsRefused() {
    assertThatThrownBy(() -> agent(0.5, 1024).get("/v1/big"))
        .isInstanceOf(IOException.class)
        .hasMessage("answered more than 1024 bytes");
  }

  @Test
  void aWholeAnswerMustArriveInTime() {
    assertThatThrownBy(() -> agent(0.3, 1_000_000).get("/v1/slow"))
        .isInstanceOf(SocketTimeoutException.class)
        .hasMessage("no whole answer within 300 ms");
  }

  @Test
  void nobodyListeningIsNoAnswer() throws IOException {
    int free;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      free = socket.getLocalPort();
    }
    AgentHttp nobody = new AgentHttp("127.0.0.1", free, 0.25, 0.5, 1024);

    assertThatThrownBy(() -> nobody.get("/v1/stamp")).isInstanceOf(IOException.class);
    assertThat(nobody.authority()).isEqualTo("127.0.0.1:" + free);
  }

  @Test
  void aTimeoutIsSeconds() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new AgentHttp("127.0.0.1", 5808, 0, 0.5, 1024));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> agent(0.5, 1024).get("v1/stamp"))
        .withMessageContaining("starts with /");
  }
}
