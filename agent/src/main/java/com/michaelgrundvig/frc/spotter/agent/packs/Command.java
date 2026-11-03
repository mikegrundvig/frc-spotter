package com.michaelgrundvig.frc.spotter.agent.packs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a pack's collector, log or action runs, fixed in its pack: never anything a request says.
 * Three kinds, the same for all three uses, but {@link Read}, which only a collector may use.
 */
public sealed interface Command permits Command.Run, Command.Http, Command.Read {
  /**
   * The kinds of command: each one's key in a pack, and the names its result's code and whole
   * output go by, which a pack's fields read and an action's response declares.
   */
  enum Kind {
    RUN("run", EXIT, OUTPUT),
    HTTP("http", STATUS, BODY),
    READ("file", "", "");

    private final String key;
    private final String code;
    private final String whole;

    Kind(String key, String code, String whole) {
      this.key = key;
      this.code = code;
      this.whole = whole;
    }

    /** Its key in a pack: {@code run:}, {@code http:} or {@code file:}. */
    public String key() {
      return key;
    }

    /**
     * The name of its result's code: a process's exit code, a response's status; empty for none.
     */
    public String code() {
      return code;
    }

    /** The name of its whole output: standard output, a response's body; empty for none. */
    public String whole() {
      return whole;
    }
  }

  /** What a named part of a command's result is. */
  enum Part {
    OUTCOME,
    OUTCOME_MESSAGE,
    CODE,
    WHOLE
  }

  /** The named parts every command fills: how it went, and why. */
  String OUTCOME = "outcome";

  String OUTCOME_MESSAGE = "outcomeMessage";

  /** A {@code run} command's named parts: its exit code, and its standard output. */
  String EXIT = "exit";

  String OUTPUT = "output";

  /** An {@code http} request's named parts: its status, and its body. */
  String STATUS = "status";

  String BODY = "body";

  /** The named parts of a kind of command, by name, in their order. */
  static Map<String, Part> parts(Kind kind) {
    Map<String, Part> parts = new LinkedHashMap<>();
    parts.put(OUTCOME, Part.OUTCOME);
    parts.put(OUTCOME_MESSAGE, Part.OUTCOME_MESSAGE);
    if (!kind.code().isEmpty()) {
      parts.put(kind.code(), Part.CODE);
    }
    if (!kind.whole().isEmpty()) {
      parts.put(kind.whole(), Part.WHOLE);
    }
    return parts;
  }

  /**
   * The programs it names by path, on the coprocessor, which only root may change if an installed
   * pack is to run it: a {@link Run}'s; none for a request or a file read.
   */
  default List<String> programsByPath(String folder) {
    return List.of();
  }

  /** Its kind. */
  default Kind kind() {
    if (this instanceof Run) {
      return Kind.RUN;
    }
    return this instanceof Http ? Kind.HTTP : Kind.READ;
  }

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
    public String program() {
      return argv.get(0);
    }

    /**
     * Whether the pack names its program by a path (its own {@code ./name}, or an absolute one).
     */
    public boolean byPath() {
      return program().startsWith("./") || program().startsWith("/");
    }

    /** Where its program is, on the coprocessor: in the pack's folder, or as written. */
    public String programPath(String folder) {
      return program().startsWith("./") ? folder + "/" + program().substring(2) : program();
    }

    /**
     * The programs it names by path, on the coprocessor, which only root may change if an installed
     * pack is to run it: its program when named by a path, and any argument that's a file of the
     * pack's own ({@code [python3, ./check.py]}). An absolute path past the program is left alone:
     * it's usually data, such as a file to hash.
     */
    public List<String> programsByPath(String folder) {
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
  public record Http(String method, String url, String form, String filename) implements Command {
    /** A request whose form, if any, names its file after its field. */
    public Http(String method, String url, String form) {
      this(method, url, form, "");
    }

    /** The file name the form's field says it sends: its own, unless the pack names one. */
    public String file() {
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
