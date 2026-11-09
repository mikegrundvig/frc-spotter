package com.michaelgrundvig.frc.spotter.manager.requests;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.michaelgrundvig.frc.spotter.protocol.WireCheck;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;

/**
 * One board's requests besides its stream: a log page, an action started, a run cancelled or
 * fetched, packs pushed. Each runs on one of the board's request threads, never the loop's, with
 * the JDK's HTTP client; each answer's protocol version is checked before its body is read. Each is
 * bounded: a connection's own timeouts, and an overall {@link #DEADLINE_MILLIS}, past which its
 * connection is dropped and its answer refused within one more read, so an agent that answers a
 * byte at a time can't hold a thread. Writes are signed when the manager has a key, over the
 * board's current stream connection's challenge, one at a time so their counters arrive in order.
 */
public final class AgentClient {
  /** How long a request may wait for each read of its answer, once connected. */
  static final int TIMEOUT_MILLIS = 10_000;

  /** The longest a request may take, from connecting to the end of its answer. */
  static final long DEADLINE_MILLIS = Protocol.REQUEST_SECONDS * 1000L;

  /**
   * The most an answer may be, in bytes: a page of a thousand log entries, or a run's file (at most
   * 1 MiB), is well within it. A longer one is refused.
   */
  static final int MAX_ANSWER = 8 << 20;

  /** A request that wasn't answered as hoped: an HTTP status, or no answer at all. */
  public static final class Refused extends IOException {
    private static final long serialVersionUID = 1L;

    /** Its status, or 0 when nothing answered. */
    final int status;

    Refused(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  /** Where a signed write's challenge and counter come from: the board's stream connection. */
  public interface Challenges {
    /** The current connection's challenge; empty while there's none. */
    String challenge();

    /** The next counter on that connection, from 1. */
    long next();
  }

  /** The most of a refusal's body read, in bytes. */
  private static final int MAX_PROBLEM = 64 << 10;

  /**
   * Where an agent is.
   *
   * @param host its name or IP address (an IPv6 address without brackets)
   * @param port its port
   * @param authority its host and port, as a URL has them
   */
  public record Address(String host, int port, String authority) {}

  /**
   * Where an agent is, from its address as robot code gives it ({@code 10.12.34.11}, {@code
   * vision-front:5808}, or an IPv6 address, in brackets with a port): on {@link Protocol#PORT}
   * unless it names one.
   */
  public static Address address(String given) {
    String authority = given;
    boolean bracketed = given.startsWith("[");
    int colons = given.length() - given.replace(":", "").length();
    boolean hasPort = bracketed ? given.contains("]:") : colons == 1;
    if (!hasPort) {
      authority = (colons > 1 && !bracketed ? "[" + given + "]" : given) + ":" + Protocol.PORT;
    }
    try {
      URI uri = new URI("http://" + authority + "/");
      String host = uri.getHost();
      if (host == null || uri.getPort() < 0 || uri.getRawUserInfo() != null) {
        throw new URISyntaxException(given, "not a host and a port");
      }
      if (host.startsWith("[")) {
        host = host.substring(1, host.length() - 1);
      }
      return new Address(host, uri.getPort(), uri.getRawAuthority());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "an agent's address is a host and maybe a port, such as 10.12.34.11 or"
              + " vision-front:5808: "
              + given,
          e);
    }
  }

  /** A URL on an agent: its path, and maybe a query. */
  public static URL url(Address address, String path) {
    try {
      return URI.create("http://" + address.authority() + path).toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("not a URL on " + address.authority() + ": " + path, e);
    }
  }

  /** Whether a version ({@code major.minor}) is this protocol's major version. */
  public static boolean sameMajor(String version) {
    return major(version).equals(major(Protocol.VERSION));
  }

  private static String major(String version) {
    int dot = version.indexOf('.');
    return (dot < 0 ? version : version.substring(0, dot)).strip();
  }

  /** What a refusal says: its status, and its {@code Problem}'s message. */
  public static String refusal(HttpURLConnection c, int code) {
    String message = "";
    try (InputStream body = c.getErrorStream()) {
      if (body != null) {
        message =
            parse(WireCheck.PROBLEM, Spotter.Problem.newInstance(), body.readNBytes(MAX_PROBLEM))
                .getMessage();
      }
    } catch (IOException e) {
      // No reason given, or none that could be read: the status says enough.
    }
    return "it answered " + code + (message.isEmpty() ? "" : ": " + message);
  }

  /**
   * Why something failed, in a few words: a connection's failure in plain ones ("connection
   * refused: is frc-spotter running on it?"), else its message, or its kind without one.
   */
  public static String why(Throwable e) {
    Throwable cause = e.getCause();
    if (!(e instanceof CompletionException) || cause == null) {
      cause = e;
    }
    String message = cause.getMessage();
    if (cause instanceof java.net.UnknownHostException) {
      return "its name doesn't resolve" + (message == null ? "" : " (" + message.strip() + ")");
    }
    if (cause instanceof java.net.ConnectException) {
      return "connection refused: is frc-spotter running on it?";
    }
    if (cause instanceof java.net.NoRouteToHostException) {
      return "no route to it: is it on the robot's network?";
    }
    if (cause instanceof java.net.SocketTimeoutException) {
      return "no answer in time: is it on the robot's network?";
    }
    return message == null || message.isBlank()
        ? cause.getClass().getSimpleName()
        : message.strip();
  }

