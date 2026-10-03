package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Runs a pack's commands: the only place the agent starts a process, asks a URL, or reads a pack's
 * file. Each is bounded: by its timeout (a process's whole group is stopped, a request is cut off,
 * a read is interrupted), and by the most of its output kept. A process runs in a group of its own
 * (setsid), its output and standard error read on threads of their own, and is waited on, never the
 * end of its pipes: what it left running that holds them is stopped once it has exited. Nothing
 * here comes from a request: every program, URL and path is its pack's, fixed when the agent
 * started. What a request brings (an action's input, a log's paging) is data: on standard input, in
 * a request's body, or in the environment, never on a command line.
 */
final class Commands implements Runner, AutoCloseable {
  /** The most of a command's standard error kept to say why it failed: its first line. */
  static final int MAX_ERRORS = 200;

  /** The longest line of standard error handed over whole; a longer one is cut. */
  static final int MAX_LINE = 8 * 1024;

  /**
   * How long a command's output may stay open once it has exited, held by something it left
   * running, before that's stopped.
   */
  static final long LEFT_MILLIS = 500;

  /** The errno in why a program couldn't start. */
  private static final Pattern ERRNO = Pattern.compile("error(?:=|: )([0-9]+)");

  private final Host host;

  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(daemon("spotter-timer"));

  /** Reads commands' standard output and standard error, a thread each while they run. */
  private final ExecutorService readers = Executors.newCachedThreadPool(daemon("spotter-output"));

  /**
   * setsid (util-linux's), which runs a command as the leader of a process group of its own; null
   * where there's none, and a command's descendants are all that's stopped.
   */
  private final @Nullable String setsid;

  Commands(Host host) {
    this.host = host;
    this.setsid = Files.isExecutable(Path.of(SETSID)) ? SETSID : null;
  }

  /** Where setsid is, on Debian and its kind. */
  private static final String SETSID = "/usr/bin/setsid";

  /** The kinds of command, as a result tells them apart. */
  enum Kind {
    RUN,
    HTTP,
    READ
  }

  /**
   * How a command went.
   *
   * @param kind its kind
   * @param outcome {@code OUTCOME_COMPLETED} when it ran to its end (a process exited, a response
   *     came back, a file was read); otherwise why not
   * @param message why it didn't complete, such as "no such file: /opt/team/x.sh"; empty when it
   *     did
   * @param code a process's exit code, or a response's HTTP status, when completed
   * @param output its standard output, its body, or the file's bytes: at most the most kept
   * @param truncated whether it had more output than was kept
   * @param errors a process's first line of standard error, why it failed in its own words
   * @param ended done once its process has exited: done already, but for a process stopped that
   *     won't go (stuck in the kernel, on a wedged device, say), whose command is still running, as
   *     its message says
   */
  record Result(
      Kind kind,
      Spotter.Outcome outcome,
      String message,
      int code,
      byte[] output,
      boolean truncated,
      String errors,
      CompletableFuture<Void> ended) {
    /** How a command went that has ended. */
    Result(
        Kind kind,
        Spotter.Outcome outcome,
        String message,
        int code,
        byte[] output,
        boolean truncated,
        String errors) {
      this(kind, outcome, message, code, output, truncated, errors, ENDED);
    }

    /** Ended already. */
    static final CompletableFuture<Void> ENDED = CompletableFuture.completedFuture(null);

    boolean completed() {
      return outcome == Spotter.Outcome.OUTCOME_COMPLETED;
    }

    /** Its output as text. */
    String text() {
      return new String(output, StandardCharsets.UTF_8);
    }

    static Result failed(Kind kind, Spotter.Outcome outcome, String message) {
      return new Result(kind, outcome, message, 0, new byte[0], false, "");
    }
  }

  /**
   * What a command run for an action or a log is given, and how it's bounded.
   *
   * @param input its input: a process's standard input, a request's body; null for none
   * @param environment variables added to the agent's own: a log's paging
   * @param maxOutput the most of its output kept, in bytes
   * @param maxErrors the most of its standard error handed over, in bytes
   * @param errors each line of its standard error, as it's read
   * @param grace how long a process stopped politely (SIGTERM) has before it's killed: zero kills
   *     it at once
   */
  record Options(
      @Nullable Path input,
      Map<String, String> environment,
      int maxOutput,
      int maxErrors,
      Consumer<String> errors,
      Duration grace) {
    /** A collector's: no input, its output bounded, its standard error's first line kept. */
    static Options collector(int maxOutput) {
      return new Options(null, Map.of(), maxOutput, 0, line -> {}, Duration.ZERO);
    }
  }

