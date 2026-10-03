package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A pack's logs: entries read from JSON lines by its map, and pages asked for by SPOTTER_*. */
class LogsTest {
  @TempDir Path dir;
  Fixture fixture;
  Commands commands;

  /** journalctl -o json's names, as the debian pack maps them. */
  static final Pack.LogMap JOURNAL =
      new Pack.LogMap(
          "__REALTIME_TIMESTAMP", "us", "PRIORITY", "SYSLOG_IDENTIFIER", "MESSAGE", "__CURSOR");

  /**
   * A log script paging a file of numbered lines by SPOTTER_*: its cursor is the line's number. It
   * prints what it was asked in each entry's source, so the test sees the environment.
   */
  static final String PAGER =
      """
      from=${SPOTTER_FROM}; cursor=${SPOTTER_CURSOR:-0}; limit=${SPOTTER_LIMIT}
      total=20
      case $from in
        latest) first=$((total - limit + 1)) last=$total ;;
        before) first=$((cursor - limit)) last=$((cursor - 1)) ;;
        after) first=$((cursor + 1)) last=$((cursor + limit)) ;;
      esac
      [ $first -lt 1 ] && first=1
      [ $last -gt $total ] && last=$total
      n=$first
      while [ $n -le $last ]; do
        level=info; [ $((n % 5)) -eq 0 ] && level=error
        echo "{\\"time\\": \\"2026-10-02T12:00:$(printf %02d $n)Z\\", \\"level\\": \\"$level\\","\
      " \\"source\\": \\"$from/$SPOTTER_LEVEL\\", \\"message\\": \\"line $n\\", \\"cursor\\": \\"$n\\"}"
        n=$((n + 1))
      done
      """;

  @BeforeEach
  void aBoard() throws Exception {
    fixture = new Fixture(dir);
    commands = new Commands(fixture.host);
  }

  @AfterEach
  void close() {
    commands.close();
  }

  @Test
  void journalLinesAreEntriesByTheirMap() {
    Spotter.LogEntry entry =
        LogLines.entry(
                "{\"__REALTIME_TIMESTAMP\": \"1759406400123456\", \"PRIORITY\": \"3\","
                    + " \"SYSLOG_IDENTIFIER\": \"photonvision\", \"MESSAGE\": \"camera lost\","
                    + " \"__CURSOR\": \"s=abc;i=1\"}",
                JOURNAL,
                0)
            .orElseThrow();
    assertThat(entry.getTimeMicros()).isEqualTo(1759406400123456L);
    assertThat(entry.getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_ERROR);
    assertThat(entry.getSource()).isEqualTo("photonvision");
    assertThat(entry.getMessage()).isEqualTo("camera lost");
    assertThat(entry.getCursor()).isEqualTo("s=abc;i=1");
  }

  @Test
  void levelsAreNamesOrSyslogsNumbers() {
    for (Object[] each :
        new Object[][] {
          {"error", Spotter.LogLevel.LOG_LEVEL_ERROR},
          {0L, Spotter.LogLevel.LOG_LEVEL_ERROR},
          {"2", Spotter.LogLevel.LOG_LEVEL_ERROR},
          {"WARNING", Spotter.LogLevel.LOG_LEVEL_WARNING},
          {"4", Spotter.LogLevel.LOG_LEVEL_WARNING},
          {"5", Spotter.LogLevel.LOG_LEVEL_INFO},
          {"info", Spotter.LogLevel.LOG_LEVEL_INFO},
          {7L, Spotter.LogLevel.LOG_LEVEL_DEBUG},
          {"loud", Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED}
        }) {
      assertThat(LogLines.level(each[0])).as("%s", each[0]).isEqualTo(each[1]);
    }
    assertThat(LogLines.level(null)).isEqualTo(Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED);
    assertThat(LogLines.name(Spotter.LogLevel.LOG_LEVEL_WARNING)).isEqualTo("warning");
    assertThat(LogLines.name(Spotter.LogLevel.LOG_LEVEL_UNSPECIFIED)).isEqualTo("debug");
  }