  /**
   * A message from an answer's bytes, checked before it's parsed ({@link WireCheck}).
   *
   * @throws WireCheck.Malformed when the bytes can't be parsed safely
   */
  public static <T extends ProtoMessage<T>> T parse(WireCheck.Type type, T message, byte[] bytes)
      throws IOException {
    WireCheck.check(type, bytes);
    return ProtoMessage.mergeFrom(message, bytes);
  }

  private final Address address;
  private final int connectTimeout;
  private final @Nullable Signer signer;
  private final Challenges challenges;
  private final @Nullable ScheduledExecutorService deadlines;
  private final long deadlineMillis;
  private final Object writes = new Object();
  private final Set<Exchange> open = new HashSet<>();
  private boolean closed;

  /**
   * @param deadlines where each request's deadline is kept; null for none (a test's)
   */
  public AgentClient(
      Address address,
      int connectTimeout,
      @Nullable Signer signer,
      Challenges challenges,
      @Nullable ScheduledExecutorService deadlines) {
    this(address, connectTimeout, signer, challenges, deadlines, DEADLINE_MILLIS);
  }

  /**
   * @param deadlineMillis the longest a request may take: {@link #DEADLINE_MILLIS}, or a test's
   */
  public AgentClient(
      Address address,
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
  public byte[] get(String pathAndQuery) throws IOException {
    return send("GET", pathAndQuery, null, "", null);
  }

  /**
   * A write, signed when there's a key and a challenge: its answer's body, for any {@code 2xx}.
   *
   * @param path its path, which is what's signed (with no query)
   */
  public byte[] write(String method, String path, byte @Nullable [] body, String type)
      throws IOException {
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
        (HttpURLConnection) url(address, pathAndQuery).openConnection(Proxy.NO_PROXY);
    Exchange exchange = new Exchange(c);
    synchronized (open) {
      if (closed) {
        throw new Refused(0, "the manager is closed");
      }
      open.add(exchange);
    }
    AtomicBoolean late = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        deadlines == null
            ? null
            : deadlines.schedule(
                () -> {
                  late.set(true);
                  exchange.drop();
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
      }
      // Connected first, and only then sent: a drop before this point (the manager closing as it
      // connects) is seen here, so nothing is sent after the manager closed.
      c.connect();
      if (!exchange.connected()) {
        throw new IOException("dropped");
      }
      if (body != null) {
        try (OutputStream out = c.getOutputStream()) {
          out.write(body);
        }
      }
      int code = c.getResponseCode();
      String version = c.getHeaderField(Protocol.HEADER);
      if (version == null || !sameMajor(version)) {
        throw new Refused(
            code,
            version == null
                ? "it answers without Spotter's protocol"
                : "it speaks Spotter protocol " + version + ", the robot " + Protocol.VERSION);
      }
      if (code / 100 != 2) {
        throw new Refused(code, refusal(c, code));
      }
      // Read a chunk at a time, a drop (the deadline, the manager closing) checked between them:
      // each read ends within its timeout.
      if (!exchange.reading()) {
        throw new IOException("dropped");
      }
      try (InputStream in = c.getInputStream()) {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
          if (exchange.dropped()) {
            throw new IOException("dropped");
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
          0,
          late.get()
              ? "no complete answer within " + deadlineMillis + " ms"
              : exchange.dropped() ? "the manager is closed" : why(e));
    } finally {
      if (deadline != null) {
        deadline.cancel(false);
      }
      synchronized (open) {
        open.remove(exchange);
      }
      c.disconnect();
    }
  }

  /**
   * A request's connection, which its deadline or the manager's close drops.
   *
   * <ul>
   *   <li>Before it's connected, a drop only marks it: the JDK's {@code disconnect} does nothing to
   *       a connection not made yet, so the request sees the drop once it's connected ({@link
   *       #connected}), and sends nothing.
   *   <li>Once connected, until its answer's body is read, a drop disconnects it, which ends a wait
   *       for the answer's head.
   *   <li>From then on, the read sees the drop between its reads, and nothing else touches the
   *       connection: a disconnect then hands the stream to the JDK's keep-alive cleaner, which
   *       holds it while it reads what's left, so a read waiting on it would wait as long as the
   *       board takes to send it all.
   * </ul>
   *
   * <p>Robot code never waits on any of this: the manager's close finishes every request's future
   * itself ({@link Link#close}), whatever its thread is doing.
   */
  private static final class Exchange {
    private final HttpURLConnection c;
    private boolean dropped;
    private boolean connected;
    private boolean reading;

    Exchange(HttpURLConnection c) {
      this.c = c;
    }

    synchronized void drop() {
      dropped = true;
      if (connected && !reading) {
        c.disconnect();
      }
    }

    /** It's connected, and a drop from here on disconnects it: false if it's dropped already. */
    synchronized boolean connected() {
      connected = true;
      return !dropped;
    }

    /** Its body is read from here on: false if it's dropped already. */
    synchronized boolean reading() {
      reading = true;
      return !dropped;
    }

    synchronized boolean dropped() {
      return dropped;
    }
  }

  /** Drops every request under way, and refuses any more: the manager is closing. */
  void close() {
    synchronized (open) {
      closed = true;
      for (Exchange exchange : open) {
        exchange.drop();
      }
      open.clear();
    }
  }
}
