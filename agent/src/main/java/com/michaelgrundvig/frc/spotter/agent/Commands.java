package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
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
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a pack's commands: the only place the agent starts a process, asks a URL, or reads a pack's
 * file. Each is bounded: by its timeout (a process and its children are killed, a request is cut
 * off, a read is interrupted), and by the most of its output kept. Nothing here comes from a
 * request: every program, URL and path is its pack's, fixed when the agent started.
 */
final class Commands implements Runner, AutoCloseable {
  /** The most of a command's standard error kept to say why it failed: its first line. */
  static final int MAX_ERRORS = 200;

  /** How long the end of a command's standard error is waited for, once it has exited. */
  private static final long ERRORS_WAIT_MILLIS = 200;

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

  @Override
  public Result run(String folder, Command command, Duration timeout, int maxOutput) {
    if (command instanceof Command.Run) {
      return process(folder, (Command.Run) command, timeout, maxOutput);
    }
    if (command instanceof Command.Http) {
      return http((Command.Http) command, timeout, maxOutput);
    }
    return read((Command.Read) command, timeout, maxOutput);
  }

  // ---- run ----

  private Result process(String folder, Command.Run run, Duration timeout, int maxOutput) {
    List<String> argv = new ArrayList<>(run.argv());
    String program = run.programPath(folder);
    if (run.byPath()) {
      argv.set(0, host.path(program).toString());
    }
    Process process;
    try {
      process =
          new ProcessBuilder(argv)
              .directory(host.path(folder).toFile())
              .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
              .start();
    } catch (IOException e) {
      return Result.failed(Kind.RUN, Spotter.Outcome.OUTCOME_COULD_NOT_START, why(e, program));
    }
    Future<String> errors = errors(process);
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              timedOut.set(true);
              kill(process);
            },
            Math.max(1, timeout.toMillis()),
            TimeUnit.MILLISECONDS);
    ByteArrayOutputStream kept = new ByteArrayOutputStream();
    boolean truncated = false;
    try (InputStream out = process.getInputStream()) {
      truncated = copy(out, kept, maxOutput, true);
    } catch (IOException e) {
      // Killed as it was read: it timed out, said below.
    }
    try {
      if (!process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
        timedOut.set(true);
        kill(process);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      kill(process);
      timedOut.set(true);
    } finally {
      deadline.cancel(false);
    }
    String said = errorsOf(errors);
    if (timedOut.get()) {
      return new Result(
          Kind.RUN,
          Spotter.Outcome.OUTCOME_TIMED_OUT,
          "timed out after " + PackReader.show(timeout),
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
   * {@code error=2,} and later Javas {@code error: 2}.
   */
  private static String why(IOException e, String program) {
    Matcher errno = ERRNO.matcher(String.valueOf(e.getMessage()));
    if (errno.find()) {
      if (errno.group(1).equals("2")) {
        return "no such file: " + program;
      }
      if (errno.group(1).equals("13")) {
        return "not allowed: " + program;
      }
    }
    return String.valueOf(e.getMessage());
  }

  /** Kills a process and everything it started. */
  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  /**
   * Reads a process's standard error until it closes: its first line kept, cut to {@link
   * #MAX_ERRORS} characters, and the rest read and dropped, so the process never blocks writing it.
   */
  private Future<String> errors(Process process) {
    return errorReaders.submit(
        () -> {
          StringBuilder first = new StringBuilder();
          boolean ended = false;
          try (Reader reader =
              new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
            int c;
            while ((c = reader.read()) != -1) {
              if (ended) {
                continue;
              }
              if (c == '\n') {
                ended = first.toString().strip().length() > 0;
              } else if (first.length() < MAX_ERRORS) {
                first.append((char) c);
              }
            }
          }
          return first.toString().strip();
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

  private Result http(Command.Http http, Duration timeout, int maxOutput) {
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
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              timedOut.set(true);
              connection.disconnect();
            },
            millis,
            TimeUnit.MILLISECONDS);
    String where = where(http.url());
    try {
      connection.setRequestMethod(http.method());
      if (http.method().equals("POST")) {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(0);
        try (OutputStream body = connection.getOutputStream()) {
          body.flush();
        }
      }
      int status = connection.getResponseCode();
      ByteArrayOutputStream kept = new ByteArrayOutputStream();
      boolean truncated = false;
      InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      if (in != null) {
        try (in) {
          truncated = copy(in, kept, maxOutput, false);
        }
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
      return Result.failed(
          Kind.HTTP,
          Spotter.Outcome.OUTCOME_TIMED_OUT,
          "no answer within " + PackReader.show(timeout) + " (" + where + ")");
    } catch (ConnectException e) {
      return Result.failed(
          Kind.HTTP, Spotter.Outcome.OUTCOME_UNREACHABLE, "connection refused (" + where + ")");
    } catch (NoRouteToHostException | UnknownHostException e) {
      return Result.failed(
          Kind.HTTP, Spotter.Outcome.OUTCOME_UNREACHABLE, "no route (" + where + ")");
    } catch (IOException e) {
      if (timedOut.get()) {
        return Result.failed(
            Kind.HTTP,
            Spotter.Outcome.OUTCOME_TIMED_OUT,
            "no answer within " + PackReader.show(timeout) + " (" + where + ")");
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
