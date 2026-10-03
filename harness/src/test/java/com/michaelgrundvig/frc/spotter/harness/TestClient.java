package com.michaelgrundvig.frc.spotter.harness;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import us.hebi.quickbuf.ProtoMessage;

/**
 * A minimal client of protocol 2, playing the robot in Spotter's own container tests: it asks in
 * protobuf, checks the version header before it reads a byte, and reads the answer with the
 * generated messages. The robot's manager, which does all this for a robot program, is coming.
 */
final class TestClient {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final String base;

  TestClient(String host, int port) {
    this.base = "http://" + host + ":" + port;
  }

  /** The agent's description. */
  Spotter.Description describe() throws IOException {
    return get(Protocol.DESCRIBE, Spotter.Description.newInstance());
  }

  /** Every value now. */
  Spotter.Values values() throws IOException {
    return get(Protocol.VALUES, Spotter.Values.newInstance());
  }

  /**
   * Asks for a message.
   *
   * @throws IOException when it can't be asked, or the answer isn't {@code 200} on protocol 2
   */
  <T extends ProtoMessage<T>> T get(String path, T message) throws IOException {
    HttpResponse<byte[]> response = send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    if (response.statusCode() != 200) {
      throw new IOException(
          path + " answered " + response.statusCode() + ": " + problem(response).getMessage());
    }
    return ProtoMessage.mergeFrom(message, response.body());
  }

  /** The answer to a request, its version checked. */
  HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException {
    try {
      HttpResponse<byte[]> response =
          http.send(
              request.header("Accept", Protocol.PROTOBUF).timeout(Duration.ofSeconds(10)).build(),
              HttpResponse.BodyHandlers.ofByteArray());
      String version = response.headers().firstValue(Protocol.HEADER).orElse("none");
      if (!version.equals(Protocol.VERSION)) {
        throw new IOException("the agent speaks protocol " + version + ", not " + Protocol.VERSION);
      }
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  /** A refusal's or a failure's Problem. */
  static Spotter.Problem problem(HttpResponse<byte[]> response) throws IOException {
    return ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), response.body());
  }
}