  @Test
  void timesAreIso8601OrANumberInTheDeclaredUnit() {
    assertThat(LogLines.micros("2026-10-02T12:00:01.5Z", "")).isEqualTo(1790942401500000L);
    assertThat(LogLines.micros("2026-10-02T08:00:01-04:00", "")).isEqualTo(1790942401000000L);
    assertThat(LogLines.micros(1759406401L, "s")).isEqualTo(1759406401000000L);
    assertThat(LogLines.micros("1759406401000", "ms")).isEqualTo(1759406401000000L);
    assertThat(LogLines.micros(1759406401000000000L, "ns")).isEqualTo(1759406401000000L);
    // A number without a unit, or text that's neither: no time.
    assertThat(LogLines.micros(1759406401L, "")).isZero();
    assertThat(LogLines.micros("yesterday", "")).isZero();
    assertThat(LogLines.micros("soon", "us")).isZero();
    assertThat(LogLines.micros(null, "us")).isZero();
  }

  @Test
  void aPlainLineIsAMessageAtInfo() {
    Spotter.LogEntry plain =
        LogLines.entry("  step 2 of 3  ", Pack.LogMap.DEFAULT, 42).orElseThrow();
    assertThat(plain.getMessage()).isEqualTo("step 2 of 3");
    assertThat(plain.getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_INFO);
    assertThat(plain.getTimeMicros()).isEqualTo(42);
    assertThat(LogLines.entry("   ", Pack.LogMap.DEFAULT, 42)).isEmpty();
    Spotter.LogEntry json =
        LogLines.entry("{\"level\": \"warning\", \"message\": {\"a\": 1}}", Pack.LogMap.DEFAULT, 42)
            .orElseThrow();
    assertThat(json.getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_WARNING);
    assertThat(json.getMessage()).isEqualTo("{\"a\":1}");
    assertThat(json.getTimeMicros()).isEqualTo(42);
    assertThat(
            LogLines.entry("{\"message\": \"" + "x".repeat(5000) + "\"}", Pack.LogMap.DEFAULT, 0))
        .hasValueSatisfying(e -> assertThat(e.getMessage()).hasSize(LogLines.MAX_MESSAGE));
  }

