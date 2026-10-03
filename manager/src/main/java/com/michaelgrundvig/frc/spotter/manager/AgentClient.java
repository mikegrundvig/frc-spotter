package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * One board's requests besides its stream: a log page, an action started, a run cancelled or
 * fetched, packs pushed. Each runs on one of the board's request threads, never the loop's, with
 * the JDK's HTTP client; each answer's protocol version is checked before its body is read. Each is
 * bounded: a connection's own timeouts, and an overall {@link #DEADLINE_MILLIS}, past which its
 * connection is dropped and its answer refused within one more read, so an agent that answers a
 * byte at a time can't hold a thread. Writes are signed when the manager has a key, over the
 * board's current stream connection's challenge, one at a time so their counters arrive in order.
 */
final class AgentClient {
  /** How long a request may wait for each read of its answer, once connected. */
  static final int TIMEOUT_MILLIS = 10_000;

  /**
   * The longest a request may take, from connecting to the end of its answer: a push or an action's
   * input sends at most 64 MiB, a few seconds on the robot's wired network.
   */
  static final long DEADLINE_MILLIS = 30_000;

  /**
   * The most an answer may be, in bytes: a page of a thousand log entries, or a run's file (at most
   * 1 MiB), is well within it. A longer one is refused.
   */
  static final int MAX_ANSWER = 8 << 20;

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
  private final @Nullable ScheduledExecutorService deadlines;
  private final long deadlineMillis;
  private final Object writes = new Object();
  private final Set<HttpURLConnection> open = new HashSet<>();
  private boolean closed;

  /**
   * @param deadlines where each request's deadline is kept; null for none (a test's)
   */
  AgentClient(
      Link.Address address,
      int connectTimeout,
      @Nullable Signer signer,
      Challenges challenges,
      @Nullable ScheduledExecutorService deadlines) {
    this(address, connectTimeout, signer, challenges, deadlines, DEADLINE_MILLIS);
  }

  /**
   * @param deadlineMillis the longest a request may take: {@link #DEADLINE_MILLIS}, or a test's
   */
  AgentClient(
      Link.Address address,
      int connectTimeout,
      @Nullable Signer signer,
      Challenges challenges,
      @Nullable ScheduledExecutorService deadlines,
      long deadlineMillis) {
    this.address = address;
    this.connectTimeout = connectTimeout;
    this.signer = signer;
    this.challenges = challenges;
    this.deadlines = deadlines;
    this.deadlineMillis = deadlineMillis;
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
    synchronized (open) {
      if (closed) {
        throw new Refused(0, "the manager is closed");
      }
      open.add(c);
    }
    AtomicBoolean late = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        deadlines == null
            ? null
            : deadlines.schedule(
                () -> {
                  late.set(true);
                  c.disconnect();
                },
                deadlineMillis,
                TimeUnit.MILLISECONDS);
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
      // Read a chunk at a time, the deadline checked between them: dropping the connection
      // doesn't stop a read of the JDK's that's under way, but each read ends within its timeout.
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
          if (late.get()) {
            throw new IOException("past its deadline");
          }
          answer.write(chunk, 0, read);
          if (answer.size() > MAX_ANSWER) {
            throw new Refused(
                code, "its answer is past the " + (MAX_ANSWER >> 20) + " MiB the manager takes");
          }
        }
        return answer.toByteArray();
      }
    } catch (Refused e) {
      throw e;
    } catch (IOException e) {
      throw new Refused(
          0, late.get() ? "no complete answer within " + deadlineMillis + " ms" : Link.why(e));
    } finally {
      if (deadline != null) {
        deadline.cancel(false);
      }
      synchronized (open) {
        open.remove(c);
      }
      c.disconnect();
    }
  }

  /** Drops every request under way, and refuses any more: the manager is closing. */
  void close() {
    synchronized (open) {
      closed = true;
      for (HttpURLConnection c : open) {
        c.disconnect();
      }
      open.clear();
    }
  }
}
