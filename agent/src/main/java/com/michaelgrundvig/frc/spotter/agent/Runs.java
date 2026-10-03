package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

/**
 * The actions' runs: started on request, each on a thread of its own, and kept in {@link #FOLDER}
 * (its response, its log, and its output for a file field) after they finish, until the same action
 * runs again or the most kept is reached, oldest first. {@link #FOLDER} is in {@code /run}, so the
 * runs survive an agent restart (a push's included) but not a reboot; a run that was going when the
 * agent stopped comes back {@code lost}.
 *
 * <p>Bounded: one run of each action at a time, at most {@link #AT_ONCE} at once, each within its
 * timeout, {@link #MAX_OUTPUT} kept of its output and of its standard error. Cancelling a run stops
 * it politely (SIGTERM), then forcibly after {@link #GRACE}.
 */
final class Runs implements AutoCloseable {
  /** Where runs are kept, each in a folder of its id's. */
  static final String FOLDER = "/run/frc-spotter/runs";

  /** How many actions run at once. */
  static final int AT_ONCE = 2;

  /** The most of an action's output kept, and of its standard error. */
  static final int MAX_OUTPUT = 1024 * 1024;

  /** The most the kept runs may take, together. */
  static final long MAX_KEPT = 64L * 1024 * 1024;

  /** The largest input an action takes. */
  static final long MAX_INPUT = 64L * 1024 * 1024;

  /** How long a cancelled run has, once asked to stop, before it's killed. */
  static final Duration GRACE = Duration.ofSeconds(5);

  /** How many runs' logs are read at once; another asked meanwhile is refused as busy. */
  static final int LOGS_AT_ONCE = 2;

  /** What a run's id is: its start on the monotonic clock, and some randomness. */
  static final Pattern ID = Pattern.compile("[0-9a-f]{16}");

  /** Why a run that was going when the agent stopped has no result. */
  static final String LOST = "the agent restarted while it ran";

  /** How long a stop waits for the runs going to end. */
  static final long CLOSE_MILLIS = 2000;

  /** A refusal of a request about runs, with its status. */
  static final class Refused extends Exception {
    private static final long serialVersionUID = 1L;
    final int status;

    Refused(int status, String reason) {
      super(reason);
      this.status = status;
    }
  }

  /** An action the board offers, by its id, and where its command runs. */
  record Declared(String id, String folder, Pack.Action action) {}

  /** A run as it's kept. */
  private static final class Kept {
    final String run;
    final String action;
    Spotter.RunState state;
    final Commands.Cancellation cancellation = new Commands.Cancellation();
    long lines;

    Kept(String run, String action, Spotter.RunState state) {
      this.run = run;
      this.action = action;
      this.state = state;
    }
  }

  private final Host host;
  private final Commands commands;
  private final Events events;
  private final Map<String, Declared> actions = new LinkedHashMap<>();
  private final LinkedHashMap<String, Kept> kept = new LinkedHashMap<>();
  private final SecureRandom random = new SecureRandom();
  // Package-private, for a test to hold.
  final Semaphore logReads = new Semaphore(LOGS_AT_ONCE);
  private final ExecutorService threads =
      Executors.newCachedThreadPool(
          work -> {
            Thread thread = new Thread(work, "spotter-action");
            thread.setDaemon(true);
            return thread;
          });
  private final long maxKept;
  private int running;
  // Set as the agent stops: a run its stop ends keeps the state it had, going.
  private volatile boolean closed;

  /**
   * @param actions every action the board offers: the built-in ones, then its packs'
   */
  Runs(Host host, Commands commands, Events events, List<Declared> actions) {
    this(host, commands, events, actions, MAX_KEPT);
  }

  /**
   * @param actions every action the board offers: the built-in ones, then its packs'
   * @param maxKept the most the kept runs may take, together
   */
  Runs(Host host, Commands commands, Events events, List<Declared> actions, long maxKept) {
    this.host = host;
    this.commands = commands;
    this.events = events;
    this.maxKept = maxKept;
    for (Declared action : actions) {
      this.actions.put(action.id(), action);
    }
  }

  /** Every action the board offers: the built-in ones (core's), then its packs', by id. */
  static List<Declared> declared(List<Pack> packs) {
    List<Declared> all = new ArrayList<>();
    for (Pack.Action action : Describer.BUILT_IN) {
      all.add(new Declared(Describer.CORE + "." + action.id(), "/", action));
    }
    for (Pack pack : packs) {
      for (Pack.Action action : pack.actions()) {
        all.add(new Declared(pack.name() + "." + action.id(), pack.folder(), action));
      }
    }
    return all;
  }

  /** An action the board offers, by its id. */
  Optional<Declared> action(String id) {
    return Optional.ofNullable(actions.get(id));
  }

