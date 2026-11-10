package com.michaelgrundvig.frc.spotter.agent.packs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The catalog's scripts that read the journal, run against a stand-in journalctl first on the PATH:
 * what they make of its failing, and what they ask it for. What they make of the real one is the
 * container tests' (CatalogContainerTest).
 */
class CatalogScriptsTest {
  @TempDir Path dir;
  Path bin;
  Path kernel;
  Path asked;

  /**
   * A journalctl that prints {@code $JOURNAL_LINES} lines of JSON, or its kernel log ({@code -k})
   * from after its cursor, a line number, saying the cursor it got to as journalctl does; then
   * exits {@code $STATUS}. It notes each cursor it was asked from.
   */
  private static final String JOURNALCTL =
      """
      #!/bin/sh
      after=0
      kernel=no
      for argument; do
        case $argument in
          --after-cursor=*) after=${argument#--after-cursor=} ;;
          -k) kernel=yes ;;
        esac
      done
      echo "$after" >> "$ASKED"
      if [ "${STATUS:-0}" != 0 ]; then
        echo "Failed to open the journal" >&2
        exit "$STATUS"
      fi
      if [ "$kernel" = yes ]; then
        lines=$(wc -l < "$KERNEL")
        [ "$lines" -gt "$after" ] || exit 0
        tail -n +"$((after + 1))" "$KERNEL"
        echo "-- cursor: $lines"
        exit 0
      fi
      i=0
      while [ "$i" -lt "${JOURNAL_LINES:-0}" ]; do
        echo '{"MESSAGE":"line '"$i"'","PRIORITY":"6","SYSLOG_IDENTIFIER":"test"}'
        i=$((i + 1))
      done
      """;

  @BeforeEach
  void aStandInJournal() throws IOException {
    bin = Files.createDirectories(dir.resolve("bin"));
    Path journalctl = bin.resolve("journalctl");
    Files.writeString(journalctl, JOURNALCTL);
    Files.setPosixFilePermissions(journalctl, PosixFilePermissions.fromString("rwxr-xr-x"));
    kernel = dir.resolve("kernel.log");
    Files.writeString(kernel, "");
    asked = dir.resolve("asked");
  }

  /** What a script printed, said on its standard error, and exited with. */
  record Ran(int code, String out, String err) {}

  private Ran run(String script, Map<String, String> environment) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("/bin/sh", CatalogTest.CATALOG.resolve("debian/" + script).toString());
    builder.environment().remove("RUNTIME_DIRECTORY");
    builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    builder.environment().put("ASKED", asked.toString());
    builder.environment().put("KERNEL", kernel.toString());
    builder.environment().putAll(environment);
    Path out = dir.resolve("out");
    Path err = dir.resolve("err");
    Process process = builder.redirectOutput(out.toFile()).redirectError(err.toFile()).start();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).as(script + " ended").isTrue();
    return new Ran(process.exitValue(), Files.readString(out), Files.readString(err));
  }

  @Test
  void theJournalSaysWhenJournalctlFailsAndNotWhenItStoppedReadingIt() throws Exception {
    // Each row: what journalctl does, then the page's exit code, its lines, and what it says.
    record Row(String what, Map<String, String> journal, int code, int lines, String says) {}
    for (Row row :
        List.of(
            new Row("fails", Map.of("STATUS", "1"), 1, 0, "journalctl failed (exit 1)"),
            new Row("prints a few", Map.of("JOURNAL_LINES", "3"), 0, 3, ""),
            // More than a page: the page is cut, and journalctl ends on SIGPIPE, no failure.
            new Row("prints more than a page", Map.of("JOURNAL_LINES", "20000"), 0, 100, ""))) {
      for (String from : List.of("latest", "before", "after")) {
        Map<String, String> environment = new java.util.HashMap<>(row.journal());
        environment.put("SPOTTER_FROM", from);
        environment.put("SPOTTER_CURSOR", "s=1");
        Ran ran = run("journal", environment);
        String as = row.what() + ", from " + from;
        assertThat(ran.code()).as(as).isEqualTo(row.code());
        assertThat(ran.out().lines().count()).as(as).isEqualTo(row.lines());
        assertThat(ran.err()).as(as).contains(row.says());
      }
    }
  }

  /** How many disconnects the usb collector said; null when it said nothing of them. */
  private @Nullable Long disconnects(Map<String, String> environment) throws Exception {
    Ran ran = run("usb", environment);
    assertThat(ran.code()).isZero();
    Matcher said = Pattern.compile("\"disconnects\": ([0-9]+)").matcher(ran.out());
    return said.find() ? Long.valueOf(said.group(1)) : null;
  }

  private void logged(String... lines) throws IOException {
    Files.writeString(
        kernel,
        String.join("\n", lines) + "\n",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);
  }

  private List<String> askedFrom() throws IOException {
    List<String> from = Files.readAllLines(asked);
    Files.delete(asked);
    return from;
  }

  @Test
  void usbCountsDisconnectsOnFromWhereItGotTo() throws Exception {
    Path runtime = Files.createDirectories(dir.resolve("run"));
    Map<String, String> agent = Map.of("RUNTIME_DIRECTORY", runtime.toString());
    logged(
        "usb 7-1: new device",
        "usb 7-1: USB disconnect, device number 2",
        "usb 8-1: USB disconnect");
    assertThat(disconnects(agent)).isEqualTo(2);
    assertThat(askedFrom()).containsExactly("0");

    // Only the lines since: two more, one a disconnect.
    logged("usb 7-1: new device", "usb 7-1: USB disconnect, device number 3");
    assertThat(disconnects(agent)).isEqualTo(3);
    assertThat(askedFrom()).containsExactly("3");
    assertThat(disconnects(agent)).isEqualTo(3);
    assertThat(askedFrom()).containsExactly("5");

    // What it kept is another boot's: counted afresh, from the start.
    Path saved = runtime.resolve("debian-usb");
    List<String> kept = Files.readAllLines(saved);
    Files.write(saved, List.of("another-boot", kept.get(1), kept.get(2)));
    assertThat(disconnects(agent)).isEqualTo(3);
    assertThat(askedFrom()).containsExactly("0");

    // With no runtime folder, the whole boot's log, each time.
    assertThat(disconnects(Map.of())).isEqualTo(3);
    assertThat(askedFrom()).containsExactly("0");

    // A journal it can't read: nothing said of disconnects, and what it kept stands.
    assertThat(disconnects(Map.of("RUNTIME_DIRECTORY", runtime.toString(), "STATUS", "1")))
        .isNull();
    assertThat(Files.readAllLines(saved).get(1)).isEqualTo("3");
  }
}
