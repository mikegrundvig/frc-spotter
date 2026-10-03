package com.michaelgrundvig.frc.spotter.manager;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;
import us.hebi.quickbuf.RepeatedMessage;
import us.hebi.quickbuf.Utf8String;

/**
 * One board's connection, on a thread of its own: it keeps the agent's stream open, decodes each
 * event into the board's table, and publishes the table for the loop to take, so the loop never
 * waits on the network. It checks the protocol's version before it reads a byte of the stream. When
 * the stream can't be opened, or ends, or is silent for the missing threshold (its connection's own
 * timeout), it connects again, backing off.
 *
 * <p>In steady state, decoding the stream into the table and publishing it allocate nothing: one
 * event, one source and one table are reused, and text is decoded only when its bytes differ from
 * what the value holds. A description, a change of level and a text that changed allocate. The
 * JDK's HTTP client, which reads the stream's chunks, allocates a little of its own for each.
 *
 * <p>It also follows the board's runs (each run event goes to its {@link Run}), sends the board's
 * requests on request threads of its own ({@link AgentClient}): at most {@link #REQUEST_THREADS}
 * at once and {@link #REQUESTS_WAITING} waiting, any more refused at once, so robot code asking
 * every loop can't pile threads up on the robot. And it keeps the board's packs the
 * robot's: it connects with their hash, and when the board's differ, pushes them while the robot is
 * disabled and off the field, once for each set of packs the board has, so a push that fails or
 * doesn't take is never repeated in a loop.
 */
final class Link implements Runnable, AgentClient.Challenges {
  /** The first wait after a failure. */
  static final long FIRST_BACKOFF_MILLIS = 250;

  /** The most one event may be, in bytes: a description is the largest, at a few KiB. */
  static final int MAX_EVENT = 16 << 20;

  /** The most runs followed at once that robot code didn't start, or that are done. */
  static final int MAX_RUNS = 64;

  /** The most of a refusal's body read, in bytes. */
  private static final int MAX_PROBLEM = 64 << 10;

  /** How many of a board's requests run at once. */
  static final int REQUEST_THREADS = 3;

  /** How many of a board's requests may wait for a thread; one more is refused at once. */
  static final int REQUESTS_WAITING = 8;

  /** How long a request thread waits for another request before it ends. */
  private static final long IDLE_SECONDS = 30;

  /** Where the board's packs are, against the robot's. */
  enum Packs {
    /** The same, or the robot has none. */
    SAME,
    /** They differ, and the board refuses pushes. */
    REFUSES,
    /** A push is on its way, or was taken and the agent is restarting. */
    PUSHING,
    /** They differ, and pushing them failed. */
    FAILED,
    /** They were pushed, and still differ. */
    STILL_DIFFER,
    /** They differ, and automatic pushes are off. */
    AUTOMATIC_OFF,
    /** They differ, and the robot is enabled or on the field. */
    WAITING
  }

  private final Board board;
  private final Robot robot;
  private final Settings settings;
  private final Recorder recorder;
  private final @Nullable TeamPacks packs;
  private final Executor requests;
  private final @Nullable ExecutorService pool;
  private final Address address;
  private final URL url;
  private final URL urlWithPacks;
  private final AgentClient client;
  private final Exchange exchange;
  private final Table current = new Table();
  private final Spotter.Event event = Spotter.Event.newInstance();
  private final ProtoSource source = ProtoSource.newStreamSource();
  private final ClockMap clock = new ClockMap();
  private final LinkedHashMap<String, Run> runs = new LinkedHashMap<>();
  private final AtomicLong counter = new AtomicLong();
  private final AtomicLong pushes = new AtomicLong();
  private Utf8String[] raws = new Utf8String[0];
  private Packs packsState = Packs.SAME;
  private @Nullable String packsFailure;
  private volatile String challenge = "";
  private volatile String theirs = "";
  private volatile boolean askWithPacks = true;
  private volatile boolean pushing;
  private volatile boolean restarting;
  private volatile @Nullable String triedFrom;
  private volatile @Nullable String pushFailure;
  private volatile boolean closed;
  private volatile @Nullable HttpURLConnection connection;
  private volatile long events;
  private volatile @Nullable Thread thread;

