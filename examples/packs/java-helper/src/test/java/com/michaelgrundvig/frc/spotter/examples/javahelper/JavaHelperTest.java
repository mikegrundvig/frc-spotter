package com.michaelgrundvig.frc.spotter.examples.javahelper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaHelperTest {
  @TempDir Path dir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  private int run(String... args) {
    return JavaHelper.run(
        List.of(args),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void itSaysTheJavaItRunsOn() {
    assertThat(run("java")).isEqualTo(JavaHelper.FOUND);
    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo("java " + Runtime.version().feature() + System.lineSeparator());
  }

  @Test
  void itHashesAFile() throws IOException {
    Path file = dir.resolve("os-release");
    Files.writeString(file, "abc");
    assertThat(run("sha256", file.toString())).isEqualTo(JavaHelper.FOUND);
    assertThat(out.toString(StandardCharsets.UTF_8).strip())
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void aFileItCantReadOrAWrongCommandSaysWhyOnStandardError() {
    assertThat(run("sha256", dir.resolve("none").toString())).isEqualTo(JavaHelper.FAILED);
    assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("can't read ");
    assertThat(run("nope")).isEqualTo(JavaHelper.USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage: java-helper");
  }
}
