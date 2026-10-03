package com.michaelgrundvig.frc.spotter.agent;

import java.util.List;

/**
 * What a pack's collector, log or action runs, fixed in its pack: never anything a request says.
 * Three kinds, the same for all three uses, but {@link Read}, which only a collector may use.
 */
sealed interface Command permits Command.Run, Command.Http, Command.Read {
  /**
   * A fixed command line, run as the agent's user with no shell unless it names one, from its
   * pack's folder: a program written {@code ./name} is the pack's own, in that folder.
   *
   * @param argv the program and its arguments, as the pack writes them
   */
  record Run(List<String> argv) implements Command {
    public Run {
      argv = List.copyOf(argv);
    }

    /** The program, as the pack writes it. */
    String program() {
      return argv.get(0);
    }

    /**
     * Whether the pack names its program by a path (its own {@code ./name}, or an absolute one).
     */
    boolean byPath() {
      return program().startsWith("./") || program().startsWith("/");
    }

    /** Where its program is, on the coprocessor: in the pack's folder, or as written. */
    String programPath(String folder) {
      return program().startsWith("./") ? folder + "/" + program().substring(2) : program();
    }
  }

  /**
   * A request to the board itself ({@code localhost} only).
   *
   * @param method {@code GET} or {@code POST}
   * @param url the URL: {@code http://localhost...}
   * @param form the form field an action's input is sent as (multipart); empty to send it as the
   *     body
   */
  record Http(String method, String url, String form) implements Command {}

  /**
   * Reading a file: a collector's only.
   *
   * @param path the file, absolute
   */
  record Read(String path) implements Command {}
}
