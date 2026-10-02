package com.michaelgrundvig.frc.spotter.probes;

import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a probe checks, by kind, with the parameters the image fixes. Every value is checked when a
 * check is made, however it's made, so a definition the agent loads can't name a shell, a path
 * outside the filesystem's root, or a URL off this computer.
 */
public sealed interface Check {
  /** Its kind. */
  ProbeKind kind();

  /** Its parameters, as definitions write them. */
  JsonValue.Obj toJson();

  /** The most arguments a program is given. */
  int MAX_ARGS = 32;

  /** The longest argument, path, URL, or pattern. */
  int MAX_TEXT = 1024;

  /**
   * Programs a probe or step may not run: shells, and programs that run another or change who runs
   * it. The image fixes every argument, so a shell would only hide what runs; one that runs another
   * program would hide which.
   */
  Set<String> NOT_RUN =
      Set.of(
          "sh",
          "bash",
          "dash",
          "zsh",
          "ksh",
          "ash",
          "fish",
          "csh",
          "tcsh",
          "busybox",
          "env",
          "sudo",
          "su",
          "doas",
          "pkexec",
          "runuser",
          "setpriv",
          "xargs",
          "nohup",
          "systemd-run");

  /** A plain program name, found on the PATH. */
  Pattern PROGRAM = Pattern.compile("[A-Za-z0-9._+-]{1,128}");

  /** A check from its kind and parameters. */
  static Check fromJson(ProbeKind kind, JsonValue.Obj o) {
    return switch (kind) {
      case COMMAND -> new Command(o.strings("argv"), o.integer("exit", 0), o.string("match", ""));
      case HTTP ->
          new Http(
              o.string("url", ""),
              o.integer("status", 200),
              o.string("field", ""),
              o.string("equals", ""));
      case FILE ->
          new File(
              o.string("path", ""),
              FileTest.byId(o.string("test", "exists")),
              o.integer("minBytes", -1L),
              o.integer("maxBytes", -1L),
              o.string("sha256", ""),
              o.string("field", ""),
              o.string("equals", ""),
              o.string("match", ""));
      case UNIT -> new Unit(o.string("unit", ""), o.string("state", "active"));
      case USB -> new Usb(o.string("path", ""), o.number("minSpeedMbps", 0));
      case THRESHOLD ->
          new Threshold(
              o.string("metric", ""), o.number("min", Double.NaN), o.number("max", Double.NaN));
    };
  }

  /**
   * Runs a program directly, never through a shell, as the agent's user, with its arguments fixed.
   *
   * @param argv the program and its arguments; the program is an absolute path or a name found on
   *     the PATH
   * @param exit the exit status it passes with; {@link #ANY_EXIT} for any (then only {@code match}
   *     decides)
   * @param match a regular expression found in what it prints (its lines joined by newlines); empty
   *     for none. Its first group, or else the whole match, is the probe's value; without a pattern
   *     the value is its first line
   */
  record Command(List<String> argv, int exit, String match) implements Check {
    /** Any exit status passes. */
    public static final int ANY_EXIT = -1;

    public Command {
      argv = List.copyOf(argv);
      checkArgv(argv, "argv");
      if (exit < ANY_EXIT || exit > 255) {
        throw new IllegalArgumentException(
            "exit " + exit + " isn't an exit status (0 to 255, or -1 for any)");
      }
      checkPattern(match, "match");
    }

    @Override
    public ProbeKind kind() {
      return ProbeKind.COMMAND;
    }

    @Override
    public JsonValue.Obj toJson() {
      return JsonValue.Obj.builder()
          .put("argv", argv)
          .put("exit", exit)
          .put("match", match)
          .build();
    }
  }

  /**
   * GETs a page on this computer: {@code http://localhost}, {@code 127.0.0.1}, or {@code [::1]}
   * only, so a probe can't reach another computer, and plain HTTP (it's this computer).
   *
   * @param url the page
   * @param status the status it passes with
   * @param field a member of the JSON answer to report, by its names joined with dots ({@code
   *     general.version}); empty to report the status
   * @param equals what that member must be, as text; empty to only report it
   */
  record Http(String url, int status, String field, String equals) implements Check {
    public Http {
      checkLocalUrl(url);
      if (status < 100 || status > 599) {
        throw new IllegalArgumentException("status " + status + " isn't an HTTP status");
      }
      checkField(field);
      checkText(equals, "equals");
      if (!equals.isEmpty() && field.isEmpty()) {
        throw new IllegalArgumentException("equals needs a field to compare");
      }
    }

    @Override
    public ProbeKind kind() {
      return ProbeKind.HTTP;
    }

    @Override
    public JsonValue.Obj toJson() {
      return JsonValue.Obj.builder()
          .put("url", url)
          .put("status", status)
          .put("field", field)
          .put("equals", equals)
          .build();
    }
  }