  /**
   * Stops a command from another thread: a process politely, then forcibly after its grace; a
   * request by closing it. Asked before the command starts, it stops it as it starts.
   */
  static final class Cancellation {
    private @Nullable Runnable stop;
    private boolean cancelled;

    /** Stops the command, once. */
    void cancel() {
      Runnable now;
      synchronized (this) {
        if (cancelled) {
          return;
        }
        cancelled = true;
        now = stop;
      }
      if (now != null) {
        now.run();
      }
    }

    synchronized boolean cancelled() {
      return cancelled;
    }

    private void onCancel(Runnable stop) {
      boolean already;
      synchronized (this) {
        this.stop = stop;
        already = cancelled;
      }
      if (already) {
        stop.run();
      }
    }
  }

  @Override
  public Result run(String folder, Command command, Duration timeout, int maxOutput) {
    return run(folder, command, timeout, Options.collector(maxOutput), new Cancellation());
  }

  /**
   * Runs a command once, on the caller's thread.
   *
   * @param folder its pack's folder on the coprocessor: where it runs, and where its {@code ./}
   *     programs are
   * @param timeout how long it may take
   * @param cancellation stops it from another thread
   */
  Result run(
      String folder,
      Command command,
      Duration timeout,
      Options options,
      Cancellation cancellation) {
    if (command instanceof Command.Run) {
      return process(folder, (Command.Run) command, timeout, options, cancellation);
    }
    if (command instanceof Command.Http) {
      return http((Command.Http) command, timeout, options, cancellation);
    }
    return read((Command.Read) command, timeout, options.maxOutput());
  }

  // ---- run ----

