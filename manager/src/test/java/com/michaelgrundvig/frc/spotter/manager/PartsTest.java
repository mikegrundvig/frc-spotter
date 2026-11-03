package com.michaelgrundvig.frc.spotter.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.manager.limits.Field;
import com.michaelgrundvig.frc.spotter.manager.limits.Reading;
import com.michaelgrundvig.frc.spotter.manager.packs.TeamPacks;
import com.michaelgrundvig.frc.spotter.manager.requests.AgentClient;
import com.michaelgrundvig.frc.spotter.manager.requests.Requests;
import com.michaelgrundvig.frc.spotter.manager.stream.ClockMap;
import com.michaelgrundvig.frc.spotter.manager.stream.Exchange;
import com.michaelgrundvig.frc.spotter.manager.stream.Table;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The manager's smaller parts: its clock map, its buffers, its settings and its addresses. */
class PartsTest {
  static final long SECOND = 1_000_000_000L;

  @Test
  void theAgentsClockIsMappedByTheLeastOffsetSeen() {
    ClockMap clock = new ClockMap();
    // Sent at 100 s on the agent's clock, heard at 5,000.020 s on the robot's: 20 ms on the way.
    clock.heard(100 * SECOND, 5_000 * SECOND + 20_000_000);
    assertThat(clock.robot(100 * SECOND)).isEqualTo(5_000 * SECOND + 20_000_000);
    // A quicker trip: the offset is the least.
    clock.heard(101 * SECOND, 5_001 * SECOND + 2_000_000);
    assertThat(clock.robot(101 * SECOND)).isEqualTo(5_001 * SECOND + 2_000_000);
    // A slower one changes nothing.
    clock.heard(102 * SECOND, 5_002 * SECOND + 90_000_000);
    assertThat(clock.robot(102 * SECOND)).isEqualTo(5_002 * SECOND + 2_000_000);
  }

  @Test
  void theLeastOffsetFollowsDriftWindowByWindow() {
    ClockMap clock = new ClockMap();
    clock.heard(0, 1_000 * SECOND);
    // Ten seconds on, the clocks have drifted 5 ms apart: the old window still counts...
    clock.heard(10 * SECOND, 1_010 * SECOND + 5_000_000);
    assertThat(clock.robot(10 * SECOND)).isEqualTo(1_010 * SECOND);
    // ...until a window later, when only what was seen since does.
    clock.heard(20 * SECOND, 1_020 * SECOND + 10_000_000);
    assertThat(clock.robot(20 * SECOND)).isEqualTo(1_020 * SECOND + 5_000_000);
    // A new connection starts afresh.
    clock.reset();
    clock.heard(0, 7 * SECOND);
    assertThat(clock.robot(SECOND)).isEqualTo(8 * SECOND);
  }

  @Test
  void theLoopTakesTheNewestPublishedBufferAndTheWriterNeverTouchesItsOwn() {
    Exchange exchange = new Exchange("vision-front");
    Table first = exchange.front();
    assertThat(exchange.take()).isFalse();
    exchange.back().changes = 1;
    exchange.publish();
    exchange.back().changes = 2;
    exchange.publish();
    assertThat(exchange.take()).isTrue();
    assertThat(exchange.front().changes).isEqualTo(2);
    assertThat(exchange.front()).isNotSameAs(first);
    assertThat(exchange.take()).isFalse();
    assertThat(exchange.back()).isNotSameAs(exchange.front());
    exchange.back().changes = 3;
    exchange.publish();
    assertThat(exchange.back()).isNotSameAs(exchange.front());
    assertThat(exchange.take()).isTrue();
    assertThat(exchange.front().changes).isEqualTo(3);
  }

