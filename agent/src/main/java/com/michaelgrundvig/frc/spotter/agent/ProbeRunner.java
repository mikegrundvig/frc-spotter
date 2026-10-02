package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.probes.Check;
import com.michaelgrundvig.frc.spotter.probes.Probe;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one probe once, by its kind, within its timeout, and says what it found: the only place a
 * probe's check is carried out. Nothing here comes from a request: every program, path, and URL is
 * the probe's own, fixed when the agent started.
 */
final class ProbeRunner {
  /** The most of a command's output read: a probe's answer is a line or two. */
  static final int MAX_OUTPUT_BYTES = 64 * 1024;

  /** The most lines of a command's output read. */
  static final int MAX_OUTPUT_LINES = 2000;

  /** The most of an HTTP answer read. */
  static final int MAX_HTTP_BYTES = 256 * 1024;

  /** The most of a file hashed: a jar of a few hundred megabytes at most. */
  static final long MAX_HASH_BYTES = 1L << 30;

  private final Host host;
  private final UsbDevices usb;
  private final Supplier<Map<String, Double>> metrics;

  /**
   * @param metrics the agent's latest measurements, for threshold probes
   */
  ProbeRunner(Host host, Supplier<Map<String, Double>> metrics) {
    this.host = host;
    this.usb = new UsbDevices(host);
    this.metrics = metrics;
  }

  /** What a run found: its status, value, and why it failed or erred. */
  record Found(String status, String value, String detail) {
    static Found pass(String value) {
      return new Found(ProbeResult.PASS, value, "");
    }

    static Found fail(String value, String detail) {
      return new Found(ProbeResult.FAIL, value, detail);
    }

    static Found error(String detail) {
      return new Found(ProbeResult.ERROR, "", detail);
    }
  }

  /** Runs a probe once, on the caller's thread, and says what it found and when. */
  ProbeResult run(Probe probe) {
    long ranMicros = host.monotonicMicros();
    long started = System.nanoTime();
    Found found;
    try {
      found = check(probe);
    } catch (IOException | RuntimeException e) {
      found = Found.error(String.valueOf(e.getMessage()));
    }
    return new ProbeResult(
        probe.id(),
        probe.kind().id(),
        found.status(),
        found.value(),
        found.detail(),
        ranMicros,
        (System.nanoTime() - started) / 1e6);
  }

  private Found check(Probe probe) throws IOException {
    Duration timeout = Duration.ofMillis(Math.round(probe.timeoutSeconds() * 1000));
    Check check = probe.check();
    if (check instanceof Check.Command command) {
      return command(command, timeout);
    }
    if (check instanceof Check.Http http) {
      return http(http, timeout);
    }
    if (check instanceof Check.File file) {
      return file(file, timeout);
    }
    if (check instanceof Check.Unit unit) {
      return unit(unit, timeout);
    }
    if (check instanceof Check.Usb device) {
      return usb(device);
    }
    return threshold((Check.Threshold) check);
  }

  // ---- command ----

  private Found command(Check.Command check, Duration timeout) throws IOException {
    Commands.Output output =
        host.commands().run(check.argv(), timeout, MAX_OUTPUT_LINES, MAX_OUTPUT_BYTES);
    if (output.timedOut()) {
      return Found.error("timed out after " + timeout.toMillis() + " ms");
    }
    // Why it failed, when it failed and said so on its standard error.
    String why = output.exit() == 0 || output.errors().isEmpty() ? "" : ": " + output.errors();
    String printed = String.join("\n", output.lines());
    String value;
    if (!check.match().isEmpty()) {
      Matcher matcher = Pattern.compile(check.match(), Pattern.MULTILINE).matcher(printed);
      if (!matcher.find()) {
        return Found.fail(
            firstLine(printed),
            "exit "
                + output.exit()
                + (why.isEmpty() ? ": its output didn't match " + check.match() : why));
      }
      value = matched(matcher);
    } else {
      value = firstLine(printed);
    }
    if (check.exit() != Check.Command.ANY_EXIT && output.exit() != check.exit()) {
      return Found.fail(
          value,
          "exit "
              + output.exit()
              + ", not "
              + check.exit()
              + (why.isEmpty() ? ": " + firstLine(printed) : why));
    }
    return Found.pass(value);
  }