  private Result process(
      String folder,
      Command.Run run,
      Duration timeout,
      Options options,
      Cancellation cancellation) {
    List<String> argv = new ArrayList<>(run.argv());
    String program = run.programPath(folder);
    // Found and checked first: setsid starts whatever it's given, so a program that isn't there,
    // or can't be run, is said here, as the process's own start would have said it.
    Path found;
    if (run.byPath() || run.program().contains("/")) {
      found = run.byPath() ? host.path(program) : host.path(folder).resolve(run.program());
    } else {
      found = onPath(run.program());
      if (found == null) {
        return Result.failed(
            Kind.RUN, Spotter.Outcome.OUTCOME_COULD_NOT_START, "no such file: " + run.program());
      }
    }
    if (!Files.exists(found)) {
      return Result.failed(
          Kind.RUN, Spotter.Outcome.OUTCOME_COULD_NOT_START, "no such file: " + program);
    }
    if (!Files.isExecutable(found) || Files.isDirectory(found)) {
      return Result.failed(
          Kind.RUN, Spotter.Outcome.OUTCOME_COULD_NOT_START, notExecutable(program, run.program()));
    }
    argv.set(0, found.toString());
    if (setsid != null) {
      // Its own process group, of which it's the leader: a timeout or a cancel signals all of it,
      // what it left running in the background included.
      argv.add(0, setsid);
    }
    Process process;
    try {
      ProcessBuilder builder =
          new ProcessBuilder(argv)
              .directory(host.path(folder).toFile())
              .redirectInput(
                  ProcessBuilder.Redirect.from(
                      options.input() == null ? nullDevice() : options.input().toFile()));
      builder.environment().putAll(options.environment());
      process = builder.start();
    } catch (IOException e) {
      return Result.failed(
          Kind.RUN, Spotter.Outcome.OUTCOME_COULD_NOT_START, why(e, program, run.program()));
    } catch (IllegalArgumentException e) {
      // The JDK refuses a variable a process can't be given (a NUL in it, say): never quoted, as
      // a log's paging is anyone's.
      return Result.failed(
          Kind.RUN,
          Spotter.Outcome.OUTCOME_COULD_NOT_START,
          "its environment can't be given to a process: a value has a character none takes");
    }
    // Its output and its standard error, each read on a thread of its own: the wait below is on
    // the process and its deadline, never on the end of a pipe that what it left may hold open.
    Output out = new Output(options.maxOutput());
    CompletableFuture<Void> outRead =
        CompletableFuture.runAsync(() -> out.read(process.getInputStream()), readers);
    Errors errs = new Errors(options);
    CompletableFuture<Void> errRead =
        CompletableFuture.runAsync(() -> errs.read(process.getErrorStream()), readers);
    CompletableFuture<Void> read = CompletableFuture.allOf(outRead, errRead);
    // Gone: it has exited, and nothing holds its output. (Its exit is asked of the process itself:
    // the JDK completes onExit only once it has drained the pipes a reader may be blocked on.)
    BooleanSupplier gone = () -> !process.isAlive() && read.isDone();
    AtomicReference<Spotter.Outcome> stopped = new AtomicReference<>();
    Runnable stop = () -> stop(process, options.grace(), gone);
    // Its deadline is its process's: one that exited in time isn't timed out by what it left.
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              if (process.isAlive()
                  && stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_TIMED_OUT)) {
                stop.run();
              }
            },
            Math.max(1, timeout.toMillis()),
            TimeUnit.MILLISECONDS);
    cancellation.onCancel(
        () -> {
          if (stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_CANCELLED)) {
            stop.run();
          }
        });
    try {
      // Until it exits (a timeout or a cancel stops it); a moment past its deadline, it's stopped
      // here; once stopped, its grace and a second more.
      if (process.waitFor(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS)) {
        deadline.cancel(false);
      } else if (stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_TIMED_OUT)) {
        stop.run();
      }
      if (stopped.get() != null) {
        process.waitFor(options.grace().toMillis() + 1000, TimeUnit.MILLISECONDS);
      }
      // Its output, once it has exited: closed at once, unless something it left running holds it
      // open, which is stopped, its whole group, so neither it nor a reader is left behind.
      if (!process.isAlive() && !await(read, LEFT_MILLIS)) {
        stop(process, Duration.ofMillis(LEFT_MILLIS), gone);
        await(read, 2 * LEFT_MILLIS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_CANCELLED)) {
        stop(process, Duration.ZERO, gone);
      }
    } finally {
      deadline.cancel(false);
      // Whatever its readers read from here on is dropped.
      errs.done();
    }
    Spotter.Outcome outcome = stopped.get();
    boolean running = process.isAlive();
    CompletableFuture<Void> ended = process.onExit().thenApply(exited -> null);
    if (outcome != null || running) {
      if (outcome == null) {
        outcome = Spotter.Outcome.OUTCOME_TIMED_OUT;
      }
      String message =
          outcome == Spotter.Outcome.OUTCOME_TIMED_OUT
              ? "timed out after " + PackReader.show(timeout)
              : "cancelled";
      return new Result(
          Kind.RUN,
          outcome,
          running ? message + "; its command is still running" : message,
          0,
          out.kept(),
          out.truncated(),
          errs.first(),
          ended);
    }
    return new Result(
        Kind.RUN,
        Spotter.Outcome.OUTCOME_COMPLETED,
        "",
        process.exitValue(),
        out.kept(),
        out.truncated(),
        errs.first(),
        ended);
  }

  /**
   * Why a program couldn't start, as a person would say it: from its errno, which Java 17 writes
   * {@code error=2,} and later Javas {@code error: 2}.
   *
   * @param program where it is
   * @param written how its pack writes it: {@code ./health}
   */
  private static String why(IOException e, String program, String written) {
    Matcher errno = ERRNO.matcher(String.valueOf(e.getMessage()));
    if (errno.find()) {
      if (errno.group(1).equals("2")) {
        return "no such file: " + program;
      }
      if (errno.group(1).equals("13")) {
        return notExecutable(program, written);
      }
    }
    return String.valueOf(e.getMessage());
  }

  /**
   * Why a program can't be run: nearly always a script without its execute bit, as a copy that
   * drops modes leaves it. The fix is said.
   */
  private static String notExecutable(String program, String written) {
    return "not executable ("
        + program
        + "): chmod +x, or name its interpreter: run: [sh, "
        + written
        + "]";
  }

  /**
   * Stops a command, and everything it started: politely (SIGTERM), then, after its grace, forcibly
   * (SIGKILL); at once with no grace. Its whole process group is signalled, so what it left running
   * in the background stops too; and its descendants, any that left the group.
   *
   * @param gone whether it has exited and nothing holds its output: then there's nothing to kill
   */
  private void stop(Process process, Duration grace, BooleanSupplier gone) {
    if (grace.isZero()) {
      kill(process, gone);
      return;
    }
    // Through its handle, not Process.destroy, which closes its streams: what it says as it stops
    // is still read, into its log.
    signal(process, "TERM");
    process.descendants().forEach(ProcessHandle::destroy);
    process.toHandle().destroy();
    timer.schedule(() -> kill(process, gone), grace.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Kills a command, and everything it started: unless it's ended already. */
  private void kill(Process process, BooleanSupplier gone) {
    if (gone.getAsBoolean()) {
      return;
    }
    signal(process, "KILL");
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /**
   * Signals a command's process group: setsid made it the group's leader, so its number is the
   * group's. The JDK signals a process, never a group, so the shell's own {@code kill} does it,
   * which every system has.
   */
  private void signal(Process process, String signal) {
    if (setsid == null) {
      return;
    }
    try {
      Process kill =
          new ProcessBuilder(
                  "/bin/sh",
                  "-c",
                  "kill -s \"$0\" -- \"-$1\" 2>/dev/null",
                  signal,
                  Long.toString(process.pid()))
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
              .start();
      kill.waitFor(1, TimeUnit.SECONDS);
    } catch (IOException e) {
      // No process to signal with (the board out of tasks): its descendants are signalled below.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Waits for something to be done, at most so long: whether it is. */
  private static boolean await(CompletableFuture<?> done, long millis) throws InterruptedException {
    try {
      done.get(Math.max(0, millis), TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (ExecutionException e) {
      // A reader failed: done all the same.
      return true;
    }
  }

  /** Where a program named without a path is: the first that may be run of its name on the PATH. */
  private static @Nullable Path onPath(String name) {
    String path = System.getenv("PATH");
    for (String folder : (path == null ? "/usr/bin:/bin" : path).split(File.pathSeparator)) {
      if (folder.isEmpty()) {
        continue;
      }
      Path candidate = Path.of(folder, name);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** What a command's standard output keeps: at most so much, the rest read and dropped. */
  private static final class Output {
    private final int max;
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private boolean truncated;

    Output(int max) {
      this.max = max;
    }

    /** Reads to its end, keeping at most the most kept: a process never blocks writing it. */
    void read(InputStream in) {
      byte[] chunk = new byte[8192];
      try (in) {
        int read;
        while ((read = in.read(chunk)) != -1) {
          synchronized (this) {
            int room = max - kept.size();
            if (read > room) {
              kept.write(chunk, 0, Math.max(0, room));
              truncated = true;
            } else {
              kept.write(chunk, 0, read);
            }
          }
        }
      } catch (IOException e) {
        // Its pipe closed under it: what was read stands.
      }
    }

    synchronized byte[] kept() {
      return kept.toByteArray();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }

  /**
   * A command's standard error, read until it closes, so the process never blocks writing it: each
   * line handed over (at most {@code maxErrors} bytes of them, each cut to {@link #MAX_LINE}) until
   * the command's run is {@link #done}, and the first that isn't blank kept, cut to {@link
   * #MAX_ERRORS} characters, to say why it failed.
   */
  private static final class Errors {
    private final Options options;
    private volatile boolean handing = true;
    private volatile String first = "";
    private long handed;

    Errors(Options options) {
      this.options = options;
    }

    /** Its run is over: no more lines are handed over. */
    void done() {
      handing = false;
    }

    /** The first line that isn't blank, so far. */
    String first() {
      return first;
    }

    void read(InputStream in) {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      try (in) {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
          for (int i = 0; i < read; i++) {
            if (chunk[i] != '\n') {
              if (line.size() < MAX_LINE) {
                line.write(chunk[i]);
              }
              continue;
            }
            take(line.toString(StandardCharsets.UTF_8), 1);
            line.reset();
          }
        }
      } catch (IOException e) {
        // Its pipe closed under it: what was read stands.
      }
      String rest = line.toString(StandardCharsets.UTF_8);
      if (!rest.isEmpty()) {
        take(rest, 0);
      }
    }

    private void take(String text, int newline) {
      if (first.isEmpty() && !text.isBlank()) {
        String stripped = text.strip();
        first = stripped.length() <= MAX_ERRORS ? stripped : stripped.substring(0, MAX_ERRORS);
      }
      if (handing && handed + text.length() + newline <= options.maxErrors()) {
        handed += text.length() + newline;
        options.errors().accept(text);
      }
    }
  }

  // ---- http ----

  private Result http(
      Command.Http http, Duration timeout, Options options, Cancellation cancellation) {
    HttpURLConnection connection;
    try {
      connection = (HttpURLConnection) new URI(http.url()).toURL().openConnection(Proxy.NO_PROXY);
    } catch (URISyntaxException | IOException | IllegalArgumentException e) {
      return Result.failed(
          Kind.HTTP, Spotter.Outcome.OUTCOME_UNREACHABLE, "not a URL: " + http.url());
    }
    int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
    connection.setConnectTimeout(millis);
    connection.setReadTimeout(millis);
    connection.setInstanceFollowRedirects(false);
    connection.setUseCaches(false);
    AtomicReference<Spotter.Outcome> stopped = new AtomicReference<>();
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              if (stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_TIMED_OUT)) {
                connection.disconnect();
              }
            },
            millis,
            TimeUnit.MILLISECONDS);
    cancellation.onCancel(
        () -> {
          if (stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_CANCELLED)) {
            connection.disconnect();
          }
        });
    String where = where(http.url());
    try {
      connection.setRequestMethod(http.method());
      if (http.method().equals("POST")) {
        send(connection, http.form(), http.file(), options.input());
      }
      int status = connection.getResponseCode();
      ByteArrayOutputStream kept = new ByteArrayOutputStream();
      boolean truncated = false;
      InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      if (in != null) {
        try (in) {
          truncated = copy(in, kept, options.maxOutput(), false);
        }
      }
      if (stopped.get() == Spotter.Outcome.OUTCOME_CANCELLED) {
        return Result.failed(Kind.HTTP, Spotter.Outcome.OUTCOME_CANCELLED, "cancelled");
      }
      return new Result(
          Kind.HTTP,
          Spotter.Outcome.OUTCOME_COMPLETED,
          "",
          status,
          kept.toByteArray(),
          truncated,
          "");
    } catch (SocketTimeoutException e) {
      return timedOut(timeout, where);
    } catch (ConnectException e) {
      return Result.failed(
          Kind.HTTP, Spotter.Outcome.OUTCOME_UNREACHABLE, "connection refused (" + where + ")");
    } catch (NoRouteToHostException | UnknownHostException e) {
      return Result.failed(
          Kind.HTTP, Spotter.Outcome.OUTCOME_UNREACHABLE, "no route (" + where + ")");
    } catch (IOException e) {
      if (stopped.get() == Spotter.Outcome.OUTCOME_TIMED_OUT) {
        return timedOut(timeout, where);
      }
      if (stopped.get() == Spotter.Outcome.OUTCOME_CANCELLED) {
        return Result.failed(Kind.HTTP, Spotter.Outcome.OUTCOME_CANCELLED, "cancelled");
      }
      return Result.failed(
          Kind.HTTP,
          Spotter.Outcome.OUTCOME_UNREACHABLE,
          String.valueOf(e.getMessage()) + " (" + where + ")");
    } finally {
      deadline.cancel(false);
      connection.disconnect();
    }
  }

  private static Result timedOut(Duration timeout, String where) {
    return Result.failed(
        Kind.HTTP,
        Spotter.Outcome.OUTCOME_TIMED_OUT,
        "no answer within " + PackReader.show(timeout) + " (" + where + ")");
  }

  /**
   * Sends a POST's body: its input as it is, or as a form's one file field ({@code form}, as
   * multipart/form-data, saying it sends a file named {@code filename}); nothing when it has none.
   */
  private static void send(
      HttpURLConnection connection, String form, String filename, @Nullable Path input)
      throws IOException {
    connection.setDoOutput(true);
    if (input == null) {
      connection.setFixedLengthStreamingMode(0);
      connection.getOutputStream().close();
      return;
    }
    if (form.isEmpty()) {
      connection.setRequestProperty("Content-Type", "application/octet-stream");
      connection.setFixedLengthStreamingMode(Files.size(input));
      try (OutputStream body = connection.getOutputStream()) {
        Files.copy(input, body);
      }
      return;
    }
    String boundary = "spotter-" + UUID.randomUUID();
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + form
                + "\"; filename=\""
                + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
    connection.setFixedLengthStreamingMode(head.length + Files.size(input) + tail.length);
    try (OutputStream body = connection.getOutputStream()) {
      body.write(head);
      Files.copy(input, body);
      body.write(tail);
    }
  }

  /** A URL's host and port, as a reason names them: {@code localhost:5800}. */
  private static String where(String url) {
    try {
      URI uri = new URI(url);
      return uri.getHost() + ":" + (uri.getPort() < 0 ? 80 : uri.getPort());
    } catch (URISyntaxException e) {
      return url;
    }
  }

  // ---- file ----

  private Result read(Command.Read read, Duration timeout, int maxOutput) {
    Thread reader = Thread.currentThread();
    Object lock = new Object();
    boolean[] done = {false};
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              synchronized (lock) {
                if (!done[0]) {
                  timedOut.set(true);
                  reader.interrupt();
                }
              }
            },
            Math.max(1, timeout.toMillis()),
            TimeUnit.MILLISECONDS);
    try (FileChannel channel = FileChannel.open(host.path(read.path()), StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.allocate(maxOutput + 1);
      while (buffer.hasRemaining() && channel.read(buffer) != -1) {
        // read on, to the end or the most kept
      }
      buffer.flip();
      boolean truncated = buffer.remaining() > maxOutput;
      byte[] bytes = new byte[Math.min(buffer.remaining(), maxOutput)];
      buffer.get(bytes);
      return new Result(Kind.READ, Spotter.Outcome.OUTCOME_COMPLETED, "", 0, bytes, truncated, "");
    } catch (NoSuchFileException e) {
      return Result.failed(
          Kind.READ, Spotter.Outcome.OUTCOME_COULD_NOT_START, "no such file: " + read.path());
    } catch (AccessDeniedException e) {
      return Result.failed(
          Kind.READ, Spotter.Outcome.OUTCOME_COULD_NOT_START, "not allowed: " + read.path());
    } catch (ClosedByInterruptException e) {
      return Result.failed(
          Kind.READ,
          Spotter.Outcome.OUTCOME_TIMED_OUT,
          "timed out after " + PackReader.show(timeout));
    } catch (IOException e) {
      return Result.failed(
          Kind.READ, Spotter.Outcome.OUTCOME_COULD_NOT_START, read.path() + ": " + e.getMessage());
    } finally {
      synchronized (lock) {
        done[0] = true;
      }
      deadline.cancel(false);
      if (timedOut.get()) {
        // The interrupt was this read's own: the thread runs on.
        Thread.interrupted();
      }
    }
  }

  // ---- helpers ----

  /**
   * Copies at most {@code max} bytes; with {@code drain}, reads the rest and drops it, so a process
   * never blocks writing. Whether there was more than was kept.
   */
  private static boolean copy(InputStream in, ByteArrayOutputStream kept, int max, boolean drain)
      throws IOException {
    byte[] chunk = new byte[8192];
    boolean truncated = false;
    int read;
    while ((read = in.read(chunk)) != -1) {
      int room = max - kept.size();
      if (read > room) {
        kept.write(chunk, 0, Math.max(0, room));
        truncated = true;
        if (!drain) {
          break;
        }
      } else {
        kept.write(chunk, 0, read);
      }
    }
    return truncated;
  }

  private static File nullDevice() {
    return new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
  }

  private static java.util.concurrent.ThreadFactory daemon(String name) {
    return work -> {
      Thread thread = new Thread(work, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  @Override
  public void close() {
    timer.shutdownNow();
    readers.shutdownNow();
  }
}
