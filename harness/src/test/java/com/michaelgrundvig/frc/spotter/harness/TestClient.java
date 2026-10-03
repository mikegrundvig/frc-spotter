package com.michaelgrundvig.frc.spotter.harness;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

/**
 * A minimal client of protocol 2, playing the robot in Spotter's own container tests: it asks in
 * protobuf, checks the version header before it reads a byte, reads answers and the stream with the
 * generated messages, and signs writes as docs/agent.md says. The robot's manager, which does all
 * this for a robot program, is coming.
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

  /** Every value now, by its id in the description. */
  Map<String, Spotter.FieldValue> valuesById() throws IOException {
    Spotter.Description description = describe();
    Spotter.Values values = values();
    Map<String, Spotter.FieldValue> byId = new LinkedHashMap<>();
    for (Spotter.FieldValue value : values.getValues()) {
      byId.put(description.getValues().get(value.getIndex()).getId(), value);
    }
    return byId;
  }

  /**
   * Asks for a message.
   *
   * @throws IOException when it can't be asked, or the answer isn't {@code 200} on protocol 2
   */
  <T extends ProtoMessage<T>> T get(String path, T message) throws IOException {
    HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri(path)).GET());
    if (response.statusCode() != 200) {
      throw new IOException(
          path + " answered " + response.statusCode() + ": " + problem(response).getMessage());
    }
    return ProtoMessage.mergeFrom(message, response.body());
  }

  /** Writes: a POST of a body, signed when a signer is given. */
  HttpResponse<byte[]> post(String path, byte[] body, @Nullable Signed signed) throws IOException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
    if (signed != null) {
      request.header(Protocol.SIGNATURE, signed.header("POST", path, body));
    }
    return send(request);
  }

  /** Writes: a POST of a file. */
  HttpResponse<byte[]> post(String path, Path body) throws IOException {
    return send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofFile(body)));
  }

  /** Writes: a DELETE, signed when a signer is given. */
  HttpResponse<byte[]> delete(String path, @Nullable Signed signed) throws IOException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).DELETE();
    if (signed != null) {
      request.header(Protocol.SIGNATURE, signed.header("DELETE", path, new byte[0]));
    }
    return send(request);
  }

  /** Starts an action, its run's id. */
  String start(String action, byte[] input) throws IOException {
    HttpResponse<byte[]> started = post(Protocol.ACTIONS + action, input, null);
    if (started.statusCode() != 202) {
      throw new IOException(
          action + " answered " + started.statusCode() + ": " + problem(started).getMessage());
    }
    return ProtoMessage.mergeFrom(Spotter.Started.newInstance(), started.body()).getRun();
  }

  /** A run, once it's finished. */
  Spotter.RunState finished(String run) throws IOException, InterruptedException {
    for (int i = 0; i < 300; i++) {
      Spotter.RunState state = get(Protocol.RUNS + run, Spotter.RunState.newInstance());
      if (!state.getRunning()) {
        return state;
      }
      Thread.sleep(100);
    }
    throw new IOException("run " + run + " didn't finish within 30 s");
  }

  /** Where the agent is: {@code http://host:port}. */
  String base() {
    return base;
  }

  private URI uri(String path) {
    return URI.create(base + path);
  }

  /** The answer to a request, its version checked. */
  HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException {
    try {
      HttpResponse<byte[]> response =
          http.send(
              request.header("Accept", Protocol.PROTOBUF).timeout(Duration.ofSeconds(30)).build(),
              HttpResponse.BodyHandlers.ofByteArray());
      check(response);
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private static void check(HttpResponse<?> response) throws IOException {
    String version = response.headers().firstValue(Protocol.HEADER).orElse("none");
    if (!version.equals(Protocol.VERSION)) {
      throw new IOException("the agent speaks protocol " + version + ", not " + Protocol.VERSION);
    }
  }

  /** A refusal's or a failure's Problem. */
  static Spotter.Problem problem(HttpResponse<byte[]> response) throws IOException {
    return ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), response.body());
  }

  /**
   * Opens the stream, as the manager keeps it open: its events, each with when it arrived, read on
   * a thread of its own. Its status and headers are the response's; a refused stream reads none.
   */
  Stream stream(String query) throws IOException {
    try {
      HttpResponse<InputStream> response =
          http.send(
              HttpRequest.newBuilder(uri(Protocol.STREAM + query))
                  .header("Accept", Protocol.PROTOBUF)
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      check(response);
      return new Stream(response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  /** An event as the robot got it, and when, on its own clock. */
  record Got(Spotter.Event event, long nanos) {}

  /** An open stream. */
  static final class Stream implements AutoCloseable {
    final HttpResponse<InputStream> response;
    final BlockingQueue<Got> events = new LinkedBlockingQueue<>();
    volatile boolean ended;
    volatile long last = System.nanoTime();
    private final Thread reader;
    private byte[] refused = new byte[0];

    Stream(HttpResponse<InputStream> response) throws IOException {
      this.response = response;
      if (response.statusCode() != 200) {
        refused = response.body().readAllBytes();
        reader = new Thread(() -> {});
        ended = true;
        return;
      }
      reader =
          new Thread(
              () -> {
                try {
                  ProtoSource source = ProtoSource.newInstance(response.body());
                  while (true) {
                    Spotter.Event event = Spotter.Event.newInstance().mergeDelimitedFrom(source);
                    last = System.nanoTime();
                    events.add(new Got(event, last));
                  }
                } catch (IOException | RuntimeException e) {
                  ended = true;
                }
              },
              "test-stream");
      reader.setDaemon(true);
      reader.start();
    }

    int status() {
      return response.statusCode();
    }

    /** The challenge to sign writes over, on this connection. */
    String challenge() {
      return response.headers().firstValue(Protocol.CHALLENGE).orElse("");
    }

    /** A refused stream's Problem. */
    Spotter.Problem problem() throws IOException {
      return ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), refused);
    }

    /** The next event, waited for a while. */
    Got next(Duration within) throws InterruptedException, IOException {
      Got got = events.poll(within.toMillis(), TimeUnit.MILLISECONDS);
      if (got == null) {
        throw new IOException("no event within " + within + (ended ? ": the stream ended" : ""));
      }
      return got;
    }

    /** The next event that isn't a heartbeat. */
    Spotter.Event nextNotHeartbeat(Duration within) throws InterruptedException, IOException {
      long until = System.nanoTime() + within.toNanos();
      while (true) {
        Got got = next(Duration.ofNanos(Math.max(1, until - System.nanoTime())));
        if (!got.event().hasHeartbeat()) {
          return got.event();
        }
      }
    }

    @Override
    public void close() throws IOException {
      response.body().close();
      reader.interrupt();
    }
  }

  /** Writes signed with a key, over one connection's challenge, its counter rising from 1. */
  static final class Signed {
    final KeyPair keys;
    final String publicKey;
    final String keyId;
    String challenge = "";
    long counter;

    Signed() throws Exception {
      keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
      keyId =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(keys.getPublic().getEncoded()))
              .substring(0, 16);
    }

    /** Signs over a new connection's challenge, its counter from 1 again. */
    Signed over(String challenge) {
      this.challenge = challenge;
      this.counter = 0;
      return this;
    }

    /** The next write's header, as docs/agent.md's "Signed writes" says. */
    String header(String method, String path, byte[] body) throws IOException {
      return header(++counter, method, path, body);
    }

    /** A write's header with a counter given. */
    String header(long count, String method, String path, byte[] body) throws IOException {
      try {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String signed = challenge + "\n" + count + "\n" + method + "\n" + path + "\n" + hash;
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(signed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return keyId + " " + count + " " + Base64.getEncoder().encodeToString(signer.sign());
      } catch (java.security.GeneralSecurityException e) {
        throw new IOException(e);
      }
    }
  }
}
