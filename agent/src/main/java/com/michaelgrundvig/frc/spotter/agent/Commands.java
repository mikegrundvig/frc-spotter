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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Runs a pack's commands: the only place the agent starts a process, asks a URL, or reads a pack's
 * file. Each is bounded: by its timeout (a process and its children are stopped, a request is cut
 * off, a read is interrupted), and by the most of its output kept. Nothing here comes from a
 * request: every program, URL and path is its pack's, fixed when the agent started. What a request
 * brings (an action's input, a log's paging) is data: on standard input, in a request's body, or in
 * the environment, never on a command line.
 */
final class Commands implements Runner, AutoCloseable {
  /** The most of a command's standard error kept to say why it failed: its first line. */
  static final int MAX_ERRORS = 200;

  /** The longest line of standard error handed over whole; a longer one is cut. */
  static final int MAX_LINE = 8 * 1024;

  /** How long the end of a command's standard error is waited for, once it has exited. */
  private static final long ERRORS_WAIT_MILLIS = 1000;

  /** The errno in why a program couldn't start. */
  private static final Pattern ERRNO = Pattern.compile("error(?:=|: )([0-9]+)");

  private final Host host;

  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(daemon("spotter-timer"));

  /** Reads commands' standard error, a thread each while they run. */
  private final ExecutorService errorReaders =
      Executors.newCachedThreadPool(daemon("spotter-stderr"));

  Commands(Host host) {
    this.host = host;
  }

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
   */
  record Result(
      Kind kind,
      Spotter.Outcome outcome,
      String message,
      int code,
      byte[] output,
      boolean truncated,
      String errors) {
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
    if (run.byPath()) {
      argv.set(0, host.path(program).toString());
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
    }
    Future<String> errors = errors(process, options);
    AtomicReference<Spotter.Outcome> stopped = new AtomicReference<>();
    Runnable stop = () -> stop(process, options.grace());
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              if (stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_TIMED_OUT)) {
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
    ByteArrayOutputStream kept = new ByteArrayOutputStream();
    boolean truncated = false;
    try (InputStream out = process.getInputStream()) {
      truncated = copy(out, kept, options.maxOutput(), true);
    } catch (IOException e) {
      // Stopped as it was read: said below.
    }
    try {
      long most = timeout.plus(options.grace()).toMillis() + 1000;
      if (!process.waitFor(most, TimeUnit.MILLISECONDS)) {
        stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_TIMED_OUT);
        kill(process);
        process.waitFor(1, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stopped.compareAndSet(null, Spotter.Outcome.OUTCOME_CANCELLED);
      kill(process);
    } finally {
      deadline.cancel(false);
    }
    String said = errorsOf(errors);
    Spotter.Outcome outcome = stopped.get();
    if (outcome != null) {
      return new Result(
          Kind.RUN,
          outcome,
          outcome == Spotter.Outcome.OUTCOME_TIMED_OUT
              ? "timed out after " + PackReader.show(timeout)
              : "cancelled",
          0,
          kept.toByteArray(),
          truncated,
          said);
    }
    return new Result(
        Kind.RUN,
        Spotter.Outcome.OUTCOME_COMPLETED,
        "",
        process.exitValue(),
        kept.toByteArray(),
        truncated,
        said);
  }

  /**
   * Why a program couldn't start, as a person would say it: from its errno, which Java 17 writes
   * {@code error=2,} and later Javas {@code error: 2}. Not allowed (EACCES) is nearly always a
   * script without its execute bit, as a copy that drops modes leaves it: the fix is said.
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
        return "not executable ("
            + program
            + "): chmod +x, or name its interpreter: run: [sh, "
            + written
            + "]";
      }
    }
    return String.valueOf(e.getMessage());
  }

  /**
   * Stops a process and everything it started: politely (SIGTERM), then, after its grace, forcibly
   * (SIGKILL); at once with no grace.
   */
  private void stop(Process process, Duration grace) {
    if (grace.isZero()) {
      kill(process);
      return;
    }
    // Through its handle, not Process.destroy, which closes its streams: what it says as it stops
    // is still read, into its log.
    process.descendants().forEach(ProcessHandle::destroy);
    process.toHandle().destroy();
    timer.schedule(() -> kill(process), grace.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Kills a process and everything it started. */
  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /**
   * Reads a process's standard error until it closes, so the process never blocks writing it: each
   * line handed over (at most {@code maxErrors} bytes of them, each cut to {@link #MAX_LINE}), and
   * the first that isn't blank kept, cut to {@link #MAX_ERRORS} characters, to say why it failed.
   */
  private Future<String> errors(Process process, Options options) {
    return errorReaders.submit(
        () -> {
          String first = "";
          long handed = 0;
          ByteArrayOutputStream line = new ByteArrayOutputStream();
          try (InputStream in = process.getErrorStream()) {
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
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (first.isEmpty() && !text.isBlank()) {
                  first = text.strip();
                  first = first.length() <= MAX_ERRORS ? first : first.substring(0, MAX_ERRORS);
                }
                if (handed + text.length() + 1 <= options.maxErrors()) {
                  handed += text.length() + 1;
                  options.errors().accept(text);
                }
              }
            }
          }
          String rest = line.toString(StandardCharsets.UTF_8);
          if (!rest.isEmpty()) {
            if (first.isEmpty() && !rest.isBlank()) {
              first = rest.strip();
              first = first.length() <= MAX_ERRORS ? first : first.substring(0, MAX_ERRORS);
            }
            if (handed + rest.length() <= options.maxErrors()) {
              options.errors().accept(rest);
            }
          }
          return first;
        });
  }

  /**
   * The first line of standard error, once the process has exited: waited for briefly, as a child
   * it left running may hold the stream open.
   */
  private static String errorsOf(Future<String> errors) {
    try {
      return errors.get(ERRORS_WAIT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      // Not readable, or still open: no reason given.
    }
    errors.cancel(true);
    return "";
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
    errorReaders.shutdownNow();
  }
}
