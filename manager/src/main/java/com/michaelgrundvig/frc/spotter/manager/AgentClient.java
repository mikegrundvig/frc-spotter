package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import org.jspecify.annotations.Nullable;

/**
 * One board's requests besides its stream: a log page, an action started, a run cancelled or
 * fetched, packs pushed. Each runs on a thread of the manager's, never the loop's, with the JDK's
 * HTTP client; each answer's protocol version is checked before its body is read. Writes are signed
 * when the manager has a key, over the board's current stream connection's challenge, one at a time
 * so their counters arrive in order.
 */
final class AgentClient {
  /** How long a request may take to answer, once connected. */
  static final int TIMEOUT_MILLIS = 10_000;

  /** The most of an answer read, in bytes: a run's file is the largest, at 1 MB. */
  static final int MAX_ANSWER = 16 << 20;

  /** A request that wasn't answered as hoped: an HTTP status, or no answer at all. */
  static final class Refused extends IOException {
    private static final long serialVersionUID = 1L;

    /** Its status, or 0 when nothing answered. */
    final int status;

    Refused(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  /** Where a signed write's challenge and counter come from: the board's stream connection. */
  interface Challenges {
    /** The current connection's challenge; empty while there's none. */
    String challenge();

    /** The next counter on that connection, from 1. */
    long next();
  }

  private final Link.Address address;
  private final int connectTimeout;
  private final @Nullable Signer signer;
  private final Challenges challenges;
  private final Object writes = new Object();

  AgentClient(
      Link.Address address, int connectTimeout, @Nullable Signer signer, Challenges challenges) {
    this.address = address;
    this.connectTimeout = connectTimeout;
    this.signer = signer;
    this.challenges = challenges;
  }

  /** A read: its answer's body, for a {@code 200}. */
  byte[] get(String pathAndQuery) throws IOException {
    return send("GET", pathAndQuery, null, "", null);
  }

  /**
   * A write, signed when there's a key and a challenge: its answer's body, for any {@code 2xx}.
   *
   * @param path its path, which is what's signed (with no query)
   */
  byte[] write(String method, String path, byte @Nullable [] body, String type) throws IOException {
    synchronized (writes) {
      String signature = null;
      String challenge = challenges.challenge();
      if (signer != null && !challenge.isEmpty()) {
        signature =
            signer.header(
                challenge, challenges.next(), method, path, body == null ? new byte[0] : body);
      }
      return send(method, path, body, type, signature);
    }
  }

  private byte[] send(
      String method,
      String pathAndQuery,
      byte @Nullable [] body,
      String type,
      @Nullable String signature)
      throws IOException {
    HttpURLConnection c =
        (HttpURLConnection) Link.url(address, pathAndQuery).openConnection(Proxy.NO_PROXY);
    try {
      c.setConnectTimeout(connectTimeout);
      c.setReadTimeout(TIMEOUT_MILLIS);
      c.setUseCaches(false);
      c.setInstanceFollowRedirects(false);
      c.setRequestMethod(method);
      c.setRequestProperty("Accept", Protocol.PROTOBUF);
      if (signature != null) {
        c.setRequestProperty(Protocol.SIGNATURE, signature);
      }
      if (body != null) {
        // Not streamed: in streaming mode the JDK's client gives a 401's body (the agent's reason)
        // no error stream. A body is at most a bundle of packs, a few MiB, held once more.
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", type);
        try (OutputStream out = c.getOutputStream()) {
          out.write(body);
        }
      }
      int code = c.getResponseCode();
      String version = c.getHeaderField(Protocol.HEADER);
      if (version == null || !Link.sameMajor(version)) {
        throw new Refused(
            code,
            version == null
                ? "it answers without Spotter's protocol"
                : "it speaks Spotter protocol " + version + ", the robot " + Protocol.VERSION);
      }
      if (code / 100 != 2) {
        throw new Refused(code, Link.refusal(c, code));
      }
      try (InputStream in = c.getInputStream()) {
        return in.readNBytes(MAX_ANSWER);
      }
    } catch (Refused e) {
      throw e;
    } catch (IOException e) {
      throw new Refused(0, Link.why(e));
    } finally {
      c.disconnect();
    }
  }
}