  private Path folder() {
    return host.path(FOLDER);
  }

  /**
   * Takes up the runs a previous agent kept, in the order they started: one that was going comes
   * back lost, as it ended with that agent.
   */
  void restore() {
    List<Path> folders = new ArrayList<>();
    try (DirectoryStream<Path> each = Files.newDirectoryStream(folder())) {
      for (Path path : each) {
        if (ID.matcher(path.getFileName().toString()).matches()) {
          folders.add(path);
        }
      }
    } catch (NoSuchFileException e) {
      return;
    } catch (IOException e) {
      host.log("Couldn't read the runs kept in " + FOLDER + ": " + e);
      return;
    }
    folders.sort(null);
    for (Path path : folders) {
      String run = path.getFileName().toString();
      try {
        Spotter.RunState state =
            ProtoMessage.mergeFrom(
                Spotter.RunState.newInstance(), Files.readAllBytes(path.resolve("state")));
        if (state.getRunning()) {
          Declared declared = actions.get(state.getAction());
          List<Field> fields =
              declared != null
                  ? declared.action().response()
                  : List.of(
                      Field.of(Fill.OUTCOME, Spotter.FieldType.FIELD_TYPE_TEXT),
                      Field.of(Fill.OUTCOME_MESSAGE, Spotter.FieldType.FIELD_TYPE_TEXT));
          Commands.Kind kind = declared != null ? Fill.kind(declared.action().command()) : null;
          state =
              finished(
                  state,
                  Commands.Result.failed(
                      kind != null ? kind : Commands.Kind.RUN, Spotter.Outcome.OUTCOME_LOST, LOST),
                  fields,
                  run);
          write(path.resolve("state"), state);
          Files.deleteIfExists(path.resolve("input"));
        }
        // Its log isn't read: a run taken up never logs again (one that was going is lost), so
        // its count of lines is never needed.
        Kept restored = new Kept(run, state.getAction(), state);
        synchronized (this) {
          kept.put(run, restored);
        }
      } catch (IOException | RuntimeException e) {
        host.log("Couldn't take up run " + run + " (" + e + "): it's dropped");
        delete(path);
      }
    }
  }

  /**
   * A file to take an action's input into, in {@link #FOLDER}: the request's body, read to its end
   * before the run starts.
   */
  Path input() throws IOException {
    Files.createDirectories(folder());
    return Files.createTempFile(folder(), ".input-", "");
  }

  /**
   * Starts an action: {@code 409} if it's running, {@code 503} if as many are running as may.
   *
   * @param input its input, taken into the run; null for none
   */
  Spotter.Started start(String id, @Nullable Path input) throws Refused, IOException {
    Declared declared = actions.get(id);
    if (declared == null) {
      throw new Refused(404, "this board has no action " + id);
    }
    String run;
    Kept started;
    synchronized (this) {
      for (Kept each : kept.values()) {
        if (each.action.equals(id) && each.state.getRunning()) {
          throw new Refused(409, id + " is running already, as run " + each.run);
        }
      }
      if (running >= AT_ONCE) {
        throw new Refused(503, AT_ONCE + " actions are running, as many as may; ask again soon");
      }
      // What's kept of a run lasts until its action runs again.
      for (Kept each : new ArrayList<>(kept.values())) {
        if (each.action.equals(id)) {
          drop(each);
        }
      }
      run = id(host.monotonicNanos());
      Files.createDirectories(folder().resolve(run));
      if (input != null) {
        Files.move(input, folder().resolve(run).resolve("input"), StandardCopyOption.ATOMIC_MOVE);
      }
      Spotter.RunState state =
          Spotter.RunState.newInstance().setRun(run).setAction(id).setRunning(true);
      write(folder().resolve(run).resolve("state"), state);
      started = new Kept(run, id, state);
      kept.put(run, started);
      running++;
    }
    host.log("Action " + id + " started, as run " + run);
    events.run(event(run, id).setStarted(true));
    Path given = input == null ? null : folder().resolve(run).resolve("input");
    threads.execute(() -> execute(declared, started, given));
    return Spotter.Started.newInstance().setRun(run);
  }

  /** A run's id: its start on the monotonic clock in microseconds, so ids sort as runs started. */
  private String id(long nanos) {
    byte[] some = new byte[3];
    random.nextBytes(some);
    String micros = String.format("%011x", (nanos / 1000) & 0xfffffffffffL);
    return micros + HexFormat.of().formatHex(some).substring(0, 5);
  }

