package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real processes, read within bounds: this Java itself, and a program that never ends. */
class ProcessCommandsTest {
  private static final String JAVA =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  @TempDir Path dir;

  /** A program printing {@code lines} lines, then (if asked) sleeping a minute. */
  private List<String> program(int lines, boolean sleep) throws IOException {
    Path source = dir.resolve("Print.java");
    Files.writeString(
        source,
        "class Print { public static void main(String[] a) throws Exception {"
            + " for (int i = 0; i < "
            + lines
            + "; i++) System.out.println(\"line \" + i);"
            + " System.out.print(\"no newline\"); System.out.flush();"
            + (sleep ? " Thread.sleep(60000);" : "")
            + " } }");
    // -Xlog:disable: the JVM's own warnings (about containers, say) would print among the lines.
    return List.of(JAVA, "-Xlog:disable", source.toString());
  }

  @Test
  void whatACommandPrintsIsRead() throws IOException {
    try (ProcessCommands commands = new ProcessCommands()) {
      Commands.Output output = commands.run(program(2, false), Duration.ofSeconds(60), 100, 10_000);
      assertThat(output.exit()).isZero();
      assertThat(output.lines()).containsExactly("line 0", "line 1", "no newline");
      assertThat(output.truncated()).isFalse();
      assertThat(output.ok()).isTrue();
    }
  }

  @Test
  void aCommandIsStoppedOnceItsPrintedEnough() throws IOException {
    try (ProcessCommands commands = new ProcessCommands()) {
      Commands.Output lines = commands.run(program(50, true), Duration.ofSeconds(60), 3, 10_000);
      assertThat(lines.lines()).containsExactly("line 0", "line 1", "line 2");
      assertThat(lines.truncated()).isTrue();
      assertThat(lines.exit()).isEqualTo(-1);
      assertThat(lines.ok()).isTrue();

      Commands.Output bytes = commands.run(program(50, true), Duration.ofSeconds(60), 100, 20);
      assertThat(bytes.truncated()).isTrue();
      assertThat(bytes.lines()).containsExactly("line 0", "line 1");
    }
  }

  @Test
  void aCommandThatOverrunsIsStopped() throws IOException {
    try (ProcessCommands commands = new ProcessCommands()) {
      Commands.Output output = commands.run(program(1, true), Duration.ofSeconds(3), 100, 10_000);
      assertThat(output.timedOut()).isTrue();
      assertThat(output.ok()).isFalse();
      assertThat(output.lines()).contains("line 0");
    }
  }

  @Test
  void aCommandThatIsntThereFailsToStart() {
    try (ProcessCommands commands = new ProcessCommands()) {
      assertThatThrownBy(
              () ->
                  commands.run(
                      List.of(dir.resolve("no-such-command").toString()),
                      Duration.ofSeconds(1),
                      1,
                      1))
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  void theFirstLineOfWhatItWritesToStandardErrorIsKept() throws IOException {
    Path source = dir.resolve("Fail.java");
    Files.writeString(
        source,
        "class Fail { public static void main(String[] a) {"
            + " System.err.println(); System.err.println(\"  no layout of its own  \");"
            + " for (int i = 0; i < 100000; i++) System.err.println(\"more \" + i);"
            + " System.out.println(\"out\"); System.exit(1); } }");
    try (ProcessCommands commands = new ProcessCommands()) {
      Commands.Output output =
          commands.run(
              List.of(JAVA, "-Xlog:disable", source.toString()), Duration.ofSeconds(60), 10, 1000);
      assertThat(output.exit()).isEqualTo(1);
      assertThat(output.lines()).containsExactly("out");
      assertThat(output.errors()).isEqualTo("no layout of its own");
      assertThat(output.describe()).isEqualTo("exit 1: no layout of its own");
      // Nothing on standard error: no reason.
      assertThat(commands.run(program(1, false), Duration.ofSeconds(60), 10, 1000).errors())
          .isEmpty();
    }
  }
}
