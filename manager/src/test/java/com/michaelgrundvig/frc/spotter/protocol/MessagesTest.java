package com.michaelgrundvig.frc.spotter.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.michaelgrundvig.frc.spotter.protocol.Spotter.Description;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.Event;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.FieldDeclaration;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.FieldType;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.FieldValue;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.Heartbeat;
import com.michaelgrundvig.frc.spotter.protocol.Spotter.Values;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.JsonSink;
import us.hebi.quickbuf.JsonSource;
import us.hebi.quickbuf.ProtoSink;
import us.hebi.quickbuf.ProtoSource;

/** spotter.proto's classes, as QuickBuffers generates them: protobuf on the wire, and its JSON. */
class MessagesTest {
  @Test
  void eventsReadBackFromTheirDelimitedFormInOrder() throws Exception {
    Description description = Description.newInstance().setRevision(7).setAgentVersion("0.4.0");
    description.addValues(
        FieldDeclaration.newInstance()
            .setId("debian.memory.available")
            .setType(FieldType.FIELD_TYPE_NUMBER)
            .setUnit("%"));
    Values values = Values.newInstance().setRevision(7).setComplete(true).setTimeNanos(42);
    values.addValues(FieldValue.newInstance().setIndex(0).setNumber(61.5));
    values.addValues(FieldValue.newInstance().setIndex(1).setUnavailable("timed out after 5 s"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ProtoSink sink = ProtoSink.newInstance(out);
    Event.newInstance().setDescribed(description).writeDelimitedTo(sink);
    Event.newInstance().setValues(values).writeDelimitedTo(sink);
    Event.newInstance()
        .setHeartbeat(Heartbeat.newInstance().setTimeNanos(43))
        .writeDelimitedTo(sink);

    ProtoSource source = ProtoSource.newInstance(new ByteArrayInputStream(out.toByteArray()));
    Event first = Event.newInstance().mergeDelimitedFrom(source);
    assertThat(first.hasDescribed()).isTrue();
    assertThat(first.getDescribed()).isEqualTo(description);
    Event second = Event.newInstance().mergeDelimitedFrom(source);
    assertThat(second.getValues().getValues().get(1).getUnavailable())
        .isEqualTo("timed out after 5 s");
    assertThat(second.getValues().getValues().get(0).hasNumber()).isTrue();
    Event third = Event.newInstance().mergeDelimitedFrom(source);
    assertThat(third.getHeartbeat().getTimeNanos()).isEqualTo(43);
  }

  @Test
  void itsJsonFormIsReadable() throws Exception {
    Values values = Values.newInstance().setRevision(3).setComplete(true);
    values.addValues(FieldValue.newInstance().setIndex(2).setFlag(true));
    JsonSink sink = JsonSink.newInstance();
    values.writeTo(sink);
    String json = sink.getChars().toString();
    assertThat(json).contains("\"revision\":3", "\"complete\":true", "\"flag\":true");
    Values read =
        Values.newInstance()
            .mergeFrom(JsonSource.newInstance(json.getBytes(StandardCharsets.UTF_8)));
    assertThat(read).isEqualTo(values);
  }
}
