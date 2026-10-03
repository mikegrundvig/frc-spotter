package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.JsonSource;
import us.hebi.quickbuf.ProtoMessage;

/** Protocol 2 on the wire: protobuf unless JSON is asked for, and its version on every answer. */
class AgentServerTest {
  @TempDir Path dir;
  Fixture fixture;
  AgentServer server;
  final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void anAgent() throws Exception {
    fixture = new Fixture(dir);
    String folder =
        fixture.pack(
            "vision",
            """
            pack: vision
            collectors:
              - id: health
                run: [./health]
                every: 1h
                fields:
                  fps: {type: number}
            """);
    fixture.script(folder + "/health", "echo 29.5");
    Agent agent = fixture.agent();
    agent.collectors().runAll();
    server = new AgentServer(agent, new InetSocketAddress("127.0.0.1", 0));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpResponse<byte[]> get(String path, String accept) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (!accept.isEmpty()) {
      request.header("Accept", accept);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  /** The status of a request with this Host, which Java's own client won't send: by hand. */
  private int statusWithHost(String host) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket
          .getOutputStream()
          .write(
              ("GET "
                      + Protocol.DESCRIBE
                      + " HTTP/1.1\r\nHost: "
                      + host
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      String status =
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)
              .lines()
              .findFirst()
              .orElse("");
      return Integer.parseInt(status.split(" ")[1]);
    }
  }

  private static <T extends ProtoMessage<T>> T read(T message, HttpResponse<byte[]> response)
      throws Exception {
    return ProtoMessage.mergeFrom(message, response.body());
  }

  @Test
  void itDescribesItselfInProtobufByDefault() throws Exception {
    HttpResponse<byte[]> response = get(Protocol.DESCRIBE, "");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue(Protocol.HEADER)).hasValue("2.0");
    assertThat(response.headers().firstValue("Content-Type")).hasValue(Protocol.PROTOBUF);
    Spotter.Description description = read(Spotter.Description.newInstance(), response);
    assertThat(description.getIdentity().getHostname()).isEqualTo("vision-front");
    assertThat(description.getValues().get(0).getId()).isEqualTo("vision.health.fps");
    HttpResponse<byte[]> asked = get(Protocol.DESCRIBE, Protocol.PROTOBUF);
    assertThat(read(Spotter.Description.newInstance(), asked)).isEqualTo(description);
  }

  @Test
  void itAnswersInQuickBuffersJsonWhenAsked() throws Exception {
    HttpResponse<byte[]> response = get(Protocol.VALUES, "application/json, */*");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValue("application/json; charset=utf-8");
    String json = new String(response.body(), StandardCharsets.UTF_8);
    assertThat(json).contains("\"complete\": true", "\"number\": 29.5");
    Spotter.Values values =
        Spotter.Values.newInstance().mergeFrom(JsonSource.newInstance(response.body()));
    assertThat(values.getValues().get(0).getNumber()).isEqualTo(29.5);
    Spotter.Values protobuf = read(Spotter.Values.newInstance(), get(Protocol.VALUES, ""));
    assertThat(protobuf.getRevision()).isEqualTo(values.getRevision());
    assertThat(protobuf.getValues()).isEqualTo(values.getValues());
  }

  @Test
  void refusalsAreProblemsWithTheVersionToo() throws Exception {
    HttpResponse<byte[]> missing = get("/v1/health", "");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.headers().firstValue(Protocol.HEADER)).hasValue("2.0");
    assertThat(read(Spotter.Problem.newInstance(), missing).getMessage())
        .isEqualTo("no such resource: /v1/health");
    HttpResponse<byte[]> json = get("/v2/nothing", Protocol.JSON);
    assertThat(new String(json.body(), StandardCharsets.UTF_8))
        .contains("\"message\": \"no such resource: /v2/nothing\"");

    HttpResponse<String> posted =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + Protocol.DESCRIBE))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Accept", Protocol.JSON)
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(posted.statusCode()).isEqualTo(405);
    assertThat(posted.headers().firstValue("Allow")).hasValue("GET");
    assertThat(posted.headers().firstValue(Protocol.HEADER)).hasValue("2.0");
  }

  @Test
  void itAnswersOnlyRequestsAddressedToIt() throws Exception {
    for (String host :
        List.of(
            "10.12.34.11:5808",
            "localhost",
            "vision-front",
            "VISION-FRONT.local",
            "[::1]:5808",
            "169.254.3.4")) {
      assertThat(statusWithHost(host)).as(host).isEqualTo(200);
    }
    assertThat(statusWithHost("attacker.example.com")).isEqualTo(421);
    assertThat(statusWithHost("[fe80::1]")).isEqualTo(421);
  }

  @Test
  void jsonIsAskedForByAccept() {
    assertThat(AgentServer.wantsJson(null)).isFalse();
    assertThat(AgentServer.wantsJson("Application/JSON")).isTrue();
    assertThat(AgentServer.wantsJson("text/html,*/*;q=0.8")).isFalse();
    assertThat(AgentServer.wantsJson("application/x-protobuf, application/json;q=0.1")).isFalse();
  }
}
