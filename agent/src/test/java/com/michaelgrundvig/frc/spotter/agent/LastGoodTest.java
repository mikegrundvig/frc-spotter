package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.PackHash;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The last good start: pushed packs load behind a marker, which a start that stays up takes away; a
 * start that finds it still there sets them aside, serves, and takes the next push; and the start
 * after a good one, or after a push, tries them again.
 */
class LastGoodTest {
  @TempDir Path dir;
  Fixture fixture;

  @BeforeEach
  void aBoardWithPushedPacks() throws Exception {
    fixture = new Fixture(dir);
    fixture.pushed("team", "pack: team\n");
  }

  private boolean marked() {
    return Files.exists(fixture.path(LastGood.MARKER));
  }

  @Test
  void aStartWithPushedPacksLeavesItsMarkerUntilItsStayedUp() {
    Configuration first = fixture.configuration();
    assertThat(first.packs().packs()).extracting(Pack::name).containsExactly("team");
    assertThat(first.problems()).isEmpty();
    assertThat(marked()).isTrue();
    LastGood.healthy(fixture.host);
    assertThat(marked()).isFalse();
    assertThat(fixture.configuration().packs().packs()).hasSize(1);
  }

  @Test
  void aStartAfterOneThatDidntStayUpSetsItsPushedPacksAsideAndStillTakesPushes() throws Exception {
    // The first start loads them, and never stays up: a crash, or a reboot.
    fixture.configuration();
    Configuration second = fixture.configuration();
    assertThat(second.packs().packs()).isEmpty();
    assertThat(second.problems()).containsExactly(LastGood.setAside());
    // What's on its disk is still what it says it has, so it isn't pushed the same again.
    assertThat(second.packs().pushedHash())
        .isEqualTo(PackHash.of(fixture.path(Packs.PUSHED)))
        .isNotEmpty();
    assertThat(second.acceptsPushes()).isTrue();

    // That start stays up: the next one tries them again.
    LastGood.healthy(fixture.host);
    assertThat(fixture.configuration().packs().packs()).hasSize(1);
  }

  @Test
  void aPushGivesItsPacksAFreshTry() throws Exception {
    fixture.configuration();
    assertThat(marked()).isTrue();
    Push push = new Push(fixture.host);
    Path body = push.bundle();
    Files.write(
        body,
        Spotter.PackBundle.newInstance()
            .addFiles(
                Spotter.PackFile.newInstance()
                    .setPath("team/pack.yaml")
                    .setContent("pack: team\nversion: 2.0.0\n".getBytes(StandardCharsets.UTF_8)))
            .toByteArray());
    push.apply(body);
    assertThat(marked()).isFalse();
    assertThat(fixture.configuration().packs().packs())
        .extracting(Pack::version)
        .containsExactly("2.0.0");
  }

  @Test
  void aBoardWithoutPushedPacksOrRefusingThemLeavesNoMarker(@TempDir Path other) throws Exception {
    Fixture none = new Fixture(other);
    none.pack("vision", "pack: vision\n");
    none.configuration();
    assertThat(Files.exists(none.path(LastGood.MARKER))).isFalse();

    fixture.config("{\"acceptPushes\": false}");
    fixture.configuration();
    assertThat(marked()).isFalse();
  }

  @Test
  void anAgentCountsItsStartGoodOnceItsBeenUp() throws Exception {
    try (Agent agent = fixture.agent()) {
      assertThat(marked()).isTrue();
      agent.start();
      // Not yet: a minute from now, on the JDK's own timer, which a test doesn't wait for.
      assertThat(marked()).isTrue();
    }
  }
}