  // ---- http ----

  private Found http(Check.Http check, Duration timeout) throws IOException {
    HttpURLConnection connection;
    try {
      connection = (HttpURLConnection) new URI(check.url()).toURL().openConnection(Proxy.NO_PROXY);
    } catch (java.net.URISyntaxException e) {
      return Found.error("not a URL: " + check.url());
    }
    int millis = (int) Math.max(1, timeout.toMillis());
    connection.setConnectTimeout(millis);
    connection.setReadTimeout(millis);
    connection.setInstanceFollowRedirects(false);
    connection.setUseCaches(false);
    try {
      int status = connection.getResponseCode();
      String body = "";
      if (!check.field().isEmpty()) {
        InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (in != null) {
          try (in) {
            body = new String(in.readNBytes(MAX_HTTP_BYTES), StandardCharsets.UTF_8);
          }
        }
      }
      if (status != check.status()) {
        return Found.fail(
            Integer.toString(status), "answered " + status + ", not " + check.status());
      }
      if (check.field().isEmpty()) {
        return Found.pass(Integer.toString(status));
      }
      String value;
      try {
        value = Check.member(Json.parse(body), check.field());
      } catch (JsonException e) {
        return Found.fail("", "its answer isn't JSON: " + e.getMessage());
      }
      return field(value, check.field(), check.equals());
    } catch (java.net.SocketTimeoutException e) {
      return Found.error("no answer within " + millis + " ms");
    } catch (java.net.ConnectException e) {
      return Found.fail("", "nothing answers at " + check.url());
    } finally {
      connection.disconnect();
    }
  }

  private static Found field(String value, String field, String equals) {
    if (value.isEmpty()) {
      return Found.fail("", field + " isn't there");
    }
    if (!equals.isEmpty() && !value.equals(equals)) {
      return Found.fail(value, field + " is " + value + ", not " + equals);
    }
    return Found.pass(value);
  }

  // ---- file ----

  private Found file(Check.File check, Duration timeout) throws IOException {
    Path path = host.path(check.path());
    if (!Files.exists(path)) {
      return Found.fail("", check.path() + " isn't there");
    }
    switch (check.test()) {
      case EXISTS:
        return Found.pass(Long.toString(Files.size(path)));
      case SIZE:
        long size = Files.size(path);
        if ((check.minBytes() >= 0 && size < check.minBytes())
            || (check.maxBytes() >= 0 && size > check.maxBytes())) {
          return Found.fail(
              Long.toString(size),
              size + " bytes, outside " + check.minBytes() + " to " + check.maxBytes());
        }
        return Found.pass(Long.toString(size));
      case SHA256:
        String hash = sha256(path, timeout);
        if (!check.sha256().isEmpty() && !hash.equals(check.sha256())) {
          return Found.fail(hash, "its SHA-256 isn't " + check.sha256());
        }
        return Found.pass(hash);
      case JSON:
        String text = host.read(check.path()).orElse("");
        try {
          return field(
              Check.member(Json.parse(text), check.field()), check.field(), check.equals());
        } catch (JsonException e) {
          return Found.fail("", check.path() + " isn't JSON: " + e.getMessage());
        }
      default:
        Matcher matcher =
            Pattern.compile(check.match(), Pattern.MULTILINE)
                .matcher(host.read(check.path()).orElse(""));
        if (!matcher.find()) {
          return Found.fail("", check.path() + " has nothing matching " + check.match());
        }
        return Found.pass(matched(matcher));
    }
  }

