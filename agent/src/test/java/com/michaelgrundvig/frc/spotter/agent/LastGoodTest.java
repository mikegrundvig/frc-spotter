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
    // Its controller named, by its team: only then does it take pushes, and load pushed packs.
    fixture.config("{\"team\": 1234}");
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
    // The first start loads them, and never stays up: it crashes, or is killed out of memory.
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

  @Test
  void aCleanStopIsntAStartThatDidntStayUp() throws Exception {
    fixture.configuration();
    // Restarted for a change, a moment later: systemd stops it, and its hook says so.
    LastGood.stopped(fixture.host);
    assertThat(fixture.configuration().packs().packs()).hasSize(1);
    LastGood.stopped(fixture.host);
    assertThat(fixture.configuration().packs().packs()).hasSize(1);
  }

  @Test
  void aBootCountsOnlyTheSecondInARowWhoseStartDidntStayUp() throws Exception {
    fixture.configuration();
    // Switched off within the minute, as a robot often is: the next boot loads them.
    newBoot("2");
    assertThat(fixture.configuration().packs().packs()).hasSize(1);
    // And again, within the minute: two boots in a row, as a pack that reboots the board makes.
    newBoot("3");
    Configuration third = fixture.configuration();
    assertThat(third.packs().packs()).isEmpty();
    assertThat(third.problems()).containsExactly(LastGood.setAside());
    // A boot that stays up starts afresh.
    LastGood.healthy(fixture.host);
    newBoot("4");
    assertThat(fixture.configuration().packs().packs()).hasSize(1);
  }

  private void newBoot(String id) {
    fixture.write(IdentitySource.BOOT_ID, "3c1e6a2e-0000-4000-8000-00000000000" + id + "\n");
  }
}
