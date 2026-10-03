package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;
import us.hebi.quickbuf.RepeatedMessage;
import us.hebi.quickbuf.Utf8String;

/**
 * One board's connection, on a thread of its own: it keeps the agent's stream open, decodes each
 * event into the board's table, and publishes the table for the loop to take, so the loop never
 * waits on the network. It checks the protocol's version before it reads a byte of the stream. When
 * the stream can't be opened, or ends, or is silent for the missing threshold (its socket's own
 * timeout), it connects again, backing off.
 *
 * <p>In steady state, reading the stream allocates nothing: it's asked for with HTTP/1.0, so its
 * body is the events themselves, read from the socket through one buffer ({@link HttpHead}); one
 * event, one source and one table are reused; and text is decoded only when its bytes differ from
 * what the value holds. A description, a change of level and a text that changed allocate.
 */
final class Link implements Runnable {
  /** The first wait after a failure. */
  static final long FIRST_BACKOFF_MILLIS = 250;

  /** The most one event may be, in bytes: a description is the largest, at a few KiB. */
  static final int MAX_EVENT = 16 << 20;

  /** The most of a refusal's body read, in bytes. */
  private static final int MAX_PROBLEM = 64 << 10;

  private final Board board;
  private final Robot robot;
  private final Settings settings;
  private final Recorder recorder;
  private final Address address;
  private final byte[] request;
  private final Exchange exchange;
  private final Table current = new Table();
  private final Spotter.Event event = Spotter.Event.newInstance();
  private final ProtoSource source = ProtoSource.newStreamSource();
  private final ClockMap clock = new ClockMap();
  private Utf8String[] raws = new Utf8String[0];
  private volatile boolean closed;
  private volatile @Nullable Socket socket;
  private volatile @Nullable Thread thread;

  Link(Board board, Robot robot, Settings settings, Recorder recorder) {
    this.board = board;
    this.robot = robot;
    this.settings = settings;
    this.recorder = recorder;
    this.exchange = board.exchange();
    this.address = address(board.address());
    this.request =
        HttpHead.request(
            Protocol.STREAM + "?heartbeat=" + settings.heartbeat().toMillis() + "ms",
            address.authority(),
            Protocol.PROTOBUF);
  }

  /**
   * Where an agent is.
   *
   * @param host its name or IP address, as a socket takes it (an IPv6 address without brackets)
   * @param port its port
   * @param authority its host and port, as a request's {@code Host} header has them
   */
  record Address(String host, int port, String authority) {}

