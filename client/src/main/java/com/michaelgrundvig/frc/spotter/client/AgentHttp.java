package com.michaelgrundvig.frc.spotter.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * GETs from a coprocessor's health agent, within a connect timeout and a timeout on the whole
 * answer, and reads at most so many bytes: the agent bounds its answers, and this never trusts it
 * to. Plain {@link HttpURLConnection}, which every Java runtime has, the robot's included, and
 * never through a proxy: the coprocessors are on the robot's own network.
 */
public final class AgentHttp {
  /** How much to read at a time. */
  private static final int CHUNK = 8192;

  private final String host;
  private final int port;
  private final int connectTimeoutMillis;
  private final int answerTimeoutMillis;
  private final int maxBytes;

  /**
   * @param host the coprocessor's address, such as {@code 10.0.0.11}
   * @param port the agent's port
   * @param connectTimeoutSeconds how long to wait for a connection
   * @param answerTimeoutSeconds how long the whole answer may take, once connected
   * @param maxBytes the most an answer may hold: a longer one is refused
   */
  public AgentHttp(
      String host,
      int port,
      double connectTimeoutSeconds,
      double answerTimeoutSeconds,
      int maxBytes) {
    this.host = host;
    this.port = port;
    connectTimeoutMillis = millis(connectTimeoutSeconds);
    answerTimeoutMillis = millis(answerTimeoutSeconds);
    this.maxBytes = maxBytes;
  }

  /** The same agent, with other timeouts and another size limit: for a download, say. */
  public AgentHttp with(double connectTimeoutSeconds, double answerTimeoutSeconds, int maxBytes) {
    return new AgentHttp(host, port, connectTimeoutSeconds, answerTimeoutSeconds, maxBytes);
  }

  /** The agent's address and port, as a URL's authority: {@code 10.0.0.11:5808}. */
  public String authority() {
    return host + ":" + port;
  }