  private static Logs.Paging paging(String... pairs) throws Logs.Refused {
    Map<String, String> query = new java.util.HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      query.put(pairs[i], pairs[i + 1]);
    }
    return Logs.Paging.of(query);
  }

  @Test
  void pagingIsCheckedAndPassedAsTheEnvironment() throws Exception {
    Logs.Paging latest = paging();
    assertThat(latest)
        .isEqualTo(new Logs.Paging("latest", "", 100, Spotter.LogLevel.LOG_LEVEL_DEBUG));
    assertThat(paging("from", "after", "cursor", "c1", "limit", "1000", "level", "3").environment())
        .containsExactly(
            Map.entry("SPOTTER_FROM", "after"),
            Map.entry("SPOTTER_CURSOR", "c1"),
            Map.entry("SPOTTER_LIMIT", "1000"),
            Map.entry("SPOTTER_LEVEL", "error"));
    for (String[] bad :
        new String[][] {
          {"from", "first"},
          {"from", "before"},
          {"limit", "0"},
          {"limit", "1001"},
          {"limit", "x"},
          {"level", "loud"}
        }) {
      assertThat(catchThrowableOfType(Logs.Refused.class, () -> paging(bad)).status)
          .as(String.join("=", bad))
          .isEqualTo(400);
    }
  }

  private Logs logs(String body) {
    String folder =
        fixture.pack(
            "team", "pack: team\nlogs:\n  - id: log\n    label: Its log\n    run: [./log]\n");
    fixture.script(folder + "/log", body);
    return new Logs(commands, Packs.load(fixture.host, true).packs());
  }

  @Test
  void aPageIsTheCommandsEntriesWithCursorsEitherSide() throws Exception {
    Logs logs = logs(PAGER);
    Spotter.LogPage latest = logs.page("team.log", paging("limit", "5"));
    assertThat(latest.getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("line 16", "line 17", "line 18", "line 19", "line 20");
    assertThat(latest.getEntries().get(0).getSource()).isEqualTo("latest/debug");
    assertThat(latest.getEntries().get(4).getLevel()).isEqualTo(Spotter.LogLevel.LOG_LEVEL_ERROR);
    assertThat(latest.getEntries().get(0).getTimeMicros()).isEqualTo(1790942416000000L);
    assertThat(latest.getBefore()).isEqualTo("16");
    assertThat(latest.getAfter()).isEqualTo("20");
    Spotter.LogPage before =
        logs.page(
            "team.log", paging("from", "before", "cursor", "4", "limit", "5", "level", "warning"));
    assertThat(before.getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("line 1", "line 2", "line 3");
    assertThat(before.getEntries().get(0).getSource()).isEqualTo("before/warning");
    // Fewer than asked for, looking back: the start.
    assertThat(before.getBefore()).isEmpty();
    Spotter.LogPage after = logs.page("team.log", paging("from", "after", "cursor", "20"));
    assertThat(after.getEntries()).isEmpty();
    assertThat(after.getAfter()).isEqualTo("20");
    assertThat(after.getBefore()).isEqualTo("20");
  }

  @Test
  void aCommandThatPrintsMoreThanAskedIsCutToTheLimit() throws Exception {
    Logs logs =
        logs(
            "for n in 1 2 3 4 5; do echo \"{\\\"message\\\": \\\"$n\\\", \\\"cursor\\\": \\\"$n\\\"}\"; done");
    assertThat(logs.page("team.log", paging("limit", "2")).getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("4", "5");
    assertThat(
            logs.page("team.log", paging("from", "after", "cursor", "0", "limit", "2"))
                .getEntries())
        .extracting(Spotter.LogEntry::getMessage)
        .containsExactly("1", "2");
  }

  @Test
  void aCommandThatFailsIsAProblem() {
    assertThat(
            catchThrowableOfType(
                Logs.Refused.class,
                () -> logs("echo broken >&2; exit 2").page("team.log", paging())))
        .satisfies(
            e -> {
              assertThat(e.status).isEqualTo(502);
              assertThat(e).hasMessage("log team.log's command failed: exit 2: broken");
            });
    assertThat(
            catchThrowableOfType(
                    Logs.Refused.class, () -> logs("true").page("team.nothing", paging()))
                .status)
        .isEqualTo(404);
    String folder = fixture.pack("gone", "pack: gone\nlogs:\n  - id: log\n    run: [./missing]\n");
    Logs gone = new Logs(commands, Packs.load(fixture.host, true).packs());
    assertThat(catchThrowableOfType(Logs.Refused.class, () -> gone.page("gone.log", paging())))
        .hasMessage("log gone.log's command: no such file: " + folder + "/missing");
  }

  @Test
  void aRunsLogIsSlicedByItsPlacesAndLevel() throws Exception {
    List<Spotter.LogEntry> all = new ArrayList<>();
    for (int n = 1; n <= 10; n++) {
      all.add(
          Spotter.LogEntry.newInstance()
              .setCursor(Integer.toString(n))
              .setMessage("step " + n)
              .setLevel(
                  n == 5 ? Spotter.LogLevel.LOG_LEVEL_ERROR : Spotter.LogLevel.LOG_LEVEL_INFO));
    }
    assertThat(Logs.slice(all, paging("limit", "3")).getEntries())
        .extracting(Spotter.LogEntry::getCursor)
        .containsExactly("8", "9", "10");
    assertThat(Logs.slice(all, paging("from", "after", "cursor", "8")).getEntries())
        .extracting(Spotter.LogEntry::getCursor)
        .containsExactly("9", "10");
    Spotter.LogPage back = Logs.slice(all, paging("from", "before", "cursor", "3"));
    assertThat(back.getEntries()).extracting(Spotter.LogEntry::getCursor).containsExactly("1", "2");
    assertThat(back.getBefore()).isEmpty();
    assertThat(Logs.slice(all, paging("level", "error")).getEntries())
        .extracting(Spotter.LogEntry::getCursor)
        .containsExactly("5");
  }
}