  private static String sha256(Path path, Duration timeout) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every Java has SHA-256", e);
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    long read = 0;
    try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
      byte[] chunk = new byte[64 * 1024];
      int got;
      while ((got = in.read(chunk)) != -1) {
        read += got;
        if (read > MAX_HASH_BYTES) {
          throw new IOException("it's larger than the " + MAX_HASH_BYTES + " bytes hashed");
        }
        if (System.nanoTime() > deadline) {
          throw new IOException("hashing it took longer than " + timeout.toMillis() + " ms");
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  // ---- unit ----

  /** What's read of a unit. */
  static final List<String> UNIT_PROPERTIES =
      List.of("LoadState", "ActiveState", "SubState", "Result", "NRestarts");

  private Found unit(Check.Unit check, Duration timeout) throws IOException {
    Commands.Output output =
        host.commands()
            .run(
                List.of(
                    "systemctl",
                    "show",
                    check.unit(),
                    "--property=" + String.join(",", UNIT_PROPERTIES)),
                timeout,
                32,
                4096);
    if (!output.ok()) {
      return Found.error(
          "systemctl show " + check.unit() + (output.timedOut() ? " timed out" : " failed"));
    }
    Map<String, String> values = new java.util.HashMap<>();
    for (String line : output.lines()) {
      int equals = line.indexOf('=');
      if (equals > 0) {
        values.put(line.substring(0, equals), line.substring(equals + 1));
      }
    }
    if ("not-found".equals(values.get("LoadState"))) {
      return Found.fail("not-found", check.unit() + " isn't installed");
    }
    String active = values.getOrDefault("ActiveState", "");
    String sub = values.getOrDefault("SubState", "");
    String restarts = values.getOrDefault("NRestarts", "0");
    String value = active + "/" + sub + ", restarts " + restarts;
    if (!active.equals(check.state())) {
      String result = values.getOrDefault("Result", "");
      return Found.fail(
          value,
          check.unit()
              + " is "
              + active
              + ", not "
              + check.state()
              + (result.isEmpty() || result.equals("success") ? "" : " (" + result + ")"));
    }
    return Found.pass(value);
  }

  // ---- usb ----

  private Found usb(Check.Usb check) throws IOException {
    Optional<UsbDevices.Found> found = usb.at(check.path());
    if (found.isEmpty()) {
      return Found.fail("", "nothing at " + check.path());
    }
    UsbDevices.Found device = found.get();
    String value =
        String.format(
                Locale.ROOT,
                "%s %.0f Mb/s %s",
                device.port().isEmpty() ? "(port unknown)" : device.port(),
                device.speedMbps(),
                device.product().isEmpty()
                    ? device.vendorId() + ":" + device.productId()
                    : device.product())
            .strip();
    if (check.minSpeedMbps() > 0 && device.speedMbps() < check.minSpeedMbps()) {
      return Found.fail(
          value,
          String.format(
              Locale.ROOT,
              "linked at %.0f Mb/s, below %.0f",
              device.speedMbps(),
              check.minSpeedMbps()));
    }
    return Found.pass(value);
  }

  // ---- threshold ----

  private Found threshold(Check.Threshold check) {
    Double value = metrics.get().get(check.metric());
    if (value == null || value.isNaN()) {
      return Found.error(check.metric() + " isn't measured on this computer");
    }
    String text = format(value);
    if (!Double.isNaN(check.min()) && value < check.min()) {
      return Found.fail(text, check.metric() + " is " + text + ", below " + format(check.min()));
    }
    if (!Double.isNaN(check.max()) && value > check.max()) {
      return Found.fail(text, check.metric() + " is " + text + ", above " + format(check.max()));
    }
    return Found.pass(text);
  }

  private static String format(double value) {
    return value == Math.rint(value) && Math.abs(value) < 1e15
        ? Long.toString((long) value)
        : String.format(Locale.ROOT, "%.1f", value);
  }

  // ---- helpers ----

  private static String matched(Matcher matcher) {
    return matcher.groupCount() >= 1 && matcher.group(1) != null
        ? matcher.group(1)
        : matcher.group();
  }

  private static String firstLine(String text) {
    int newline = text.indexOf('\n');
    return (newline < 0 ? text : text.substring(0, newline)).strip();
  }
}