  private void execute(Declared declared, Kept run, @Nullable Path input) {
    Path folder = folder().resolve(run.run);
    Spotter.RunState state;
    try (OutputStream log =
        new BufferedOutputStream(
            Files.newOutputStream(
                folder.resolve("log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
      ProtoSink sink = ProtoSink.newInstance(log);
      Commands.Result result =
          commands.run(
              declared.folder(),
              declared.action().command(),
              declared.action().timeout(),
              new Commands.Options(
                  input,
                  Map.of(),
                  MAX_OUTPUT,
                  MAX_OUTPUT,
                  line -> logged(run, sink, log, line),
                  GRACE),
              run.cancellation);
      Files.write(folder.resolve("output"), result.output());
      state = finished(run.state, result, declared.action().response(), run.run);
    } catch (Throwable e) {
      // Whatever it meets, a stack overflow on a deeply nested output included, the run finishes
      // and its slot is free again.
      host.log("Run " + run.run + " of " + run.action + " failed: " + e);
      state =
          finished(
              run.state,
              Commands.Result.failed(
                  Fill.kind(declared.action().command()),
                  Spotter.Outcome.OUTCOME_COULD_NOT_START,
                  "the agent couldn't run it: " + e.getMessage()),
              declared.action().response(),
              run.run);
    }
    if (closed) {
      // The agent stopped while it ran, and its process with it: its state is left as it was,
      // going, so the next start takes it up as lost, as after any restart.
      host.log("Run " + run.run + " of " + run.action + " ended as the agent stopped");
      return;
    }
    try {
      write(folder.resolve("state"), state);
      if (input != null) {
        Files.deleteIfExists(input);
      }
    } catch (IOException e) {
      host.log("Couldn't keep run " + run.run + ": " + e);
    }
    synchronized (this) {
      run.state = state;
      running--;
    }
    host.log(
        "Action "
            + run.action
            + " finished, run "
            + run.run
            + ": "
            + Fill.name(state.getResult().getOutcome())
            + (state.getResult().getOutcomeMessage().isEmpty()
                ? ""
                : " (" + state.getResult().getOutcomeMessage() + ")"));
    events.run(event(run.run, run.action).setFinished(state.getResult()));
    cap();
  }

  /** A line of a run's standard error: an entry of its log, kept and sent as it's read. */
  private void logged(Kept run, ProtoSink sink, OutputStream log, String line) {
    Optional<Spotter.LogEntry> read =
        LogLines.entry(line, Pack.LogMap.DEFAULT, System.currentTimeMillis() * 1000);
    if (read.isEmpty()) {
      return;
    }
    Spotter.LogEntry entry;
    synchronized (this) {
      entry = read.get().setCursor(Long.toString(++run.lines));
    }
    try {
      entry.writeDelimitedTo(sink);
      log.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    events.run(event(run.run, run.action).setLog(entry));
  }

  /** A run's state once it's finished, with its response filled from its result. */
  private static Spotter.RunState finished(
      Spotter.RunState state, Commands.Result result, List<Field> fields, String run) {
    Spotter.RunResult finished =
        Spotter.RunResult.newInstance()
            .setOutcome(result.outcome())
            .setOutcomeMessage(result.message());
    List<Spotter.FieldValue> values = Fill.fill(fields, result, MAX_OUTPUT, files(run));
    for (int i = 0; i < fields.size(); i++) {
      finished.addResponse(values.get(i).setName(fields.get(i).name()));
    }
    return state.clone().setRunning(false).setResult(bounded(finished));
  }

  /**
   * A result within {@link Protocol#MAX_RESPONSE}, as a stream's event carries it: its largest
   * fields made unavailable, saying why, until it fits. Its output stays whole in the run's folder,
   * where a {@code file} field downloads it.
   */
  static Spotter.RunResult bounded(Spotter.RunResult result) {
    while (result.getSerializedSize() > Protocol.MAX_RESPONSE) {
      Spotter.FieldValue largest = null;
      for (Spotter.FieldValue each : result.getMutableResponse()) {
        if (largest == null || each.getSerializedSize() > largest.getSerializedSize()) {
          largest = each;
        }
      }
      if (largest == null || largest.getSerializedSize() < 1024) {
        break;
      }
      int size = largest.getSerializedSize();
      String name = largest.getName();
      largest
          .clear()
          .setName(name)
          .setUnavailable(
              "too large to send: "
                  + size / 1024
                  + " KiB, more than the "
                  + Protocol.MAX_RESPONSE / 1024
                  + " KiB a run's response may be (declare the whole output type: file to"
                  + " download it)");
    }
    return result;
  }

  /** Where a run's file fields are fetched. */
  static String files(String run) {
    return "/v2/runs/" + run + "/files/";
  }

  private Spotter.RunEvent event(String run, String action) {
    return Spotter.RunEvent.newInstance()
        .setRun(run)
        .setAction(action)
        .setTimeNanos(host.monotonicNanos());
  }

  /**
   * Cancels a run: stopped politely, then forcibly after {@link #GRACE}. {@code 404} if it isn't
   * kept, {@code 409} if it has finished.
   */
  void cancel(String run) throws Refused {
    Kept each;
    synchronized (this) {
      each = kept.get(run);
      if (each == null) {
        throw new Refused(404, "no run " + run + " is kept");
      }
      if (!each.state.getRunning()) {
        throw new Refused(409, "run " + run + " has finished");
      }
    }
    host.log("Run " + run + " of " + each.action + " cancelled");
    each.cancellation.cancel();
  }

  /** Every run kept, in the order they started. */
  synchronized List<Spotter.RunState> states() {
    List<Spotter.RunState> all = new ArrayList<>();
    for (Kept each : kept.values()) {
      all.add(each.state.clone());
    }
    return all;
  }

  /** A kept run's state. */
  synchronized Optional<Spotter.RunState> state(String run) {
    Kept each = kept.get(run);
    return each == null ? Optional.empty() : Optional.of(each.state.clone());
  }

  /**
   * A finished run's file field: its bytes, and the name it downloads as.
   *
   * @param path where its bytes are
   * @param name the name it downloads as
   */
  record Download(Path path, String name) {}

  /** A finished run's file field, by its name: {@code 404} unless it has one. */
  Download file(String run, String field) throws Refused {
    Spotter.RunState state =
        state(run).orElseThrow(() -> new Refused(404, "no run " + run + " is kept"));
    for (Spotter.FieldValue value : state.getResult().getResponse()) {
      if (value.getName().equals(field) && value.hasFileUrl()) {
        Declared declared = actions.get(state.getAction());
        String name = field;
        if (declared != null) {
          for (Field each : declared.action().response()) {
            if (each.name().equals(field) && !each.fileName().isEmpty()) {
              name = each.fileName();
            }
          }
        }
        return new Download(folder().resolve(run).resolve("output"), name);
      }
    }
    throw new Refused(404, "run " + run + " has no file " + field);
  }

  /**
   * A page of a kept run's log: read as a stream, a page's worth held, at most {@link
   * #LOGS_AT_ONCE} at once ({@code 503} past that).
   */
  Spotter.LogPage log(String run, Logs.Paging paging) throws Refused, IOException {
    if (state(run).isEmpty()) {
      throw new Refused(404, "no run " + run + " is kept");
    }
    if (!logReads.tryAcquire()) {
      throw new Refused(503, "as many runs' logs are being read as may; ask again shortly");
    }
    try {
      Logs.Slice slice = new Logs.Slice(paging);
      Path log = folder().resolve(run).resolve("log");
      if (Files.exists(log)) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(log))) {
          ProtoSource source = ProtoSource.newInstance(in);
          while (!source.isAtEnd()) {
            if (!slice.take(Spotter.LogEntry.newInstance().mergeDelimitedFrom(source))) {
              break;
            }
          }
        } catch (IOException e) {
          // The last entry was cut short as the agent stopped: the rest stand.
        }
      }
      return slice.page();
    } finally {
      logReads.release();
    }
  }

  /** Drops the oldest finished runs while the kept ones take more than the most kept. */
  private synchronized void cap() {
    long total = 0;
    for (Kept each : kept.values()) {
      total += size(folder().resolve(each.run));
    }
    for (Kept each : new ArrayList<>(kept.values())) {
      if (total <= maxKept) {
        return;
      }
      if (!each.state.getRunning()) {
        total -= size(folder().resolve(each.run));
        drop(each);
        host.log("Run " + each.run + " of " + each.action + " dropped: the runs kept were full");
      }
    }
  }

  private void drop(Kept each) {
    kept.remove(each.run);
    delete(folder().resolve(each.run));
  }

  private static long size(Path folder) {
    try (Stream<Path> files = Files.walk(folder)) {
      return files.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
    } catch (IOException e) {
      return 0;
    }
  }

  private void delete(Path folder) {
    try (Stream<Path> files = Files.walk(folder)) {
      for (Path each : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(each);
      }
    } catch (IOException e) {
      host.log("Couldn't remove " + folder + ": " + e);
    }
  }

  /** Writes a state whole, then renames it into place, so it's never read half written. */
  private static void write(Path file, Spotter.RunState state) throws IOException {
    Path part = file.resolveSibling(file.getFileName() + ".part");
    Files.write(part, state.toByteArray());
    Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /**
   * Stops the runs going (their processes, at once) and waits a moment for them to end, so none
   * writes its state once another agent may be taking it up.
   */
  @Override
  public void close() {
    closed = true;
    threads.shutdownNow();
    try {
      if (!threads.awaitTermination(CLOSE_MILLIS, TimeUnit.MILLISECONDS)) {
        host.log("A run hadn't ended " + CLOSE_MILLIS + " ms after the agent stopped");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
