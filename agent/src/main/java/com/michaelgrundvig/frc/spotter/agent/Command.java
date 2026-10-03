package com.michaelgrundvig.frc.spotter.agent;

import java.util.ArrayList;
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

    /**
     * The programs it names by path, on the coprocessor, which only root may change if an installed
     * pack is to run it: its program when named by a path, and any argument that's a file of the
     * pack's own ({@code [python3, ./check.py]}). An absolute path past the program is left alone:
     * it's usually data, such as a file to hash.
     */
    List<String> programsByPath(String folder) {
      List<String> named = new ArrayList<>();
      if (byPath()) {
        named.add(programPath(folder));
      }
      for (String argument : argv.subList(1, argv.size())) {
        if (argument.startsWith("./")) {
          named.add(folder + "/" + argument.substring(2));
        }
      }
      return named;
    }
  }

  /**
   * A request to the board itself ({@code localhost} only).
   *
   * @param method {@code GET} or {@code POST}
   * @param url the URL: {@code http://localhost...}
   * @param form the form field an action's input is sent as (multipart); empty to send it as the
   *     body
   * @param filename the file name the form's field says it sends; empty for the field's own name
   */
  record Http(String method, String url, String form, String filename) implements Command {
    /** A request whose form, if any, names its file after its field. */
    Http(String method, String url, String form) {
      this(method, url, form, "");
    }

    /** The file name the form's field says it sends: its own, unless the pack names one. */
    String file() {
      return filename.isEmpty() ? form : filename;
    }
  }

  /**
   * Reading a file: a collector's only.
   *
   * @param path the file, absolute
   */
  record Read(String path) implements Command {}
}
