package com.michaelgrundvig.frc.spotter.manager.stream;

import com.michaelgrundvig.frc.spotter.manager.Alert;
import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.Level;
import com.michaelgrundvig.frc.spotter.manager.Recorder;
import com.michaelgrundvig.frc.spotter.manager.RobotSignals;
import com.michaelgrundvig.frc.spotter.manager.Settings;
import com.michaelgrundvig.frc.spotter.manager.actions.Runs;
import com.michaelgrundvig.frc.spotter.manager.limits.Field;
import com.michaelgrundvig.frc.spotter.manager.limits.Judge;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.manager.packs.PackSync;
import com.michaelgrundvig.frc.spotter.manager.packs.TeamPacks;
import com.michaelgrundvig.frc.spotter.manager.requests.AgentClient;
import com.michaelgrundvig.frc.spotter.manager.requests.Requests;
import com.michaelgrundvig.frc.spotter.manager.requests.Signer;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.michaelgrundvig.frc.spotter.protocol.WireCheck;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
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
 * <p>It reads each event whole before parsing it, at most {@link Protocol#MAX_EVENT} bytes, and
 * checks it ({@link WireCheck}) first, so whatever a board sends, parsing it allocates within its
 * size; an event past that, or malformed, drops the connection, which is made again. It takes a
 * description of at most {@link Protocol#MAX_VALUES} values, {@link Protocol#MAX_ACTIONS} actions
 * and {@link Protocol#MAX_PROBLEMS} problems, and raises at most {@link #MAX_ALERTS} alerts for its
 * values. Whatever its thread meets, an {@link Error} included, it connects again.
 *
 * <p>In steady state, decoding the stream into the table and publishing it allocate nothing: one
 * event, one buffer, two sources and one table are reused, and text is decoded only when its bytes
 * differ from what the value holds. A description, a change of level and a text that changed
 * allocate. The JDK's HTTP client, which reads the stream's chunks, allocates a little of its own
 * for each.
 *
 * <p>What the stream says of runs goes to the board's {@link Runs}, and what its description says
 * of its pushed packs to its {@link PackSync}; its requests go through its {@link Requests}.
 */
public final class Link implements Runnable, AgentClient.Challenges {
  /** The first wait after a failure. */
  static final long FIRST_BACKOFF_MILLIS = 250;

  /** The most one event may be, in bytes. */
  static final int MAX_EVENT = Protocol.MAX_EVENT;

  /** The size of the buffer each event is read into, reused; a larger event gets its own. */
  static final int FRAME = 64 << 10;

  /**
   * The most alerts a board's values raise: past them, the failing ones first, and one more says
   * how many others there are.
   */
  public static final int MAX_ALERTS = 32;

  private final Board board;
  private final RobotSignals robot;
  private final Settings settings;
  private final Recorder recorder;
  private final @Nullable PackSync packs;
  private final Requests requests;
  private final AgentClient.Address address;
  private final URL url;
  private final Exchange exchange;
  private final Table current = new Table();
  private final Spotter.Event event = Spotter.Event.newInstance();
  private final ProtoSource source = ProtoSource.newArraySource();
  private final ProtoSource checked = ProtoSource.newArraySource();
  private final byte[] frame = new byte[FRAME];
  private final ClockMap clock = new ClockMap();
  private final Runs runs;
  private final AtomicLong counter = new AtomicLong();
  private Utf8String[] raws = new Utf8String[0];
  private volatile String challenge = "";
  private volatile boolean closed;
  private volatile @Nullable HttpURLConnection connection;
  private volatile long events;
  private volatile @Nullable Thread thread;

  /**
   * A link without packs, a key, deadlines, or threads for requests (they run on the caller's): for
   * tests of the stream alone.
   */
  public Link(
      Board board, Exchange exchange, RobotSignals robot, Settings settings, Recorder recorder) {
    this(board, exchange, robot, settings, recorder, null, null, null, Runnable::run, null);
  }

  /**
   * A board's link, as the manager makes it: its requests on threads of its own.
   *
   * @param packs the team's packs, to push to this board; null for none
   * @param signer the key that signs writes; null for none
   * @param deadlines where each request's overall deadline is kept: the manager's
   */
  public Link(
      Board board,
      Exchange exchange,
      RobotSignals robot,
      Settings settings,
      Recorder recorder,
      @Nullable TeamPacks packs,
      @Nullable Signer signer,
      ScheduledExecutorService deadlines) {
    this(
        board,
        exchange,
        robot,
        settings,
        recorder,
        packs,
        signer,
        deadlines,
        Requests.pool(board.address()));
  }

  private Link(
      Board board,
      Exchange exchange,
      RobotSignals robot,
      Settings settings,
      Recorder recorder,
      @Nullable TeamPacks packs,
      @Nullable Signer signer,
      ScheduledExecutorService deadlines,
      ThreadPoolExecutor pool) {
    this(board, exchange, robot, settings, recorder, packs, signer, deadlines, pool, pool);
  }

  private Link(
      Board board,
      Exchange exchange,
      RobotSignals robot,
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
    this.exchange = exchange;
    this.address = AgentClient.address(board.address());
    String stream = Protocol.STREAM + "?heartbeat=" + settings.heartbeat().toMillis() + "ms";
    this.url = AgentClient.url(address, stream);
    int connect = (int) Math.min(Integer.MAX_VALUE, settings.missing().toMillis());
    AgentClient client = new AgentClient(address, connect, signer, this, deadlines);
    this.requests = new Requests(board, client, requests, pool);
    this.runs = new Runs(this.requests);
    this.packs =
        packs == null ? null : new PackSync(board, robot, settings, recorder, packs, this.requests);
  }

  /** Starts its thread. */
  public void start() {
    Thread started = new Thread(this, "Spotter " + board.address());
    started.setDaemon(true);
    thread = started;
    started.start();
  }

  /** Stops its thread, dropping its connection, and its request threads. */
  public void close() {
    closed = true;
    Thread running = thread;
    if (running != null) {
      running.interrupt();
    }
    HttpURLConnection open = connection;
    if (open != null) {
      open.disconnect();
    }
    requests.close();
  }

  /** How many events it's received: for tests, to measure what it allocates per event. */
  public long events() {
    return events;
  }

  /** How many pushes it's sent: for tests, to see that a failed one isn't sent again in a loop. */
  public long pushes() {
    return packs == null ? 0 : packs.pushes();
  }

  /** Its thread, once started: for tests, to measure what it allocates. */
  public @Nullable Thread thread() {
    return thread;
  }

  /** Waits for its thread to end, after {@link #close}. */
  public void join(long millis) throws InterruptedException {
    Thread running = thread;
    if (running != null) {
      running.join(millis);
    }
  }

  /** What it needs of the robot. */
  public RobotSignals robot() {
    return robot;
  }

  /** The manager's settings. */
  public Settings settings() {
    return settings;
  }

  /** Its board's requests: log pages, actions, cancels, files, pushes. */
  public Requests requests() {
    return requests;
  }

  /** Its board's runs, as it follows them from the stream. */
  public Runs runs() {
    return runs;
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
      } catch (Throwable e) {
        // Anything, an Error included: the board is reached again, never left for good.
        if (closed) {
          return;
        }
        failed(AgentClient.why(e));
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
   * fails: whether it opened. The connection's timeouts, connecting and reading, are the missing
   * threshold: a stream silent that long is dropped.
   */
  private boolean attempt() throws IOException {
    HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
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
      if (version == null || !AgentClient.sameMajor(version)) {
        otherProtocol(version == null ? "" : version, now);
        return false;
      }
      if (code != HttpURLConnection.HTTP_OK) {
        throw new IOException(AgentClient.refusal(c, code));
      }
      String given = c.getHeaderField(Protocol.CHALLENGE);
      counter.set(0);
      challenge = given == null ? "" : given;
      if (packs != null) {
        packs.opened();
      }
      opened(version, now);
      opened = true;
      read(new BufferedInputStream(c.getInputStream(), 8192));
      return true;
    } catch (IOException | RuntimeException e) {
      if (opened && !closed) {
        failed(AgentClient.why(e));
        return true;
      }
      throw e;
    } catch (Error e) {
      // Not a stream that opened well: the next attempt waits its backoff.
      if (!closed) {
        failed(AgentClient.why(e));
      }
      return false;
    } finally {
      challenge = "";
      connection = null;
      c.disconnect();
    }
  }

  /**
   * Reads events until the stream ends (which throws) or the link is closed: each, its length a
   * varint first, read whole, checked, decoded into the one reused event, applied to the table, and
   * published. An event past {@link #MAX_EVENT}, or malformed, throws: the connection is dropped.
   */
  public void read(InputStream in) throws IOException {
    while (!closed) {
      int first = in.read();
      if (first < 0) {
        throw new EOFException("the stream ended");
      }
      int length = ProtoSource.readRawVarint32(first, in);
      if (length < 0 || length > MAX_EVENT) {
        throw new IOException(
            "it sent an event of "
                + (length & 0xffffffffL)
                + " bytes, past the "
                + MAX_EVENT / 1024
                + " KiB the manager takes");
      }
      byte[] bytes = length <= frame.length ? frame : new byte[length];
      if (in.readNBytes(bytes, 0, length) < length) {
        throw new EOFException("the stream ended within an event");
      }
      try {
        WireCheck.check(WireCheck.EVENT, checked.setInput(bytes, 0, length), length);
      } catch (WireCheck.Malformed e) {
        throw new IOException("it sent an event that can't be read safely: " + e.getMessage(), e);
      }
      source.setInput(bytes, 0, length);
      // Cleared to its defaults, not just marked empty: a field at its default (a value's index 0,
      // false, an empty text) isn't on the wire, and must read as that default, never as what the
      // event before left there.
      event.clear();
      event.mergeFrom(source);
      received(event);
    }
  }

  /** Applies an event, and publishes the table. */
  public void received(Spotter.Event received) {
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
      runs.event(received.getRun());
    } else if (received.hasRuns()) {
      runs.listed(received.getRuns());
    }
    if (packs != null && (described || !packs.same()) && current.description != Table.NONE) {
      String said = packs.check(current.description);
      if (said != null) {
        current.packs = said;
      }
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
    if (packs != null) {
      packs.described(described.getPushedPacks());
    }
    if (described.equals(current.description)) {
      // A reconnect: the same description, so nothing changed.
      return false;
    }
    String past = past(described);
    if (!past.isEmpty()) {
      String hostname = described.getIdentity().getHostname();
      exchange.named(hostname.isEmpty() ? board.address() : hostname);
      forget();
      current.refused =
          board.name()
              + " describes "
              + past
              + ", more than the manager takes: its values and actions aren't used";
      return true;
    }
    current.refused = "";
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
    exchange.named(hostname.isEmpty() ? board.address() : hostname);
    problems(copy);
    return true;
  }

  /**
   * What a description has more of than the manager takes ({@code "5000 values (at most 2048)"});
   * empty when it's within them.
   */
  static String past(Spotter.Description described) {
    int values = described.getValues().length();
    if (values > Protocol.MAX_VALUES) {
      return values + " values (at most " + Protocol.MAX_VALUES + ")";
    }
    int actions = described.getActions().length();
    if (actions > Protocol.MAX_ACTIONS) {
      return actions + " actions (at most " + Protocol.MAX_ACTIONS + ")";
    }
    int problems = described.getProblems().length();
    if (problems > Protocol.MAX_PROBLEMS) {
      return problems + " problems (at most " + Protocol.MAX_PROBLEMS + ")";
    }
    return "";
  }

  /** Forgets the board's description: its values and actions aren't used. */
  private void forget() {
    if (current.description != Table.NONE) {
      current.describe(Table.NONE, new Field[0]);
      raws = new Utf8String[0];
      current.changes++;
    }
    current.alerts = List.of();
    current.problems = List.of();
    current.problemsAlert = "";
  }

  /** The longest a problem is quoted in its alert; the board's problems have it whole. */
  public static final int QUOTED_PROBLEM = 200;

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
  public static String problemsAlert(String board, List<String> problems) {
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
    Reading[] table = current.values;
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
      Reading value = table[index];
      value.nanos = at;
      if (value.take(raws[index], each)) {
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

  /**
   * The board's value alerts: one per value at warning or failing, in its pack's words. Past {@link
   * #MAX_ALERTS}, the failing ones first, then one says how many more there are, at the worst of
   * their levels.
   */
  private List<Alert> alerts() {
    String name = board.name();
    List<Reading> raised = new ArrayList<>();
    for (Reading value : current.values) {
      if (value.level == Level.WARNING || value.level == Level.FAILING) {
        raised.add(value);
      }
    }
    if (raised.size() > MAX_ALERTS) {
      // Stable: within a level, in the description's order.
      raised.sort(Comparator.comparing(value -> value.level != Level.FAILING));
    }
    List<Alert> alerts = new ArrayList<>();
    for (int i = 0;
        i < raised.size() && i < MAX_ALERTS - (raised.size() > MAX_ALERTS ? 1 : 0);
        i++) {
      Reading value = raised.get(i);
      alerts.add(
          new Alert(
              board.address() + "/value/" + value.id(), value.level, name, text(name, value)));
    }
    int more = raised.size() - alerts.size();
    if (more > 0) {
      Level worst = raised.get(alerts.size()).level;
      alerts.add(
          new Alert(
              board.address() + "/values/more",
              worst,
              name,
              name + ": " + more + " more values at warning or failing"));
    }
    return List.copyOf(alerts);
  }

  /** What an alert about a value says: {@code "vision-front: CPU temperature above 80 °C"}. */
  public static String text(String board, Reading value) {
    return board + ": " + value.why();
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
    // Its values and actions aren't used.
    forget();
    current.refused = "";
    current.packs = "";
    if (packs != null) {
      packs.reset();
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

  /**
   * Pushes the team's packs to the board now, whatever the robot is doing: robot code's call.
   * Completes once the board has taken them, or exceptionally, saying why not.
   */
  public CompletableFuture<Void> push() {
    if (packs == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("the manager was given no packs to push"));
    }
    return packs.push(true);
  }
}
