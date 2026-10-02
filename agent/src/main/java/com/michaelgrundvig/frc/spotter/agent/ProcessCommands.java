package com.michaelgrundvig.frc.spotter.agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs commands as processes: the only place the agent starts one. What a command prints is read as
 * it's printed and the process is stopped once the bounds are reached, so a journal of any size
 * costs no more than a page; one timer thread stops a process that overruns its time.
 */
final class ProcessCommands implements Commands, AutoCloseable {
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "coprocessor-agent-timer");
            thread.setDaemon(true);
            return thread;
          });

  @Override
  public Output run(List<String> command, Duration timeout, int maxLines, int maxBytes)
      throws IOException {
    Process process =
        new ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledFuture<?> deadline =
        timer.schedule(
            () -> {
              timedOut.set(true);
              process.destroyForcibly();
            },
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
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
    return new Output(exit, lines, truncated, timedOut.get());
  }

  private static java.io.File nullDevice() {
    return new java.io.File(
        System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
  }

  @Override
  public void close() {
    timer.shutdownNow();
  }
}