  @Test
  void aReaderNeverSeesABufferBeingWritten() throws Exception {
    Exchange exchange = new Exchange("vision-front");
    AtomicBoolean done = new AtomicBoolean();
    AtomicReference<String> torn = new AtomicReference<>("");
    Thread writer =
        new Thread(
            () -> {
              for (long i = 1; i <= 2_000_000; i++) {
                Table back = exchange.back();
                back.changes = i;
                back.heardNanos = i;
                exchange.publish();
              }
              done.set(true);
            });
    writer.start();
    long last = 0;
    while (!done.get() || exchange.take()) {
      exchange.take();
      Table front = exchange.front();
      long changes = front.changes;
      long heard = front.heardNanos;
      if (changes != heard || changes < last) {
        torn.set(changes + " beside " + heard + " after " + last);
        break;
      }
      last = changes;
    }
    writer.join();
    exchange.take();
    assertThat(torn.get()).isEmpty();
    assertThat(exchange.front().changes).isEqualTo(2_000_000);
  }

  @Test
  void aTableCopiesEverythingAndRebuildsItsValuesOnlyForANewDescription() {
    Spotter.Description description = ZeroAllocationTest.description();
    Table writer = new Table();
    writer.describe(description, Field.of(description, java.util.Map.of()));
    writer.values[0].kind = Reading.Kind.NUMBER;
    writer.values[0].number = 12;
    writer.changes = 4;
    Table reader = new Table();
    reader.copyFrom(writer);
    assertThat(reader.description).isSameAs(description);
    assertThat(reader.values).hasSize(6);
    assertThat(reader.values[0].id()).isEqualTo("vision.health.fps");
    assertThat(reader.values[0].number).isEqualTo(12);
    assertThat(reader.changes).isEqualTo(4);
    Reading kept = reader.values[0];
    writer.values[0].number = 13;
    reader.copyFrom(writer);
    assertThat(reader.values[0]).isSameAs(kept);
    assertThat(kept.number).isEqualTo(13);
  }

  @Test
  void settingsHaveTheDesignsDefaults() {
    assertThat(Settings.DEFAULTS.heartbeat()).isEqualTo(Duration.ofMillis(250));
    assertThat(Settings.DEFAULTS.missing()).isEqualTo(Duration.ofSeconds(1));
    assertThat(Settings.DEFAULTS.backoff()).isEqualTo(Duration.ofSeconds(5));
    assertThat(Settings.DEFAULTS.limits()).isEmpty();
    Settings changed =
        Settings.DEFAULTS
            .withHeartbeat(Duration.ofMillis(100))
            .withMissing(Duration.ofSeconds(2))
            .withBackoff(Duration.ofSeconds(1))
            .withLimits("a.b.c", Limits.NONE)
            .withLimits("d.e.f", Limits.NONE);
    assertThat(changed.heartbeat()).isEqualTo(Duration.ofMillis(100));
    assertThat(changed.missing()).isEqualTo(Duration.ofSeconds(2));
    assertThat(changed.backoff()).isEqualTo(Duration.ofSeconds(1));
    assertThat(changed.limits()).containsOnlyKeys("a.b.c", "d.e.f");
  }

