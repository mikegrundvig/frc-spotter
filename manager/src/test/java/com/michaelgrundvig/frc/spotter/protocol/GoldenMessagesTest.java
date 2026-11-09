package com.michaelgrundvig.frc.spotter.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

/**
 * Protocol 2.0's messages pinned to their bytes: each message, every field of it set and every
 * value of each enum used, as the bytes committed in {@code golden-messages.txt} are read. Boards
 * and robots on different releases of Spotter speak to each other, and only the major version is
 * checked, so a field number, a field's type or an enum's value that changed would be read as
 * something else, silently. A failure here is a change to the protocol: a new major version, or a
 * field added under a number of its own (its message's line then rewritten with the bytes the
 * failure gives, the old fields' bytes as they were). The bytes are compared as they're read, not
 * as they're written, so an encoder that orders fields otherwise is no change.
 */
class GoldenMessagesTest {
  /** Each message by its line's name, in the file's order. */
  private static Map<String, ProtoMessage<?>> messages() {
    Map<String, ProtoMessage<?>> messages = new LinkedHashMap<>();
    messages.put("event-described", Spotter.Event.newInstance().setDescribed(description()));
    messages.put("event-values", Spotter.Event.newInstance().setValues(values()));
    messages.put("event-runs", Spotter.Event.newInstance().setRuns(runs()));
    messages.put(
        "event-run-started", Spotter.Event.newInstance().setRun(runEvent().setStarted(true)));
    messages.put(
        "event-run-log",
        Spotter.Event.newInstance()
            .setRun(runEvent().setLog(entry(Spotter.LogLevel.LOG_LEVEL_WARNING))));
    messages.put(
        "event-run-finished",
        Spotter.Event.newInstance()
            .setRun(runEvent().setFinished(result(Spotter.Outcome.OUTCOME_COMPLETED))));
    messages.put(
        "event-heartbeat",
        Spotter.Event.newInstance()
            .setHeartbeat(Spotter.Heartbeat.newInstance().setTimeNanos(1_234_567_890_123L)));
    messages.put("log-page", logPage());
    messages.put("started", Spotter.Started.newInstance().setRun("r-7f3a"));
    messages.put("problem", Spotter.Problem.newInstance().setMessage("a push is under way"));
    messages.put("pack-bundle", bundle());
    return messages;
  }

