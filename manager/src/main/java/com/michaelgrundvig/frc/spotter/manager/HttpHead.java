package com.michaelgrundvig.frc.spotter.manager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP answer's status and headers, read from a socket: what the stream's request needs, and no
 * more. The stream is asked for with HTTP/1.0, which a server must answer without chunks: its body
 * is the events themselves, until the connection closes, read with no framing to decode (the JDK's
 * own HTTP client decodes each chunk's header into a new String, so a stream read through it is
 * never free of garbage).
 *
 * @param status its status code
 * @param headers its headers, by name in lower case
 */
record HttpHead(int status, Map<String, String> headers) {
  /** The most an answer's status line and headers may be, in bytes. */
  static final int MAX = 64 << 10;

  /** A header's value, by name in any case; null when it has none. */
  @Nullable String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  /** The request for a path, HTTP/1.0, to a host ({@code host:port}), asking for protobuf. */
  static byte[] request(String path, String host, String accept) {
    return ("GET "
            + path
            + " HTTP/1.0\r\nHost: "
            + host
            + "\r\nAccept: "
            + accept
            + "\r\nUser-Agent: spotter-manager\r\n\r\n")
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Reads an answer's status line and headers, leaving the stream at its body. */
  static HttpHead read(InputStream in) throws IOException {
    int[] budget = {MAX};
    String status = line(in, budget);
    String[] parts = status.split(" ", 3);
    int code;
    try {
      if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
        throw new NumberFormatException();
      }
      code = Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("it doesn't answer in HTTP: " + quote(status));
    }
    Map<String, String> headers = new HashMap<>();
    while (true) {
      String line = line(in, budget);
      if (line.isEmpty()) {
        break;
      }
      int colon = line.indexOf(':');
      if (colon > 0) {
        headers.put(
            line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
            line.substring(colon + 1).strip());
      }
    }
    String coding = headers.get("transfer-encoding");
    if (coding != null && !coding.equalsIgnoreCase("identity")) {
      throw new IOException("it answers HTTP/1.0 with a transfer coding: " + coding);
    }
    return new HttpHead(code, Map.copyOf(headers));
  }

  /** A line, without its CRLF (or LF). */
  private static String line(InputStream in, int[] budget) throws IOException {
    StringBuilder line = new StringBuilder();
    while (true) {
      int b = in.read();
      if (b < 0) {
        throw new IOException(
            line.length() == 0 && budget[0] == MAX
                ? "it closed the connection without answering"
                : "its answer ended in its headers");
      }
      if (--budget[0] < 0) {
        throw new IOException("its answer's headers are over " + MAX + " bytes");
      }
      if (b == '\n') {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
          line.setLength(end - 1);
        }
        return line.toString();
      }
      line.append((char) b);
    }
  }

  private static String quote(String text) {
    return "\"" + (text.length() > 80 ? text.substring(0, 80) + "…" : text) + "\"";
  }
}