  /**
   * An answer's body.
   *
   * @param pathAndQuery such as {@code /v1/health} or {@code /v1/journal?priority=3}
   * @throws IOException saying why there's none: no connection or answer in time, an answer other
   *     than 200, or one too long
   */
  public byte[] get(String pathAndQuery) throws IOException {
    HttpURLConnection connection = open(pathAndQuery);
    long deadline = System.nanoTime() + answerTimeoutMillis * 1_000_000L;
    try {
      int status = connection.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) {
        throw new Answered(status, pathAndQuery, connection.getHeaderField("Retry-After"));
      }
      try (InputStream in = connection.getInputStream()) {
        return readBounded(in, deadline);
      }
    } finally {
      connection.disconnect();
    }
  }

  /**
   * An answer's body, to read as it arrives, with its length when the agent gave it (-1 when not):
   * for a download passed on without being held whole. Reading it past the size limit or the
   * timeout on the whole answer fails; closing it closes the connection.
   *
   * @throws IOException saying why there's none: no connection or answer in time, an answer other
   *     than 200, or one longer than the limit by its own length
   */
  public Streamed stream(String pathAndQuery) throws IOException {
    HttpURLConnection connection = open(pathAndQuery);
    long deadline = System.nanoTime() + answerTimeoutMillis * 1_000_000L;
    try {
      int status = connection.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) {
        throw new Answered(status, pathAndQuery, connection.getHeaderField("Retry-After"));
      }
      long length = connection.getContentLengthLong();
      if (length > maxBytes) {
        throw new IOException("answered more than " + maxBytes + " bytes");
      }
      return new Streamed(length, new Bounded(connection, deadline));
    } catch (IOException | RuntimeException e) {
      connection.disconnect();
      throw e;
    }
  }

  /** Something answered, but not 200: the agent refused, is busy (503), or it isn't this agent. */
  public static final class Answered extends IOException {
    private static final long serialVersionUID = 1;

    /** The answer's status. */
    public final int status;

    /** When it said to ask again, in seconds (its {@code Retry-After}): 1 when it didn't say. */
    public final int retryAfterSeconds;

    Answered(int status, String pathAndQuery, @Nullable String retryAfter) {
      super("answered " + status + " to " + pathAndQuery);
      this.status = status;
      int seconds = 1;
      if (retryAfter != null && retryAfter.strip().matches("\\d{1,3}")) {
        seconds = Integer.parseInt(retryAfter.strip());
      }
      retryAfterSeconds = seconds;
    }

    /** Whether the agent was busy with another such request, and would take this one shortly. */
    public boolean busy() {
      return status == 503;
    }
  }

  /** A call to the agent. */
  @FunctionalInterface
  public interface Call<T> {
    T call() throws IOException;
  }

  /**
   * Makes a call, and when the agent answers it's busy (503), once more after it said to wait (at
   * most two seconds): a heavy request (the journal, the settings) finds another under way.
   */
  public static <T> T onceMoreIfBusy(Call<T> call) throws IOException {
    try {
      return call.call();
    } catch (Answered answered) {
      if (!answered.busy()) {
        throw answered;
      }
      try {
        Thread.sleep(Math.min(2, Math.max(0, answered.retryAfterSeconds)) * 1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw answered;
      }
      return call.call();
    }
  }

  /**
   * An answer's body as it arrives.
   *
   * @param length its length, or -1 when the agent didn't give it
   * @param body what it holds
   */
  public record Streamed(long length, InputStream body) {}

  /** An answer's body that holds to the size limit and the deadline, and disconnects on close. */
  private final class Bounded extends InputStream {
    private final HttpURLConnection connection;
    private final InputStream in;
    private final long deadline;
    private long read;

    Bounded(HttpURLConnection connection, long deadline) throws IOException {
      this.connection = connection;
      in = connection.getInputStream();
      this.deadline = deadline;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] into, int offset, int length) throws IOException {
      int got = in.read(into, offset, length);
      if (got > 0) {
        read += got;
        if (read > maxBytes) {
          throw new IOException("answered more than " + maxBytes + " bytes");
        }
      }
      if (System.nanoTime() > deadline) {
        throw new SocketTimeoutException("no whole answer within " + answerTimeoutMillis + " ms");
      }
      return got;
    }

    @Override
    public void close() throws IOException {
      try {
        in.close();
      } finally {
        connection.disconnect();
      }
    }
  }

  /**
   * POSTs to the agent, with no body, and says how it answered.
   *
   * @return the answer's status, such as 202
   * @throws IOException saying why there's no answer: no connection, or none in time
   */
  public int post(String path) throws IOException {
    HttpURLConnection connection = open(path);
    try {
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(0);
      connection.getOutputStream().close();
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  /**
   * Whether anything accepts a connection on a port of the agent's computer, within the connect
   * timeout: a computer that's gone refuses, or doesn't answer at all.
   */
  public boolean accepts(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** The agent's port. */
  public int port() {
    return port;
  }

  private HttpURLConnection open(String pathAndQuery) throws IOException {
    if (!pathAndQuery.startsWith("/")) {
      throw new IllegalArgumentException("a path on the agent starts with /: " + pathAndQuery);
    }
    HttpURLConnection connection;
    try {
      connection =
          (HttpURLConnection)
              new URI("http://" + authority() + pathAndQuery)
                  .toURL()
                  .openConnection(Proxy.NO_PROXY);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("not a path on the agent: " + pathAndQuery, e);
    }
    connection.setConnectTimeout(connectTimeoutMillis);
    connection.setReadTimeout(answerTimeoutMillis);
    connection.setUseCaches(false);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Accept", "*/*");
    return connection;
  }

  private byte[] readBounded(InputStream in, long deadline) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] chunk = new byte[CHUNK];
    while (true) {
      int read = in.read(chunk);
      if (read < 0) {
        return body.toByteArray();
      }
      if (body.size() + read > maxBytes) {
        throw new IOException("answered more than " + maxBytes + " bytes");
      }
      body.write(chunk, 0, read);
      if (System.nanoTime() > deadline) {
        throw new SocketTimeoutException("no whole answer within " + answerTimeoutMillis + " ms");
      }
    }
  }

  private static int millis(double seconds) {
    if (!(seconds > 0) || seconds > 600) {
      throw new IllegalArgumentException("a timeout, in seconds: " + seconds);
    }
    return (int) Math.max(1, Math.round(seconds * 1000));
  }
}