  /** What a {@link File} check reads of its file. */
  enum FileTest {
    /** Whether it's there: its size is the value. */
    EXISTS,
    /** Its size in bytes, within {@code minBytes} and {@code maxBytes}. */
    SIZE,
    /** Its SHA-256, equal to {@code sha256} when that's given. */
    SHA256,
    /** A member of it, read as JSON ({@code field}), equal to {@code equals} when that's given. */
    JSON,
    /** A regular expression found in it, read as text ({@code match}). */
    TEXT;

    /** Its name as definitions write it. */
    public String id() {
      return name().toLowerCase(Locale.ROOT);
    }

    static FileTest byId(String id) {
      for (FileTest test : values()) {
        if (test.id().equals(id)) {
          return test;
        }
      }
      throw new IllegalArgumentException(
          "test \"" + id + "\" isn't exists, size, sha256, json, or text");
    }
  }

  /**
   * Reads a file: whether it's there, its size, its SHA-256, or a JSON member or pattern in it.
   *
   * @param path the file, an absolute path
   * @param test what's read of it
   * @param minBytes for {@code size}: the least it may be; -1 for no least
   * @param maxBytes for {@code size}: the most it may be; -1 for no most
   * @param sha256 for {@code sha256}: what it must be, lowercase hex; empty to only report it
   * @param field for {@code json}: the member, by its names joined with dots
   * @param equals for {@code json}: what the member must be, as text; empty to only report it
   * @param match for {@code text}: a regular expression found in it; its first group (or the whole
   *     match) is the value
   */
  record File(
      String path,
      FileTest test,
      long minBytes,
      long maxBytes,
      String sha256,
      String field,
      String equals,
      String match)
      implements Check {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public File {
      checkPath(path, "path");
      checkField(field);
      checkText(equals, "equals");
      checkPattern(match, "match");
      if (!sha256.isEmpty() && !SHA256.matcher(sha256).matches()) {
        throw new IllegalArgumentException("sha256 must be 64 lowercase hex digits");
      }
      switch (test) {
        case JSON -> {
          if (field.isEmpty()) {
            throw new IllegalArgumentException("test json needs a field");
          }
        }
        case TEXT -> {
          if (match.isEmpty()) {
            throw new IllegalArgumentException("test text needs a match");
          }
        }
        case SIZE -> {
          if (minBytes < -1 || maxBytes < -1 || (maxBytes >= 0 && minBytes > maxBytes)) {
            throw new IllegalArgumentException(
                "minBytes and maxBytes: -1 for none, or 0 and up, least first");
          }
        }
        default -> {}
      }
    }

    @Override
    public ProbeKind kind() {
      return ProbeKind.FILE;
    }

    @Override
    public JsonValue.Obj toJson() {
      return JsonValue.Obj.builder()
          .put("path", path)
          .put("test", test.id())
          .put("minBytes", minBytes)
          .put("maxBytes", maxBytes)
          .put("sha256", sha256)
          .put("field", field)
          .put("equals", equals)
          .put("match", match)
          .build();
    }
  }

  /**
   * A systemd unit's state, as {@code systemctl show} reads it.
   *
   * @param unit the unit, such as {@code photonvision.service}
   * @param state the active state it passes in: {@code active} unless the definition says
   */
  record Unit(String unit, String state) implements Check {
    private static final Pattern UNIT =
        Pattern.compile(
            "[A-Za-z0-9:_.\\\\@-]{1,200}\\.(service|socket|timer|mount|target|path|device|scope|slice)");
    private static final Set<String> STATES =
        Set.of("active", "inactive", "failed", "activating", "deactivating", "reloading");

    public Unit {
      if (!UNIT.matcher(unit).matches()) {
        throw new IllegalArgumentException("unit \"" + unit + "\" isn't a systemd unit's name");
      }
      if (!STATES.contains(state)) {
        throw new IllegalArgumentException("state \"" + state + "\" isn't one of " + STATES);
      }
    }

    @Override
    public ProbeKind kind() {
      return ProbeKind.UNIT;
    }

    @Override
    public JsonValue.Obj toJson() {
      return JsonValue.Obj.builder().put("unit", unit).put("state", state).build();
    }
  }

  /**
   * A device at its stable path ({@code /dev/v4l/by-path/...}, {@code /dev/serial/by-path/...}),
   * which names the port it's plugged into, so a device in the wrong port fails; and its USB link
   * speed, so a camera that fell back to USB 2 (or 1) shows.
   *
   * @param path its by-path name
   * @param minSpeedMbps the slowest link it passes at, in Mb/s: 480 for USB 2, 5000 for USB 3; 0
   *     for any
   */
  record Usb(String path, double minSpeedMbps) implements Check {
    public Usb {
      checkPath(path, "path");
      if (!path.startsWith("/dev/") || !path.contains("/by-path/")) {
        throw new IllegalArgumentException("path " + path + " isn't a /dev/.../by-path/ name");
      }
      if (!(minSpeedMbps >= 0)) {
        throw new IllegalArgumentException("minSpeedMbps must be 0 or more");
      }
    }

