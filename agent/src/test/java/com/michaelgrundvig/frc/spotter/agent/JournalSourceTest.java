package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.JournalEntry;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.JournalSummary;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The journal, read in bounded pieces, and this boot's trouble counted. */
class JournalSourceTest {
  static final String BOOT = "3c1e6a2e-6f6c-4a1d-9a53-8c1f0c7b8e21";

  @TempDir Path dir;
  Fixture fixture;
  JournalSource journal;

  @BeforeEach
  void aCoprocessor() throws IOException {
    fixture = new Fixture(dir);
    journal = new JournalSource(fixture.host, Duration.ofSeconds(2));
  }

  @Test
  void theCountPicksUpWhereItLeftOff() throws IOException {
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"), Fixture.lines("journal-this-boot.json"));
    JournalSummary first = journal.summary(BOOT);
    assertThat(first.count("usb")).isEqualTo(1);
    assertThat(fixture.commands.ran().get(0)).doesNotContain("--after-cursor=s=a1;i=108");

    // The next count asks only for what came after, and adds it.
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"),
        List.of(
            "{\"__CURSOR\":\"s=a1;i=109\",\"_BOOT_ID\":\"3c1e6a2e6f6c4a1d9a538c1f0c7b8e21\","
                + "\"_TRANSPORT\":\"kernel\",\"PRIORITY\":\"4\",\"MESSAGE\":\"usb 7-1: reset"
                + " SuperSpeed USB device number 3 using xhci-hcd\"}"));
    JournalSummary second = journal.summary(BOOT);
    assertThat(fixture.commands.ran().get(1)).contains("--after-cursor=s=a1;i=108");
    assertThat(second.count("usb")).isEqualTo(2);
    assertThat(second.latest()).hasSize(JournalSource.LATEST);
    assertThat(second.latest().get(2).message()).startsWith("usb 7-1: reset");
    assertThat(second.counts().keySet()).containsExactlyElementsOf(JournalSummary.CATEGORIES);
  }

  @Test
  void aCountCutShortLeavesTheRestForNextTime() throws IOException {
    List<String> lines = Fixture.lines("journal-this-boot.json");
    fixture.commands.answer(
        List.of("journalctl", "-b", "-o"),
        new Commands.Output(-1, lines.subList(0, 3), true, false));
    JournalSummary summary = journal.summary(BOOT);
    // The last line read may be cut off, so it's read again next time.
    assertThat(summary.count("usb")).isEqualTo(1);
    assertThat(summary.count("uvc")).isZero();
  }

  @Test
  void aPageIsTheLatestOrWhatComesAfterACursor() throws IOException {
    fixture.commands.answer(List.of("journalctl", "-o"), Fixture.lines("journal-this-boot.json"));
    JournalPage latest =
        journal.page(JournalSource.Position.LATEST, 3, List.of("photonvision.service"), 3);
    assertThat(fixture.commands.ran().get(0))
        .contains(
            "--priority=3",
            "--lines=3",
            "_SYSTEMD_UNIT=photonvision.service",
            "+",
            "UNIT=photonvision.service")
        .doesNotContain("--after-cursor=", "_TRANSPORT=kernel");
    assertThat(latest.entries()).hasSize(3);
    assertThat(latest.more()).isTrue();
    assertThat(latest.cursor()).isEqualTo("s=a1;i=103");

    JournalPage after =
        journal.page(JournalSource.Position.after("s=a1;i=103"), -1, AgentApi.JOURNAL_UNITS, 100);
    assertThat(fixture.commands.ran().get(1))
        .contains(
            "--after-cursor=s=a1;i=103", "_TRANSPORT=kernel", "UNIT=frc-coprocessor-agent.service");
    assertThat(after.entries()).hasSize(8);
    assertThat(after.more()).isFalse();

    fixture.commands.answer(List.of("journalctl", "-o"), List.of());
    JournalPage none =
        journal.page(JournalSource.Position.after("s=a1;i=108"), -1, List.of("kernel"), 100);
    assertThat(none.entries()).isEmpty();
    assertThat(none.cursor()).isEqualTo("s=a1;i=108");

    fixture.commands.answer(
        List.of("journalctl", "-o"), new Commands.Output(-1, List.of(), false, true));
    assertThatThrownBy(() -> journal.page(JournalSource.Position.LATEST, -1, List.of("kernel"), 10))
        .hasMessage("journalctl timed out");
  }

  @Test
  void olderPagesComeBeforeACursorAndAWholeBootFromItsStart() throws IOException {
    List<String> lines = Fixture.lines("journal-this-boot.json");
    // journalctl --reverse prints newest first.
    List<String> newestFirst = new ArrayList<>(lines.subList(0, 4));
    Collections.reverse(newestFirst);
    fixture.commands.answer(List.of("journalctl", "-o"), newestFirst);
    JournalPage older =
        journal.page(JournalSource.Position.before("s=a1;i=105"), -1, List.of("kernel"), 3);
    assertThat(fixture.commands.ran().get(0)).contains("--reverse", "--after-cursor=s=a1;i=105");
    assertThat(older.entries())
        .extracting(JournalEntry::cursor)
        .containsExactly("s=a1;i=102", "s=a1;i=103", "s=a1;i=104");
    assertThat(older.cursor()).isEqualTo("s=a1;i=102");
    assertThat(older.more()).isTrue();

    fixture.commands.answer(List.of("journalctl", "-o"), List.of());
    JournalPage oldest =
        journal.page(JournalSource.Position.before("s=a1;i=101"), -1, List.of("kernel"), 3);
    assertThat(oldest.entries()).isEmpty();
    assertThat(oldest.cursor()).isEqualTo("s=a1;i=101");
    assertThat(oldest.more()).isFalse();

    fixture.commands.answer(List.of("journalctl", "-o"), lines);
    JournalPage start = journal.page(JournalSource.Position.BOOT, -1, List.of("kernel"), 2);
    assertThat(fixture.commands.ran().get(2)).contains("--boot").doesNotContain("--lines=2");
    assertThat(start.entries())
        .extracting(JournalEntry::cursor)
        .containsExactly("s=a1;i=101", "s=a1;i=102");
    assertThat(start.cursor()).isEqualTo("s=a1;i=102");
    assertThat(start.more()).isTrue();
  }

  @Test
  void thePreviousBootEndedCleanlyOrNotOrLeftNoJournal() throws IOException {
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), Fixture.lines("journal-previous-cut.json"));
    assertThat(journal.previousBootClean()).isFalse();
    // Read once: it can't change.
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), Fixture.lines("journal-previous-clean.json"));
    assertThat(journal.previousBootClean()).isFalse();
    assertThat(new JournalSource(fixture.host, Duration.ofSeconds(1)).previousBootClean()).isTrue();

    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), new Commands.Output(1, List.of(), false, false));
    assertThat(new JournalSource(fixture.host, Duration.ofSeconds(1)).previousBootClean()).isNull();
    fixture.commands.answer(
        List.of("journalctl", "-b", "-1"), new Commands.Output(-1, List.of(), false, true));
    assertThatThrownBy(
            () -> new JournalSource(fixture.host, Duration.ofSeconds(1)).previousBootClean())
        .isInstanceOf(IOException.class);
  }

  @Test
  void anEntryIsReadFromJournalctlsJson() {
    JournalEntry entry =
        Objects.requireNonNull(
            JournalSource.entry(
                "{\"__CURSOR\":\"c\",\"__REALTIME_TIMESTAMP\":\"17\",\"__MONOTONIC_TIMESTAMP\":\"5\","
                    + "\"_BOOT_ID\":\"b\",\"PRIORITY\":\"2\",\"_SYSTEMD_UNIT\":\"u.service\","
                    + "\"SYSLOG_IDENTIFIER\":[\"first\",\"second\"],\"MESSAGE\":\""
                    + "x".repeat(AgentApi.MAX_MESSAGE + 10)
                    + "\"}"));
    assertThat(entry.cursor()).isEqualTo("c");
    assertThat(entry.realtimeMicros()).isEqualTo(17);
    assertThat(entry.monotonicMicros()).isEqualTo(5);
    assertThat(entry.priority()).isEqualTo(2);
    assertThat(entry.unit()).isEqualTo("u.service");
    assertThat(entry.identifier()).isEqualTo("first");
    assertThat(entry.message()).hasSize(AgentApi.MAX_MESSAGE);
    assertThat(entry.category()).isEqualTo("error");
    assertThat(JournalSource.entry("[1]")).isNull();
    JournalEntry bare = Objects.requireNonNull(JournalSource.entry("{\"PRIORITY\":\"x\"}"));
    assertThat(bare.priority()).isEqualTo(6);
    assertThat(bare.category()).isEmpty();
  }

  @Test
  void eachKindOfTroubleIsCounted() {
    assertThat(JournalSource.category(true, 3, "Out of memory: Killed process 12 (java)"))
        .isEqualTo("oom");
    assertThat(JournalSource.category(true, 6, "oom_reaper: reaped process 12")).isEqualTo("oom");
    assertThat(JournalSource.category(true, 4, "cpu4: throttling, clock lowered"))
        .isEqualTo("thermal");
    assertThat(JournalSource.category(true, 3, "uvcvideo: Failed to resubmit video URB (-19)"))
        .isEqualTo("uvc");
    assertThat(JournalSource.category(true, 6, "uvcvideo: Found UVC 1.00 device")).isEmpty();
    assertThat(JournalSource.category(true, 6, "usb 3-1: USB disconnect, device number 4"))
        .isEqualTo("usb");
    assertThat(JournalSource.category(true, 6, "usb 3-1: new high-speed USB device number 4"))
        .isEmpty();
    assertThat(JournalSource.category(true, 4, "xhci-hcd xhci-hcd.0.auto: port 1 over-current"))
        .isEqualTo("usb");
    assertThat(
            JournalSource.category(true, 3, "nvme nvme0: I/O 12 QID 3 timeout, reset controller"))
        .isEqualTo("filesystem");
    assertThat(
            JournalSource.category(true, 2, "EXT4-fs (nvme0n1p3): Remounting filesystem read-only"))
        .isEqualTo("filesystem");
    assertThat(JournalSource.category(false, 3, "thermal zone is fine, says java"))
        .isEqualTo("error");
    assertThat(JournalSource.category(false, 4, "a warning")).isEmpty();
  }

  @Test
  void requestsAreCheckedBeforeTheyReachJournalctl() {
    assertThat(JournalSource.CURSOR.matcher("s=0f;i=1a2;b=3c;m=4d;t=5e;x=6f").matches()).isTrue();
    assertThat(JournalSource.CURSOR.matcher("s=1 --since=x").matches()).isFalse();
    assertThat(JournalSource.matches(List.of("kernel", "photonvision.service")))
        .containsExactly(
            "_TRANSPORT=kernel",
            "+",
            "_SYSTEMD_UNIT=photonvision.service",
            "+",
            "UNIT=photonvision.service");
    // Which units are served is the server's to check; a name that isn't a unit's never reaches
    // journalctl.
    assertThatThrownBy(() -> JournalSource.matches(List.of("--since=x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JournalSource.matches(List.of("a b.service")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPageReadsNoMoreThanItsLimitAllows() throws IOException {
    Fixture small = new Fixture(dir.resolve("small"), new Limits(256 * 1024, 700, 4 * 1024 * 1024));
    small.commands.answer(List.of("journalctl", "-o"), Fixture.lines("journal-this-boot.json"));
    JournalPage page =
        new JournalSource(small.host, Duration.ofSeconds(2))
            .page(JournalSource.Position.LATEST, -1, AgentApi.JOURNAL_UNITS, 100);
    // Each line is about 300 characters: two fit in 700, and the rest wait for the next page.
    assertThat(page.entries()).hasSize(2);
    assertThat(page.more()).isTrue();
  }
}
