package com.michaelgrundvig.frc.spotter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.api.Drive;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The drive's health from nvme-cli's reports, in the shapes its versions have printed. */
class DriveSourceTest {
  @TempDir Path dir;

  @Test
  void otherSpellingsAndShapesOfTheSameValuesRead() throws IOException {
    Fixture fixture = new Fixture(dir);
    fixture.write(
        DriveSource.SMART_LOG,
        "{\"critical_warning\":{\"value\":4},\"temperature\":\"41 °C (314 K)\","
            + "\"percentage_used\":\"7%\",\"unsafe_shutdowns\":\"340282366920938463463374607431768211455\","
            + "\"power_cycles\":12}");
    fixture.delete(DriveSource.ID_CTRL);
    Drive drive = new DriveSource(fixture.host).read().orElseThrow();
    assertThat(drive.device()).isEqualTo("/dev/nvme0");
    assertThat(drive.celsius()).isEqualTo(41);
    assertThat(drive.criticalWarning()).isEqualTo(4);
    assertThat(drive.percentUsed()).isZero(); // "7%" isn't a count
    assertThat(drive.unsafeShutdowns()).isEqualTo(Long.MAX_VALUE);
    assertThat(drive.powerCycles()).isEqualTo(12);
    assertThat(drive.mediaErrors()).isZero();
  }

  @Test
  void temperaturesReadInKelvinsOrCelsius() {
    assertThat(DriveSource.celsius(JsonValue.of(314L))).isEqualTo(40.9);
    assertThat(DriveSource.celsius(JsonValue.of(41L))).isEqualTo(41);
    assertThat(DriveSource.celsius(JsonValue.of("314 K"))).isEqualTo(40.9);
    assertThat(DriveSource.celsius(JsonValue.of("unknown"))).isNaN();
    assertThat(DriveSource.celsius(null)).isNaN();
    assertThat(DriveSource.count(Json.parse("{\"value\":\"3\"}"))).isEqualTo(3);
    assertThat(DriveSource.count(JsonValue.TRUE)).isZero();
  }
}