  /**
   * A link without packs, a key, deadlines, or threads for requests (they run on the caller's): for
   * tests of the stream alone.
   */
  Link(Board board, Robot robot, Settings settings, Recorder recorder) {
    this(board, robot, settings, recorder, null, null, null, Runnable::run, null);
  }

  /**
   * A board's link, as the manager makes it: its requests on threads of its own.
   *
   * @param packs the team's packs, to push to this board; null for none
   * @param signer the key that signs writes; null for none
   * @param deadlines where each request's overall deadline is kept: the manager's
   */
  Link(
      Board board,
      Robot robot,
      Settings settings,
      Recorder recorder,
      @Nullable TeamPacks packs,
      @Nullable Signer signer,
      ScheduledExecutorService deadlines) {
    this(board, robot, settings, recorder, packs, signer, deadlines, pool(board.address()));
  }

  private Link(
      Board board,
      Robot robot,
      Settings settings,
      Recorder recorder,
      @Nullable TeamPacks packs,
      @Nullable Signer signer,
      ScheduledExecutorService deadlines,
      ThreadPoolExecutor pool) {
    this(board, robot, settings, recorder, packs, signer, deadlines, pool, pool);
  }

  private Link(
      Board board,
      Robot robot,
      Settings settings,
      Recorder recorder,
      @Nullable TeamPacks packs,
      @Nullable Signer signer,
      @Nullable ScheduledExecutorService deadlines,
      Executor requests,
      @Nullable ExecutorService pool) {
    this.board = board;
    this.robot = robot;
    this.settings = settings;
    this.recorder = recorder;
    this.packs = packs;
    this.requests = requests;
    this.pool = pool;
    this.exchange = board.exchange();
    this.address = address(board.address());
    String stream = Protocol.STREAM + "?heartbeat=" + settings.heartbeat().toMillis() + "ms";
    this.url = url(address, stream);
    this.urlWithPacks = packs == null ? url : url(address, stream + "&packs=" + packs.hash());
    int connect = (int) Math.min(Integer.MAX_VALUE, settings.missing().toMillis());
    this.client = new AgentClient(address, connect, signer, this, deadlines);
    board.link(this);
  }

  /**
   * A board's request threads: {@link #REQUEST_THREADS} at most, made as they're needed and ended
   * when idle; {@link #REQUESTS_WAITING} requests may wait, and one more is refused at once.
   */
  private static ThreadPoolExecutor pool(String address) {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            REQUEST_THREADS,
            REQUEST_THREADS,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(REQUESTS_WAITING),
            work -> {
              Thread thread = new Thread(work, "Spotter requests " + address);
              thread.setDaemon(true);
              return thread;
            });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  /**
   * Where an agent is.
   *
   * @param host its name or IP address (an IPv6 address without brackets)
   * @param port its port
   * @param authority its host and port, as a URL has them
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

  /** A URL on an agent: its path, and maybe a query. */
  static URL url(Address address, String path) {
    try {
      return URI.create("http://" + address.authority() + path).toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("not a URL on " + address.authority() + ": " + path, e);
    }
  }

  /** Starts its thread. */
  void start() {
    Thread started = new Thread(this, "Spotter " + board.address());
    started.setDaemon(true);
    thread = started;
    started.start();
  }

  /** Stops its thread, dropping its connection, and its request threads. */
  void close() {
    closed = true;
    Thread running = thread;
    if (running != null) {
      running.interrupt();
    }
    HttpURLConnection open = connection;
    if (open != null) {
      open.disconnect();
    }
    if (pool != null) {
      for (Runnable waiting : pool.shutdownNow()) {
        if (waiting instanceof Request) {
          ((Request) waiting).refuse("the manager is closed");
        }
      }
    }
    client.close();
  }

  /** How many events it's received: for tests, to measure what it allocates per event. */
  long events() {
    return events;
  }

