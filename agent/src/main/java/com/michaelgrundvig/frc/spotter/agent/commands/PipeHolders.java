package com.michaelgrundvig.frc.spotter.agent.commands;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * What holds a command's output pipes open once it has exited, found as {@code fuser} finds it: by
 * the pipes' names ({@code pipe:[33352998]}) among each process's open files in Linux's {@code
 * /proc}. A program that started a session of its own is outside the command's group, and no
 * descendant of it once the command has exited, so only this finds it. A reader blocked on a pipe
 * isn't freed by closing the agent's end of it: only by its last writer closing it, or going.
 * Elsewhere than Linux, or where {@code /proc} can't be read, nothing is found.
 */
final class PipeHolders {
  private static final Path PROC = Path.of("/proc");

  /** A process's own open files, which only another process of the agent's user can read. */
  private static final String FD = "fd";

  private PipeHolders() {}

  /**
   * The pipes a process writes its output and its standard error to, read as it starts: once it has
   * exited, they can no longer be read. None when it's gone already.
   */
  static Set<String> of(long pid) {
    Set<String> pipes = new HashSet<>();
    for (String fd : new String[] {"1", "2"}) {
      try {
        String target =
            Files.readSymbolicLink(PROC.resolve(Long.toString(pid)).resolve(FD).resolve(fd))
                .toString();
        if (target.startsWith("pipe:")) {
          pipes.add(target);
        }
      } catch (IOException | UnsupportedOperationException e) {
        // It's gone already, or this isn't Linux: nothing to find later.
      }
    }
    return pipes;
  }

  /**
   * Kills every process but the agent that holds one of these pipes: what a command left running,
   * outside its group, holding its output.
   */
  static void kill(Set<String> pipes) {
    if (pipes.isEmpty()) {
      return;
    }
    long agent = ProcessHandle.current().pid();
    try (DirectoryStream<Path> processes = Files.newDirectoryStream(PROC, "[0-9]*")) {
      for (Path process : processes) {
        long pid = pid(process);
        if (pid > 0 && pid != agent && holds(process, pipes)) {
          ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
      }
    } catch (IOException e) {
      // No /proc to read: what holds the pipes is left, and its readers with it.
    }
  }

  /** Whether a process has one of these pipes open. */
  private static boolean holds(Path process, Set<String> pipes) {
    try (DirectoryStream<Path> fds = Files.newDirectoryStream(process.resolve(FD))) {
      for (Path fd : fds) {
        if (pipes.contains(link(fd))) {
          return true;
        }
      }
    } catch (IOException | DirectoryIteratorException e) {
      // Another user's, or gone as it was read: not one of the command's.
    }
    return false;
  }

  /** A process's number, from its folder's name; 0 for a folder that isn't a process's. */
  private static long pid(Path process) {
    try {
      return Long.parseLong(process.getFileName().toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static String link(Path fd) {
    try {
      return Files.readSymbolicLink(fd).toString();
    } catch (IOException e) {
      return "";
    }
  }
}