  @Test
  void settingsThatCantWorkAreRefused() {
    assertThatThrownBy(() -> Settings.DEFAULTS.withMissing(Duration.ofMillis(250)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("longer than its heartbeat");
    assertThatThrownBy(() -> Settings.DEFAULTS.withHeartbeat(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Settings.DEFAULTS.withBackoff(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void limitsAreCopiedAsGivenAndAsRead() {
    Spotter.Limit warn = Spotter.Limit.newInstance().setBelow(30);
    Limits limits = new Limits(warn, Spotter.Limit.newInstance());
    warn.setBelow(10);
    assertThat(limits.warn().getBelow()).isEqualTo(30);
    limits.warn().setBelow(5);
    assertThat(limits.warn().getBelow()).isEqualTo(30);
    assertThat(limits)
        .isEqualTo(
            new Limits(Spotter.Limit.newInstance().setBelow(30), Spotter.Limit.newInstance()));
  }

  @Test
  void anAgentsAddressIsAHostAndMaybeAPort() {
    assertThat(AgentClient.address("10.12.34.11"))
        .isEqualTo(new AgentClient.Address("10.12.34.11", 5808, "10.12.34.11:5808"));
    assertThat(AgentClient.address("vision-front:5809"))
        .isEqualTo(new AgentClient.Address("vision-front", 5809, "vision-front:5809"));
    assertThat(AgentClient.address("fd00::11"))
        .isEqualTo(new AgentClient.Address("fd00::11", 5808, "[fd00::11]:5808"));
    assertThat(AgentClient.address("[fd00::11]:6000"))
        .isEqualTo(new AgentClient.Address("fd00::11", 6000, "[fd00::11]:6000"));
    assertThat(AgentClient.address("[fd00::11]"))
        .isEqualTo(new AgentClient.Address("fd00::11", 5808, "[fd00::11]:5808"));
    assertThat(AgentClient.url(AgentClient.address("fd00::11"), "/v2/describe").toString())
        .isEqualTo("http://[fd00::11]:5808/v2/describe");
    for (String wrong : List.of("", "vision front", "a:b", "user@host", "host/path")) {
      assertThatThrownBy(() -> AgentClient.address(wrong))
          .as(wrong)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("an agent's address");
    }
  }

  @Test
  void aLogQueryIsAPagesQuery() {
    assertThat(Requests.query(LogQuery.latest(100))).isEqualTo("?from=latest&limit=100");
    assertThat(Requests.query(LogQuery.before("a b/c", 5).atLeast("warning")))
        .isEqualTo("?from=before&limit=5&cursor=a+b%2Fc&level=warning");
    assertThat(Requests.query(LogQuery.after("20", 1))).isEqualTo("?from=after&limit=1&cursor=20");
    assertThatThrownBy(() -> new LogQuery("sideways", "", 5, ""))
        .hasMessage("from is latest, before or after, not sideways");
    assertThatThrownBy(() -> LogQuery.before("", 5)).hasMessage("from before pages from a cursor");
    assertThatThrownBy(() -> LogQuery.latest(0)).hasMessage("limit is 1 to 1000, not 0");
    assertThatThrownBy(() -> LogQuery.latest(1001)).hasMessage("limit is 1 to 1000, not 1001");
  }

  @Test
  void theTeamsPacksAreBundledAndTheirHashIsTheFolders(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    java.nio.file.Path packs = dir.resolve("packs");
    java.nio.file.Files.createDirectories(packs.resolve("team/scripts"));
    java.nio.file.Files.writeString(packs.resolve("team/pack.yaml"), "pack: team\n");
    java.nio.file.Files.writeString(packs.resolve("team/scripts/check"), "#!/bin/sh\n");
    java.nio.file.Files.setPosixFilePermissions(
        packs.resolve("team/scripts/check"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    TeamPacks team = java.util.Objects.requireNonNull(TeamPacks.of(packs));
    assertThat(team.hash()).isEqualTo(com.michaelgrundvig.frc.spotter.protocol.PackHash.of(packs));
    Spotter.PackBundle sent =
        us.hebi.quickbuf.ProtoMessage.mergeFrom(Spotter.PackBundle.newInstance(), team.bundle());
    assertThat(sent.getFiles())
        .extracting(Spotter.PackFile::getPath, Spotter.PackFile::getExecutable)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("team/pack.yaml", false),
            org.assertj.core.groups.Tuple.tuple("team/scripts/check", true));
    // A board that names none gets none: the empty bundle, whose hash is the empty folder's.
    TeamPacks none = team.select(List.of());
    assertThat(none.hash()).isEmpty();
    assertThat(none.bundle()).isEmpty();
    assertThat(team.select(List.of("team"))).isSameAs(team);
    assertThat(team.select(List.of("other")).hash()).isEmpty();
    assertThat(TeamPacks.of(dir.resolve("empty")).names()).isEmpty();
  }

  @Test
  void limitsAreMadeInThePacksOwnGrammar() {
    assertThat(Limits.below(30)).isEqualTo(Spotter.Limit.newInstance().setBelow(30));
    assertThat(Limits.above(80)).isEqualTo(Spotter.Limit.newInstance().setAbove(80));
    assertThat(Limits.equalTo("calibrating").getEquals().getText()).isEqualTo("calibrating");
    assertThat(Limits.equalTo(2).getEquals().getNumber()).isEqualTo(2);
    assertThat(Limits.equalTo(true).getEquals().getFlag()).isTrue();
    assertThat(Limits.notEqualTo("tracking").getNotEquals().getText()).isEqualTo("tracking");
    assertThat(Limits.notEqualTo(0).getNotEquals().getNumber()).isZero();
    assertThat(Limits.notEqualTo(true).getNotEquals().getFlag()).isTrue();
    assertThat(Limits.missing()).isEqualTo(Spotter.Limit.newInstance().setMissing(true));
    assertThat(Limits.below(5).setMissing(true))
        .isEqualTo(Spotter.Limit.newInstance().setBelow(5).setMissing(true));
    assertThat(Limits.none()).isEqualTo(Spotter.Limit.newInstance());
    // Each a new message: changing one changes no other.
    assertThat(Limits.none()).isNotSameAs(Limits.none());
  }

  @Test
  void anAddressGivenTwiceIsRefused() {
    assertThatThrownBy(
            () ->
                new Manager(
                    Boards.robot(),
                    List.of(
                        new BoardDefinition("10.12.34.11"), new BoardDefinition(" 10.12.34.11"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("two boards have the address 10.12.34.11");
  }

  @Test
  void aBoardsDefinitionNamesEachPackOnceByItsFolderAndServesAnyManager() {
    BoardDefinition front = new BoardDefinition(" vision-front ", "debian", "detector");
    assertThat(front.address()).isEqualTo("vision-front");
    assertThat(front.packs()).containsExactly("debian", "detector");
    assertThat(front).isEqualTo(new BoardDefinition("vision-front", List.of("debian", "detector")));
    assertThat(new BoardDefinition("vision-front").packs()).isEmpty();
    assertThatThrownBy(() -> new BoardDefinition("vision-front", "debian", "debian"))
        .hasMessage("the pack debian is named twice for vision-front");
    for (String name : new String[] {"", "a/b", "a\\b", ".hidden"}) {
      assertThatThrownBy(() -> new BoardDefinition("vision-front", name))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("a pack's name is its folder's");
    }
    assertThatThrownBy(
            () -> new Manager(Boards.robot(), List.of(new BoardDefinition("not a host"))))
        .isInstanceOf(IllegalArgumentException.class);

    // One definition, as a constant would be, for two managers: each makes its own board.
    try (Manager one = new Manager(Boards.robot(), List.of(front));
        Manager two = new Manager(Boards.robot(), List.of(front))) {
      Board board = one.board("vision-front");
      assertThat(board.definition()).isSameAs(front);
      assertThat(board.address()).isEqualTo("vision-front");
      assertThat(board.name()).isEqualTo("vision-front");
      assertThat(one.board(" vision-front ")).isSameAs(board);
      assertThat(two.board("vision-front")).isNotSameAs(board);
      assertThatThrownBy(() -> one.board("vision-back"))
          .hasMessage("the manager has no board vision-back, only vision-front");
    }
  }

  @Test
  void whyAFailureHappenedIsItsMessageOrItsKind() {
    assertThat(AgentClient.why(new java.io.IOException(" Connection refused ")))
        .isEqualTo("Connection refused");
    assertThat(AgentClient.why(new java.io.EOFException())).isEqualTo("EOFException");
    // A connection's failures, in plain words.
    assertThat(AgentClient.why(new java.net.UnknownHostException("vision-frnt")))
        .isEqualTo("its name doesn't resolve (vision-frnt)");
    assertThat(AgentClient.why(new java.net.ConnectException("Connection refused")))
        .isEqualTo("connection refused: is frc-spotter running on it?");
    assertThat(AgentClient.why(new java.net.NoRouteToHostException("No route to host")))
        .isEqualTo("no route to it: is it on the robot's network?");
    assertThat(AgentClient.why(new java.net.SocketTimeoutException("Connect timed out")))
        .isEqualTo("no answer in time: is it on the robot's network?");
  }
}