  /** How many pushes it's sent: for tests, to see that a failed one isn't sent again in a loop. */
  long pushes() {
    return pushes.get();
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

  /** What it needs of the robot. */
  Robot robot() {
    return robot;
  }

  /** The manager's settings. */
  Settings settings() {
    return settings;
  }

  @Override
  public String challenge() {
    return challenge;
  }

  @Override
  public long next() {
    return counter.incrementAndGet();
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
   * Connects, checks the version before reading a byte of the stream, and reads the stream until it
   * fails: whether it opened (or was refused for its packs, which is as good). The connection's
   * timeouts, connecting and reading, are the missing threshold: a stream silent that long is
   * dropped.
   */
  private boolean attempt() throws IOException {
    boolean withPacks = packs != null && settings.pushAutomatically() && askWithPacks;
    HttpURLConnection c =
        (HttpURLConnection) (withPacks ? urlWithPacks : url).openConnection(Proxy.NO_PROXY);
    connection = c;
    boolean opened = false;
    try {
      if (closed) {
        return false;
      }
      int timeout = (int) Math.min(Integer.MAX_VALUE, settings.missing().toMillis());
      c.setConnectTimeout(timeout);
      c.setReadTimeout(timeout);
      c.setUseCaches(false);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("Accept", Protocol.PROTOBUF);
      int code = c.getResponseCode();
      long now = robot.nanos().getAsLong();
      String version = c.getHeaderField(Protocol.HEADER);
      if (version == null || !sameMajor(version)) {
        otherProtocol(version == null ? "" : version, now);
        return false;
      }
      if (code == HttpURLConnection.HTTP_CONFLICT && withPacks) {
        // Its packs differ from the robot's. A push is signed over a stream's challenge, so the
        // stream is opened without the hash, and the push goes over it.
        askWithPacks = false;
        failed("its packs differ from the robot's");
        return true;
      }
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException(refusal(c, code));
      }
      String given = c.getHeaderField(Protocol.CHALLENGE);
      counter.set(0);
      challenge = given == null ? "" : given;
      restarting = false;
      opened(version, now);
      opened = true;
      read(new BufferedInputStream(c.getInputStream(), 8192));
      return true;
    } catch (IOException | RuntimeException e) {
      if (opened && !closed) {
        failed(why(e));
        return true;
      }
      throw e;
    } finally {
      challenge = "";
      connection = null;
      c.disconnect();
    }
  }

  /** Whether a version ({@code major.minor}) is this protocol's major version. */
  static boolean sameMajor(String version) {
    return major(version).equals(major(Protocol.VERSION));
  }

  private static String major(String version) {
    int dot = version.indexOf('.');
    return (dot < 0 ? version : version.substring(0, dot)).strip();
  }

  /** What a refusal says: its status, and its {@code Problem}'s message. */
  static String refusal(HttpURLConnection c, int code) {
    String message = "";
    try (InputStream body = c.getErrorStream()) {
      if (body != null) {
        message =
            ProtoMessage.mergeFrom(Spotter.Problem.newInstance(), body.readNBytes(MAX_PROBLEM))
                .getMessage();
      }
    } catch (IOException e) {
      // No reason given, or none that could be read: the status says enough.
    }
    return "it answered " + code + (message.isEmpty() ? "" : ": " + message);
  }

  /** Why something failed, in a few words: its message, or its kind without one. */
  static String why(Throwable e) {
    Throwable cause = e.getCause();
    if (!(e instanceof CompletionException) || cause == null) {
      cause = e;
    }
    String message = cause.getMessage();
    return message == null || message.isBlank()
        ? cause.getClass().getSimpleName()
        : message.strip();
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
    events++;
    long now = robot.nanos().getAsLong();
    current.heard = true;
    current.heardNanos = now;
    boolean described = false;
    if (received.hasDescribed()) {
      described = described(received.getDescribed());
    } else if (received.hasValues()) {
      values(received.getValues(), now);
    } else if (received.hasHeartbeat()) {
      clock.heard(received.getHeartbeat().getTimeNanos(), now);
    } else if (received.hasRun()) {
      run(received.getRun());
    } else if (received.hasRuns()) {
      listed(received.getRuns());
    }
    if (packs != null && (described || packsState != Packs.SAME)) {
      packs();
    }
    publish();
    try {
      if (received.hasDescribed()) {
        recorder.described(board, received.getDescribed());
      } else if (received.hasValues()) {
        recorder.values(board, received.getValues());
      } else if (received.hasRun()) {
        recorder.run(board, received.getRun());
      }
    } catch (RuntimeException e) {
      // A logging fault never takes a board away.
    }
  }

  /** Takes a description: whether it's new. */
  private boolean described(Spotter.Description described) {
    theirs = described.getPushedPacks();
    if (described.equals(current.description)) {
      // A reconnect: the same description, so nothing changed.
      return false;
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
    problems(copy);
    return true;
  }

  /** The longest a problem is quoted in its alert; the board's problems have it whole. */
  static final int QUOTED_PROBLEM = 200;

  /**
   * Takes a description's problems, and its alert: the count and the first, so a pack the board
   * ignored (a typo, a key a newer agent knows) is never silent on the robot.
   */
  private void problems(Spotter.Description described) {
    List<String> problems = new ArrayList<>();
    for (int i = 0; i < described.getProblems().length(); i++) {
      problems.add(described.getProblems().get(i));
    }
    current.problems = List.copyOf(problems);
    current.problemsAlert = problemsAlert(board.name(), current.problems);
  }

  /**
   * What a board's problems' alert says: {@code "vision-front reports 2 problems, the first: ..."};
   * empty when it has none.
   */
  static String problemsAlert(String board, List<String> problems) {
    if (problems.isEmpty()) {
      return "";
    }
    String first = problems.get(0);
    if (first.length() > QUOTED_PROBLEM) {
      first = first.substring(0, QUOTED_PROBLEM) + "...";
    }
    return board
        + (problems.size() == 1
            ? " reports a problem: "
            : " reports " + problems.size() + " problems, the first: ")
        + first;
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
  static boolean take(Value value, Utf8String raw, Spotter.FieldValue sent) {
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
    return board + ": " + text(value);
  }

  /** Why a value is at its level, its label first: {@code "CPU temperature above 80 °C"}. */
  static String text(Value value) {
    String label = value.label();
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
      current.problems = List.of();
      current.problemsAlert = "";
    }
    current.packs = "";
    packsState = Packs.SAME;
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

  // ---- packs ----

  /**
   * Where the board's packs are against the robot's, and what's to be done: a push, when they
   * differ, the board accepts pushes, automatic pushes are on, the robot is disabled and off the
   * field, and no push was tried over the packs the board has now. Builds its alert's text only
   * when that changes.
   */
  private void packs() {
    TeamPacks team = packs;
    Spotter.Description description = current.description;
    if (team == null || description == Table.NONE) {
      return;
    }
    String has = description.getPushedPacks();
    Packs now;
    if (has.equals(team.hash())) {
      now = Packs.SAME;
    } else if (description.getRefusesPushes()) {
      now = Packs.REFUSES;
    } else if (pushing || restarting) {
      now = Packs.PUSHING;
    } else if (has.equals(triedFrom)) {
      now = pushFailure != null ? Packs.FAILED : Packs.STILL_DIFFER;
    } else if (!settings.pushAutomatically()) {
      now = Packs.AUTOMATIC_OFF;
    } else if (robot.enabled().getAsBoolean() || robot.fieldAttached().getAsBoolean()) {
      now = Packs.WAITING;
    } else {
      push(false);
      now = Packs.PUSHING;
    }
    String failure = pushFailure;
    if (now == packsState && (now != Packs.FAILED || failure == packsFailure)) {
      return;
    }
    packsState = now;
    packsFailure = failure;
    String name = board.name() + "'s packs ";
    switch (now) {
      case REFUSES:
        current.packs = name + "differ from the robot's, and it refuses pushes";
        break;
      case FAILED:
        current.packs = name + "differ from the robot's, and pushing them failed: " + failure;
        break;
      case STILL_DIFFER:
        current.packs = name + "still differ from the robot's after a push";
        break;
      case AUTOMATIC_OFF:
        current.packs = name + "differ from the robot's, and automatic pushes are off";
        break;
      case WAITING:
        current.packs = name + "differ from the robot's: they'll be pushed off the field";
        break;
      default:
        current.packs = "";
    }
  }

  /**
   * Pushes the team's packs to the board, signed over its stream's challenge: automatically, or
   * forced by robot code. Completes once the board has taken them (it then restarts, and the stream
   * reconnects with the packs' hash), or exceptionally, saying why not.
   */
  CompletableFuture<Void> push(boolean forced) {
    TeamPacks team = packs;
    if (team == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("the manager was given no packs to push"));
    }
    synchronized (this) {
      if (pushing) {
        return CompletableFuture.failedFuture(
            new IllegalStateException("a push to " + board.name() + " is under way"));
      }
      pushing = true;
    }
    pushes.incrementAndGet();
    triedFrom = theirs;
    CompletableFuture<Void> pushed = new CompletableFuture<>();
    execute(
        why -> {
          pushFailure = why;
          pushing = false;
          pushed.completeExceptionally(new IOException(why));
        },
        () -> {
          client.write("POST", Protocol.PACKS, team.bundle(), Protocol.PROTOBUF);
          pushFailure = null;
          restarting = true;
          askWithPacks = true;
          pushing = false;
          try {
            recorder.pushed(board, team.hash(), forced);
          } catch (RuntimeException e) {
            // A logging fault never fails a push.
          }
          pushed.complete(null);
        });
    return pushed;
  }

  // ---- requests ----

  /** Starts an action's run, on a request thread; the run follows from the stream. */
  void start(Run run, String action, byte[] input) {
    execute(
        run::refused,
        () -> {
          byte[] answer =
              client.write("POST", Protocol.ACTIONS + action, input, "application/octet-stream");
          String id = ProtoMessage.mergeFrom(Spotter.Started.newInstance(), answer).getRun();
          boolean cancel = run.started(id);
          attach(id, run);
          if (cancel) {
            CompletableFuture<Void> later = run.cancelling();
            cancel(id)
                .whenComplete(
                    (done, failure) -> {
                      if (later != null) {
                        if (failure != null) {
                          later.completeExceptionally(failure);
                        } else {
                          later.complete(null);
                        }
                      }
                    });
          }
        });
  }

  /** Cancels a run. */
  CompletableFuture<Void> cancel(String run) {
    CompletableFuture<Void> taken = new CompletableFuture<>();
    execute(
        why -> taken.completeExceptionally(new IOException(why)),
        () -> {
          client.write("DELETE", Protocol.RUNS + run, null, "");
          taken.complete(null);
        });
    return taken;
  }

  /** A run's file field, as its bytes. */
  CompletableFuture<byte[]> file(String run, String field) {
    return call(() -> client.get(Protocol.RUNS + run + "/files/" + field));
  }

  /** A page of one of the board's logs. */
  CompletableFuture<Spotter.LogPage> log(String id, LogQuery query) {
    return call(
        () ->
            ProtoMessage.mergeFrom(
                Spotter.LogPage.newInstance(), client.get(Protocol.LOGS + id + query.query())));
  }

  /** A request that answers something. */
  private interface Call<T> {
    T call() throws IOException;
  }

  /** A request that answers nothing. */
  private interface Task {
    void run() throws IOException;
  }

  private <T> CompletableFuture<T> call(Call<T> call) {
    CompletableFuture<T> answer = new CompletableFuture<>();
    execute(
        why -> answer.completeExceptionally(new IOException(why)),
        () -> answer.complete(call.call()));
    return answer;
  }

  /**
   * Runs a request on one of the board's request threads: what it throws goes to {@code failed},
   * as why; when they're all busy and as many wait as may, it's refused at once.
   */
  private void execute(java.util.function.Consumer<String> failed, Task task) {
    try {
      requests.execute(new Request(failed, task));
    } catch (RejectedExecutionException e) {
      failed.accept(busy(e));
    }
  }

  /** A request, waiting for a thread or under way: what it throws goes to its failure, as why. */
  private static final class Request implements Runnable {
    private final java.util.function.Consumer<String> failed;
    private final Task task;

    Request(java.util.function.Consumer<String> failed, Task task) {
      this.failed = failed;
      this.task = task;
    }

    @Override
    public void run() {
      try {
        task.run();
      } catch (IOException | RuntimeException e) {
        failed.accept(why(e));
      }
    }

    /** It never ran, and why. */
    void refuse(String why) {
      failed.accept(why);
    }
  }

  /** Why a request was refused before it was sent: the manager closed, or too many at once. */
  private String busy(RejectedExecutionException e) {
    return closed || (pool != null && pool.isShutdown())
        ? "the manager is closed"
        : "too many requests to "
            + board.name()
            + " at once: "
            + REQUEST_THREADS
            + " under way and "
            + REQUESTS_WAITING
            + " waiting";
  }

  // ---- runs ----

  /** Robot code's run, now that the board has given it an id: it takes what came before. */
  private void attach(String id, Run run) {
    synchronized (runs) {
      Run earlier = runs.put(id, run);
      if (earlier != null && earlier != run) {
        run.takeFrom(earlier);
      }
      trim();
    }
  }

  /** A run's event: to its run, or kept for robot code's that hasn't its id yet. */
  private void run(Spotter.RunEvent sent) {
    synchronized (runs) {
      Run run = runs.get(sent.getRun());
      if (run == null) {
        run = orphan(sent.getAction());
        runs.put(sent.getRun(), run);
        trim();
      }
      run.event(sent);
    }
  }

  /** A run robot code didn't start (or hasn't its id for yet). */
  private Run orphan(String action) {
    return new Run(
        action, Spotter.ActionDeclaration.newInstance().setId(action), Map.of(), this, false);
  }

  /** Drops the oldest runs nobody's following, past the most kept. */
  private void trim() {
    Iterator<Run> each = runs.values().iterator();
    while (runs.size() > MAX_RUNS && each.hasNext()) {
      Run run = each.next();
      if (!run.ours() || run.done()) {
        each.remove();
      }
    }
  }

  /**
   * The runs the agent keeps, as it lists them on every connect: a result that came while the board
   * was away goes to its run, and its log is fetched again; robot code's run that was going and
   * isn't kept any more is lost.
   */
  private void listed(Spotter.Runs listed) {
    List<Run> fetch = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    synchronized (runs) {
      Set<String> kept = new HashSet<>();
      for (Spotter.RunState state : listed.getRuns()) {
        kept.add(state.getRun());
        Run run = runs.get(state.getRun());
        if (run == null) {
          continue;
        }
        if (run.ours()) {
          fetch.add(run);
          ids.add(state.getRun());
        }
        if (!state.getRunning() && state.hasResult()) {
          run.finish(state.getResult());
        }
      }
      for (Map.Entry<String, Run> each : runs.entrySet()) {
        Run run = each.getValue();
        if (run.ours() && !run.done() && !kept.contains(each.getKey())) {
          run.lost("the board no longer keeps it: it was restarted, or rebooted, while it ran");
        }
      }
    }
    for (int i = 0; i < fetch.size(); i++) {
      Run run = fetch.get(i);
      String id = ids.get(i);
      execute(
          why -> {},
          () -> {
            Spotter.LogPage page =
                ProtoMessage.mergeFrom(
                    Spotter.LogPage.newInstance(),
                    client.get(Protocol.RUNS + id + "/log?limit=" + LogQuery.MAX_LIMIT));
            List<Spotter.LogEntry> entries = new ArrayList<>();
            for (Spotter.LogEntry entry : page.getEntries()) {
              entries.add(entry);
            }
            run.log(entries);
          });
    }
  }
}
