package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Runs commands as processes: the only place the agent starts one. What a command prints is read as
 * it's printed and the process is stopped once the bounds are reached, so a journal of any size
 * costs no more than a page; one timer thread stops a process that overruns its time. Of its
 * standard error, the first line is kept (why it failed) and the rest read and dropped. Each runs
 * with {@link #JAVA_VARIABLE} naming the agent's own Java.
 */
final class ProcessCommands implements Commands, AutoCloseable {
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "coprocessor-agent-timer");
            thread.setDaemon(true);
            return thread;
          });

  /** Reads commands' standard error, a thread each while they run. */
  private final ExecutorService errorReaders =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "coprocessor-agent-stderr");
            thread.setDaemon(true);
            return thread;
          });

  /** How long the end of a command's standard error is waited for, once it has exited. */
  private static final long ERRORS_WAIT_MILLIS = 200;

  /** The Java this agent runs on, which its commands' helpers run on too. */
  private final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();

  /** A command's process: its input empty, the agent's Java named. */
  private Process start(List<String> command) throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
    builder.environment().put(JAVA_VARIABLE, java);
    return builder.start();
  }

  /**
   * Reads a process's standard error until it closes: its first line kept, cut to {@link
   * Commands.Output#MAX_ERRORS} characters, and the rest read and dropped, so the process never
   * blocks writing it.
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
              } else if (first.length() < Commands.Output.MAX_ERRORS) {
                first.append((char) c);
              }
            }
          }
          return first.toString().strip();
        });
  }

  /** Stops the process when the time is up, saying so. */
  private ScheduledFuture<?> deadline(Process process, Duration timeout, AtomicBoolean timedOut) {
    return timer.schedule(
        () -> {
          timedOut.set(true);
          process.destroyForcibly();
        },
        timeout.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public Output toFile(List<String> command, Duration timeout, long maxBytes, Path file)
      throws IOException {
    Process process = start(command);
    Future<String> errors = errors(process);
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> deadline = deadline(process, timeout, timedOut);
    boolean truncated = false;
    try (InputStream in = process.getInputStream();
        OutputStream out = Files.newOutputStream(file)) {
      byte[] chunk = new byte[8192];
      long written = 0;
      int read;
      while ((read = in.read(chunk)) != -1) {
        if (written + read > maxBytes) {
          truncated = true;
          process.destroyForcibly();
          break;
        }
        out.write(chunk, 0, read);
        written += read;
      }
    }
    return finish(process, timeout, timedOut, deadline, List.of(), truncated, errors);
  }

  @Override
  public Output run(List<String> command, Duration timeout, int maxLines, int maxBytes)
      throws IOException {
    Process process = start(command);
    Future<String> errors = errors(process);
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> deadline = deadline(process, timeout, timedOut);
    List<String> lines = new ArrayList<>();
    boolean truncated = false;
    try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      StringBuilder line = new StringBuilder();
      int bytes = 0;
      int c;
      while ((c = reader.read()) != -1) {
        if (++bytes > maxBytes) {
          truncated = true;
          break;
        }
        if (c == '\n') {
          lines.add(line.toString());
          line.setLength(0);
          if (lines.size() >= maxLines) {
            truncated = reader.read() != -1;
            break;
          }
        } else {
          line.append((char) c);
        }
      }
      if (!truncated && !line.isEmpty()) {
        lines.add(line.toString());
      }
    } finally {
      if (truncated) {
        process.destroyForcibly();
      }
    }
    return finish(process, timeout, timedOut, deadline, lines, truncated, errors);
  }

  /** Waits for the process to end (or stops it), and says how it went. */
  private static Output finish(
      Process process,
      Duration timeout,
      AtomicBoolean timedOut,
      ScheduledFuture<?> deadline,
      List<String> lines,
      boolean truncated,
      Future<String> errors) {
    try {
      boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        timedOut.set(true);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      timedOut.set(true);
    } finally {
      deadline.cancel(false);
    }
    int exit = truncated || timedOut.get() ? -1 : process.exitValue();
    return new Output(exit, lines, truncated, timedOut.get(), errorsOf(errors));
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

  private static java.io.File nullDevice() {
    return new java.io.File(
        System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
  }

  @Override
  public void close() {
    timer.shutdownNow();
    errorReaders.shutdownNow();
  }
}