    @Override
    public ProbeKind kind() {
      return ProbeKind.USB;
    }

    @Override
    public JsonValue.Obj toJson() {
      return JsonValue.Obj.builder().put("path", path).put("minSpeedMbps", minSpeedMbps).build();
    }
  }

  /**
   * One of the agent's own measurements ({@link Metrics}) against limits.
   *
   * @param metric its name
   * @param min the least it passes at; NaN for none
   * @param max the most it passes at; NaN for none
   */
  record Threshold(String metric, double min, double max) implements Check {
    public Threshold {
      if (!Metrics.exists(metric)) {
        throw new IllegalArgumentException(
            "metric \"" + metric + "\" isn't one of " + String.join(", ", Metrics.NAMES.keySet()));
      }
      if (!Double.isNaN(min) && !Double.isNaN(max) && min > max) {
        throw new IllegalArgumentException("min is more than max");
      }
    }

    @Override
    public ProbeKind kind() {
      return ProbeKind.THRESHOLD;
    }

    @Override
    public JsonValue.Obj toJson() {
      return JsonValue.Obj.builder()
          .put("metric", metric)
          .put("min", JsonValue.of(min))
          .put("max", JsonValue.of(max))
          .build();
    }
  }

  // ---- What every check's values are checked against ----

  /** Checks a program and its arguments: no shell, nothing empty, nothing unbounded. */
  static void checkArgv(List<String> argv, String what) {
    if (argv.isEmpty()) {
      throw new IllegalArgumentException(what + " is empty: name a program");
    }
    if (argv.size() > MAX_ARGS) {
      throw new IllegalArgumentException(what + " has more than " + MAX_ARGS + " arguments");
    }
    for (String arg : argv) {
      checkText(arg, what);
    }
    String program = argv.get(0);
    if (program.startsWith("/")) {
      checkPath(program, what + "'s program");
    } else if (!PROGRAM.matcher(program).matches()) {
      throw new IllegalArgumentException(
          what + "'s program \"" + program + "\" is neither an absolute path nor a plain name");
    }
    String name = program.substring(program.lastIndexOf('/') + 1);
    if (NOT_RUN.contains(name) || name.startsWith("python") || name.startsWith("perl")) {
      throw new IllegalArgumentException(
          what
              + " runs "
              + name
              + ": a probe runs one program directly, never a shell or a program that runs another");
    }
  }

  /** Checks an absolute path: normalized, no {@code ..}, not too long. */
  static void checkPath(String path, String what) {
    checkText(path, what);
    if (!path.startsWith("/")) {
      throw new IllegalArgumentException(what + " " + path + " isn't an absolute path");
    }
    if (!Path.of(path).normalize().toString().equals(path) || path.contains("/../")) {
      throw new IllegalArgumentException(what + " " + path + " isn't written plainly (no . or ..)");
    }
  }

  /** Checks a URL is on this computer, over plain HTTP. */
  static void checkLocalUrl(String url) {
    checkText(url, "url");
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("url " + url + " isn't a URL");
    }
    String host = uri.getHost();
    if (!"http".equals(uri.getScheme())
        || host == null
        || !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"))
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "url " + url + " isn't on this computer: http://localhost, 127.0.0.1, or [::1] only");
    }
  }

  /** Checks a field's path: names joined with dots. */
  static void checkField(String field) {
    checkText(field, "field");
    if (!field.isEmpty()
        && (field.startsWith(".") || field.endsWith(".") || field.contains(".."))) {
      throw new IllegalArgumentException("field " + field + " isn't names joined with dots");
    }
  }

  /** Checks a regular expression compiles. */
  static void checkPattern(String pattern, String what) {
    checkText(pattern, what);
    try {
      Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException(
          what + " isn't a regular expression: " + e.getDescription());
    }
  }

  /** Checks text has no control characters and isn't too long. */
  static void checkText(String text, String what) {
    if (text.length() > MAX_TEXT) {
      throw new IllegalArgumentException(what + " is longer than " + MAX_TEXT + " characters");
    }
    if (text.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
      throw new IllegalArgumentException(what + " has a control character");
    }
  }

  /** A JSON member's text, by its names joined with dots; empty when it isn't there. */
  static String member(JsonValue json, String field) {
    JsonValue at = json;
    for (String name : field.split("\\.")) {
      if (!(at instanceof JsonValue.Obj object) || object.get(name) == null) {
        return "";
      }
      JsonValue next = object.get(name);
      at = next == null ? JsonValue.NULL : next;
    }
    if (at instanceof JsonValue.Str string) {
      return string.value();
    }
    if (at instanceof JsonValue.Num number) {
      return number.text();
    }
    if (at instanceof JsonValue.Bool bool) {
      return Boolean.toString(bool.value());
    }
    if (at instanceof JsonValue.Null) {
      return "null";
    }
    return com.michaelgrundvig.frc.spotter.json.Json.compact(at);
  }
}