  private static Spotter.Description description() {
    Spotter.Description description =
        Spotter.Description.newInstance()
            .setRevision(7)
            .setAgentVersion("0.4.1")
            .setIdentity(
                Spotter.Identity.newInstance()
                    .setHostname("vision-front")
                    .addAddresses("10.12.34.11")
                    .setMac("c0:74:2b:fe:12:34")
                    .setBootId("3c1e6a2e-0000-4000-8000-000000000001")
                    .addOsRelease(
                        Spotter.Identity.OsReleaseEntry.newInstance()
                            .setKey("ID")
                            .setValue("debian")))
            .addPacks(
                Spotter.Pack.newInstance()
                    .setName("vision")
                    .setVersion("1.0.0")
                    .setFolder("/etc/frc-spotter/packs/vision")
                    .setPushed(true))
            .addLogs(Spotter.LogDeclaration.newInstance().setId("vision.log").setLabel("Its log"))
            .addProblems("/etc/frc-spotter/packs/mine/pack.yaml: isn't root's, ignored")
            .setPushedPacks("9f86d081884c7d65")
            .setRefusesPushes(true)
            .setRequiresSignatures(true);
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("vision.health.fps")
            .setLabel("Frame rate")
            .setType(Spotter.FieldType.FIELD_TYPE_NUMBER)
            .setUnit("fps")
            .setWarn(
                Spotter.Limit.newInstance()
                    .setAbove(90)
                    .setBelow(25)
                    .setEquals(Spotter.Scalar.newInstance().setNumber(1.5))
                    .setNotEquals(Spotter.Scalar.newInstance().setText("idle"))
                    .setMissing(true))
            .setFail(
                Spotter.Limit.newInstance()
                    .setBelow(10)
                    .setEquals(Spotter.Scalar.newInstance().setFlag(true))));
    // Every field type, past the first.
    for (Spotter.FieldType type :
        List.of(
            Spotter.FieldType.FIELD_TYPE_TEXT,
            Spotter.FieldType.FIELD_TYPE_BOOLEAN,
            Spotter.FieldType.FIELD_TYPE_STATUS,
            Spotter.FieldType.FIELD_TYPE_JSON,
            Spotter.FieldType.FIELD_TYPE_FILE)) {
      description.addValues(
          Spotter.FieldDeclaration.newInstance()
              .setId("vision.all." + type.getNumber())
              .setType(type));
    }
    // Every kind of input.
    for (Spotter.Input input :
        List.of(Spotter.Input.INPUT_NONE, Spotter.Input.INPUT_TEXT, Spotter.Input.INPUT_FILE)) {
      description.addActions(
          Spotter.ActionDeclaration.newInstance()
              .setId("vision.restart-" + input.getNumber())
              .setLabel("Restart vision")
              .setDescription("Restarts its service")
              .setInput(input)
              .setConfirm("Restart it?")
              .setTimeoutSeconds(90)
              .setWhileEnabled(true)
              .addResponse(
                  Spotter.FieldDeclaration.newInstance()
                      .setId("exit")
                      .setType(Spotter.FieldType.FIELD_TYPE_NUMBER)));
    }
    return description;
  }

  private static Spotter.Values values() {
    Spotter.Values values =
        Spotter.Values.newInstance().setRevision(7).setTimeNanos(42).setComplete(true);
    values.addValues(Spotter.FieldValue.newInstance().setIndex(1).setNumber(61.5));
    values.addValues(Spotter.FieldValue.newInstance().setIndex(2).setText("active"));
    values.addValues(Spotter.FieldValue.newInstance().setIndex(3).setFlag(true));
    // Every level.
    for (Spotter.Level level :
        List.of(Spotter.Level.LEVEL_OK, Spotter.Level.LEVEL_WARNING, Spotter.Level.LEVEL_FAILING)) {
      values.addValues(
          Spotter.FieldValue.newInstance()
              .setIndex(3 + level.getNumber())
              .setStatus(Spotter.Status.newInstance().setLevel(level).setMessage("camera")));
    }
    values.addValues(Spotter.FieldValue.newInstance().setName("body").setJson("{\"a\": 1}"));
    values.addValues(
        Spotter.FieldValue.newInstance()
            .setName("output")
            .setFileUrl("/v2/runs/r-7f3a/files/output"));
    values.addValues(
        Spotter.FieldValue.newInstance().setIndex(9).setUnavailable("timed out after 5s"));
    return values;
  }

  private static Spotter.Runs runs() {
    Spotter.Runs runs = Spotter.Runs.newInstance();
    // Every outcome.
    for (Spotter.Outcome outcome :
        List.of(
            Spotter.Outcome.OUTCOME_COMPLETED,
            Spotter.Outcome.OUTCOME_UNREACHABLE,
            Spotter.Outcome.OUTCOME_COULD_NOT_START,
            Spotter.Outcome.OUTCOME_TIMED_OUT,
            Spotter.Outcome.OUTCOME_CANCELLED,
            Spotter.Outcome.OUTCOME_LOST)) {
      runs.addRuns(
          Spotter.RunState.newInstance()
              .setRun("r-" + outcome.getNumber())
              .setAction("vision.restart")
              .setRunning(true)
              .setResult(result(outcome)));
    }
    return runs;
  }

  private static Spotter.RunResult result(Spotter.Outcome outcome) {
    return Spotter.RunResult.newInstance()
        .setOutcome(outcome)
        .setOutcomeMessage("timed out after 90s")
        .addResponse(Spotter.FieldValue.newInstance().setName("exit").setNumber(3));
  }

  private static Spotter.RunEvent runEvent() {
    return Spotter.RunEvent.newInstance()
        .setRun("r-7f3a")
        .setAction("vision.restart")
        .setTimeNanos(1_234_567_890_123L);
  }

  private static Spotter.LogEntry entry(Spotter.LogLevel level) {
    return Spotter.LogEntry.newInstance()
        .setTimeMicros(1_762_700_000_000_000L)
        .setLevel(level)
        .setSource("vision")
        .setMessage("camera reconnected")
        .setCursor("s=" + level.getNumber());
  }

  private static Spotter.LogPage logPage() {
    Spotter.LogPage page = Spotter.LogPage.newInstance().setBefore("s=1").setAfter("s=4");
    // Every log level.
    for (Spotter.LogLevel level :
        List.of(
            Spotter.LogLevel.LOG_LEVEL_ERROR,
            Spotter.LogLevel.LOG_LEVEL_WARNING,
            Spotter.LogLevel.LOG_LEVEL_INFO,
            Spotter.LogLevel.LOG_LEVEL_DEBUG)) {
      page.addEntries(entry(level));
    }
    return page;
  }

  private static Spotter.PackBundle bundle() {
    return Spotter.PackBundle.newInstance()
        .addFiles(
            Spotter.PackFile.newInstance()
                .setPath("vision/check")
                .setExecutable(true)
                .setContent("#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8)));
  }

  /** The committed bytes, by name: each line {@code <name> <hex>}, past the comments. */
  private static Map<String, String> golden() throws IOException {
    Map<String, String> golden = new LinkedHashMap<>();
    try (InputStream in =
        Objects.requireNonNull(
            GoldenMessagesTest.class.getResourceAsStream("golden-messages.txt"),
            "golden-messages.txt")) {
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.strip().split(" ", 2);
        golden.put(parts[0], parts[1]);
      }
    }
    return golden;
  }

  @Test
  void theCommittedBytesOfEachMessageAreReadAsIt() throws Exception {
    Map<String, ProtoMessage<?>> messages = messages();
    Map<String, String> golden = golden();
    assertThat(golden.keySet()).containsExactlyElementsOf(messages.keySet());
    for (Map.Entry<String, ProtoMessage<?>> each : messages.entrySet()) {
      ProtoMessage<?> read = each.getValue().clone();
      read.clear();
      read.mergeFrom(ProtoSource.newInstance(HexFormat.of().parseHex(golden.get(each.getKey()))));
      // On a mismatch, the bytes this release writes for it: the protocol changed (see the class).
      assertThat(read)
          .as(
              "%s, which this release writes as %s",
              each.getKey(), HexFormat.of().formatHex(each.getValue().toByteArray()))
          .isEqualTo(each.getValue());
    }
  }
}