  /**
   * Where an agent is, from its address as robot code gives it ({@code 10.12.34.11}, {@code
   * vision-front:5808}, or an IPv6 address, in brackets with a port): on {@link Protocol#PORT}
   * unless it names one.
   */
  static Address address(String given) {
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

  /** Starts its thread. */
  void start() {
    Thread started = new Thread(this, "Spotter " + board.address());
    started.setDaemon(true);
    thread = started;
    started.start();
  }

  /** Stops its thread, dropping its connection. */
  void close() {
    closed = true;
    Thread running = thread;
    if (running != null) {
      running.interrupt();
    }
    Socket open = socket;
    if (open != null) {
      try {
        open.close();
      } catch (IOException e) {
        // Closed as far as it can be.
      }
    }
  }

  /** Its thread, once started: for tests, to measure what it allocates. */
  @Nullable Thread thread() {
    return thread;
  }

  /** Waits for its thread to end, after {@link #close}. */
  void join(long millis) throws InterruptedException {
    Thread running = thread;
    if (running != null) {
      running.join(millis);
    }
  }

  @Override
  public void run() {
    long wait = FIRST_BACKOFF_MILLIS;
    long most = Math.max(FIRST_BACKOFF_MILLIS, settings.backoff().toMillis());
    while (!closed) {
      boolean opened = false;
      try {
        opened = attempt();
      } catch (IOException | RuntimeException e) {
        if (closed) {
          return;
        }
        failed(why(e));
      }
      if (opened) {
        wait = FIRST_BACKOFF_MILLIS;
      }
      try {
        Thread.sleep(wait);
      } catch (InterruptedException e) {
        return;
      }
      wait = Math.min(most, wait * 2);
    }
  }

  /**
   * Connects, asks for the stream, checks the version, and reads the stream until it fails: whether
   * it opened. The socket's timeouts, connecting and reading, are the missing threshold: a stream
   * silent that long is dropped.
   */
  private boolean attempt() throws IOException {
    Socket s = new Socket(Proxy.NO_PROXY);
    socket = s;
    boolean opened = false;
    try (s) {
      if (closed) {
        return false;
      }
      int timeout = (int) Math.min(Integer.MAX_VALUE, settings.missing().toMillis());
      s.connect(new InetSocketAddress(address.host(), address.port()), timeout);
      s.setSoTimeout(timeout);
      s.setTcpNoDelay(true);
      OutputStream out = s.getOutputStream();
      out.write(request);
      out.flush();
      InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
      HttpHead head = HttpHead.read(in);
      long now = robot.nanos().getAsLong();
      String version = head.header(Protocol.HEADER);
      if (version == null || !sameMajor(version)) {
        otherProtocol(version == null ? "" : version, now);
        return false;
      }
      if (head.status() != 200) {
        throw new IOException(refusal(head, in));
      }
      opened(version, now);
      opened = true;
      read(in);
      return true;
    } catch (IOException | RuntimeException e) {
      if (opened && !closed) {
        failed(why(e));
        return true;
      }
      throw e;
    } finally {
      socket = null;
    }
  }

  /** Whether a version ({@code major.minor}) is this protocol's major version. */
  static boolean sameMajor(@Nullable String version) {
    return version != null && major(version).equals(major(Protocol.VERSION));
  }

  private static String major(String version) {
    int dot = version.indexOf('.');
    return (dot < 0 ? version : version.substring(0, dot)).strip();
  }

  /** What a refusal says: its status, and its {@code Problem}'s message. */
  private static String refusal(HttpHead head, InputStream body) {
    String message = "";
    try {
      String length = head.header("Content-Length");
      int most = MAX_PROBLEM;
      if (length != null) {
        most = (int) Math.min(MAX_PROBLEM, Long.parseLong(length.strip()));
      }
      message =
          ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), body.readNBytes(most)).getMessage();
    } catch (IOException | NumberFormatException e) {
      // No reason given, or none that could be read: the status says enough.
    }
    return "it answered " + head.status() + (message.isEmpty() ? "" : ": " + message);
  }

  /** Why something failed, in a few words: its message, or its kind without one. */
  static String why(Exception e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.strip();
  }

  /**
   * Reads events until the stream ends (which throws) or the link is closed: each decoded into the
   * one reused event, applied to the table, and published.
   */
  void read(InputStream in) throws IOException {
    source.setInput(in);
    source.setSizeLimit(MAX_EVENT);
    while (!closed) {
      source.resetSizeCounter();
      if (source.isAtEnd()) {
        throw new EOFException("the stream ended");
      }
      event.clearQuick();
      source.readMessage(event);
      received(event);
    }
  }

  /** Applies an event, and publishes the table. */
  void received(Spotter.Event received) {
    long now = robot.nanos().getAsLong();
    current.heard = true;
    current.heardNanos = now;
    if (received.hasDescribed()) {
      described(received.getDescribed());
    } else if (received.hasValues()) {
      values(received.getValues(), now);
    } else if (received.hasHeartbeat()) {
      clock.heard(received.getHeartbeat().getTimeNanos(), now);
    }
    publish();
    if (received.hasDescribed()) {
      try {
        recorder.described(board, received.getDescribed());
      } catch (RuntimeException e) {
        // A logging fault never takes a board away.
      }
    } else if (received.hasValues()) {
      try {
        recorder.values(board, received.getValues());
      } catch (RuntimeException e) {
        // As above.
      }
    }
  }

  private void described(Spotter.Description described) {
    if (described.equals(current.description)) {
      // A reconnect: the same description, so nothing changed.
      return;
    }
    Spotter.Description copy = described.clone();
    Field[] fields = Field.of(copy, settings.limits());
    current.describe(copy, fields);
    raws = new Utf8String[fields.length];
    for (int i = 0; i < raws.length; i++) {
      raws[i] = Utf8String.newEmptyInstance();
    }
    current.changes++;
    current.alerts = List.of();
    String hostname = copy.getIdentity().getHostname();
    board.named(hostname.isEmpty() ? board.address() : hostname);
  }

  private void values(Spotter.Values values, long now) {
    Value[] table = current.values;
    if (current.description == Table.NONE
        || values.getRevision() != current.description.getRevision()) {
      // Indexes into another description: the next one is on its way.
      return;
    }
    clock.heard(values.getTimeNanos(), now);
    long at = clock.robot(values.getTimeNanos());
    boolean changed = false;
    boolean judged = false;
    RepeatedMessage<Spotter.FieldValue> sent = values.getValues();
    for (int i = 0; i < sent.length(); i++) {
      Spotter.FieldValue each = sent.get(i);
      int index = each.getIndex();
      if (index < 0 || index >= table.length) {
        continue;
      }
      Value value = table[index];
      value.nanos = at;
      if (take(value, raws[index], each)) {
        changed = true;
        value.changed = current.changes + 1;
        Level was = value.level;
        String why = value.reason;
        Judge.judge(value);
        judged |= value.level != was || !value.reason.equals(why);
      }
    }
    if (changed) {
      current.changes++;
    }
    if (judged) {
      current.alerts = alerts();
    }
  }

  /** Takes a sent value: whether it changed. */
  private static boolean take(Value value, Utf8String raw, Spotter.FieldValue sent) {
    if (sent.hasNumber()) {
      double number = sent.getNumber();
      if (value.kind == Value.Kind.NUMBER && Double.compare(number, value.number) == 0) {
        return false;
      }
      value.kind = Value.Kind.NUMBER;
      value.number = number;
      return true;
    }
    if (sent.hasFlag()) {
      boolean flag = sent.getFlag();
      if (value.kind == Value.Kind.FLAG && flag == value.flag) {
        return false;
      }
      value.kind = Value.Kind.FLAG;
      value.flag = flag;
      return true;
    }
    if (sent.hasText()) {
      return text(value, raw, Value.Kind.TEXT, sent.getTextBytes(), Level.UNAVAILABLE);
    }
    if (sent.hasStatus()) {
      Spotter.Status status = sent.getStatus();
      return text(
          value, raw, Value.Kind.STATUS, status.getMessageBytes(), level(status.getLevelValue()));
    }
    if (sent.hasUnavailable()) {
      return text(
          value, raw, Value.Kind.UNAVAILABLE, sent.getUnavailableBytes(), Level.UNAVAILABLE);
    }
    if (sent.hasJson()) {
      return text(value, raw, Value.Kind.TEXT, sent.getJsonBytes(), Level.UNAVAILABLE);
    }
    if (sent.hasFileUrl()) {
      return text(value, raw, Value.Kind.TEXT, sent.getFileUrlBytes(), Level.UNAVAILABLE);
    }
    if (value.kind == Value.Kind.UNAVAILABLE && value.text.equals(NO_VALUE)) {
      return false;
    }
    raw.clear();
    value.kind = Value.Kind.UNAVAILABLE;
    value.text = NO_VALUE;
    return true;
  }

  /** Why a value sent with none of its kinds set has no value. */
  static final String NO_VALUE = "sent without a value";

  /**
   * Takes text (or a status's message, or why there's none): decoded only when its bytes differ.
   */
  private static boolean text(
      Value value, Utf8String raw, Value.Kind kind, Utf8String bytes, Level status) {
    if (value.kind == kind && value.status == status && raw.equals(bytes)) {
      return false;
    }
    raw.copyFrom(bytes);
    value.kind = kind;
    value.status = status;
    value.text = bytes.getString();
    return true;
  }

  /** A status's level, from {@code spotter.proto}'s; a status with none is unavailable. */
  private static Level level(int sent) {
    switch (sent) {
      case Spotter.Level.LEVEL_OK_VALUE:
        return Level.OK;
      case Spotter.Level.LEVEL_WARNING_VALUE:
        return Level.WARNING;
      case Spotter.Level.LEVEL_FAILING_VALUE:
        return Level.FAILING;
      default:
        return Level.UNAVAILABLE;
    }
  }

  /** The board's value alerts: one per value at warning or failing, in its pack's words. */
  private List<Alert> alerts() {
    String name = board.name();
    List<Alert> alerts = new ArrayList<>();
    for (Value value : current.values) {
      if (value.level == Level.WARNING || value.level == Level.FAILING) {
        alerts.add(new Alert(value.level, name, text(name, value)));
      }
    }
    return List.copyOf(alerts);
  }

  /** What an alert about a value says: {@code "vision-front: CPU temperature above 80 °C"}. */
  static String text(String board, Value value) {
    String label = board + ": " + value.label();
    if (value.kind == Value.Kind.STATUS && value.status != Level.UNAVAILABLE) {
      return label + ": " + value.reason;
    }
    if (!value.available() || value.kind == Value.Kind.STATUS) {
      return label + " unavailable: " + value.reason;
    }
    return label + " " + value.reason;
  }

  private void opened(String version, long now) {
    current.otherProtocol = false;
    current.protocol = version;
    current.why = "";
    current.heard = true;
    current.heardNanos = now;
    clock.reset();
    publish();
  }

  private void otherProtocol(String version, long now) {
    current.otherProtocol = true;
    current.protocol = version;
    current.why =
        version.isEmpty()
            ? "it answers without Spotter's protocol (the robot speaks " + Protocol.VERSION + ")"
            : "it speaks Spotter protocol " + version + ", the robot " + Protocol.VERSION;
    current.heard = true;
    current.heardNanos = now;
    if (current.description != Table.NONE) {
      // Its values and actions aren't used.
      current.describe(Table.NONE, new Field[0]);
      raws = new Utf8String[0];
      current.changes++;
      current.alerts = List.of();
    }
    publish();
  }

  private void failed(String why) {
    current.why = why;
    publish();
  }

  private void publish() {
    exchange.back().copyFrom(current);
    exchange.publish();
  }
}
