package com.michaelgrundvig.frc.spotter.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

/**
 * The check before parsing: its table is spotter.proto's own, real messages pass, and a message
 * that would make QuickBuffers allocate past its size (a string or bytes declaring more than is
 * left, at any depth; more list elements than a board ever sends) is refused before it's parsed.
 */
class WireCheckTest {
  /** A varint's bytes. */
  private static void varint(ByteArrayOutputStream out, long value) {
    while ((value & ~0x7fL) != 0) {
      out.write((int) ((value & 0x7f) | 0x80));
      value >>>= 7;
    }
    out.write((int) value);
  }

  /** A length-delimited field: its tag, its length, then {@code body} (which may be short). */
  private static byte[] field(int number, long declared, byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    varint(out, (long) number << 3 | 2);
    varint(out, declared);
    out.writeBytes(body);
    return out.toByteArray();
  }

  private static byte[] field(int number, byte[] body) {
    return field(number, body.length, body);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  @Test
  void itsTableIsSpotterProtosOwn() throws Exception {
    DescriptorProtos.FileDescriptorProto file =
        DescriptorProtos.FileDescriptorProto.parseFrom(Spotter.getDescriptor().toProtoBytes());
    Map<String, DescriptorProtos.DescriptorProto> messages = new HashMap<>();
    for (DescriptorProtos.DescriptorProto message : file.getMessageTypeList()) {
      collect(file.getPackage() + "." + message.getName(), message, messages);
    }
    Map<String, WireCheck.Type> table = WireCheck.types();
    assertThat(table.keySet()).containsExactlyInAnyOrderElementsOf(messages.keySet());
    messages.forEach(
        (name, message) -> {
          WireCheck.Type type = java.util.Objects.requireNonNull(table.get(name), name);
          Map<Integer, DescriptorProtos.FieldDescriptorProto> fields = new HashMap<>();
          for (DescriptorProtos.FieldDescriptorProto field : message.getFieldList()) {
            fields.put(field.getNumber(), field);
            boolean isMessage =
                field.getType() == DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE;
            WireCheck.Type held = type.message(field.getNumber());
            if (isMessage) {
              assertThat(held).as(name + "'s field " + field.getName()).isNotNull();
              assertThat("." + java.util.Objects.requireNonNull(held).name())
                  .isEqualTo(field.getTypeName());
            } else {
              assertThat(held).as(name + "'s field " + field.getName()).isNull();
            }
            assertThat(type.repeats(field.getNumber()))
                .as(name + "'s field " + field.getName() + " repeats")
                .isEqualTo(
                    field.getLabel() == DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED);
          }
          // And it says nothing of fields the proto hasn't.
          for (int number = 0; number < type.fields(); number++) {
            if (type.message(number) != null || type.repeats(number)) {
              assertThat(fields).as(name + "'s field " + number).containsKey(number);
            }
          }
        });
  }

  private static void collect(
      String name,
      DescriptorProtos.DescriptorProto message,
      Map<String, DescriptorProtos.DescriptorProto> into) {
    into.put(name, message);
    for (DescriptorProtos.DescriptorProto nested : message.getNestedTypeList()) {
      collect(name + "." + nested.getName(), nested, into);
    }
  }

  /** A description with everything set: what a board sends first. */
  static Spotter.Description description() {
    Spotter.Description description =
        Spotter.Description.newInstance().setRevision(7).setAgentVersion("0.4.0");
    description
        .getMutableIdentity()
        .setHostname("vision-front")
        .setMac("c0:74:2b:fe:12:34")
        .setBootId("3c1e6a2e")
        .addAddresses("10.12.34.11");
    description
        .getMutableIdentity()
        .getMutableOsRelease()
        .next()
        .setKey("PRETTY_NAME")
        .setValue("Debian GNU/Linux 13 (trixie)");
    description.addPacks(Spotter.Pack.newInstance().setName("debian").setVersion("1.0.0"));
    description.addValues(
        Spotter.FieldDeclaration.newInstance()
            .setId("debian.thermal.margin")
            .setLabel("Thermal margin")
            .setType(Spotter.FieldType.FIELD_TYPE_NUMBER)
            .setUnit("°C")
            .setWarn(Spotter.Limit.newInstance().setBelow(10))
            .setFail(
                Spotter.Limit.newInstance()
                    .setNotEquals(Spotter.Scalar.newInstance().setText("x"))
                    .setMissing(true)));
    description.addLogs(Spotter.LogDeclaration.newInstance().setId("debian.journal"));
    description.addActions(
        Spotter.ActionDeclaration.newInstance()
            .setId("core.reboot")
            .addResponse(Spotter.FieldDeclaration.newInstance().setId("exit")));
    description.addProblems("/etc/frc-spotter/packs/old: group-writable, ignored");
    return description;
  }

  @Test
  void realMessagesPass() throws Exception {
    Spotter.Event described = Spotter.Event.newInstance().setDescribed(description());
    WireCheck.check(WireCheck.EVENT, described.toByteArray());
    Spotter.RunResult result =
        Spotter.RunResult.newInstance()
            .setOutcome(Spotter.Outcome.OUTCOME_COMPLETED)
            .addResponse(Spotter.FieldValue.newInstance().setName("exit").setNumber(0))
            .addResponse(
                Spotter.FieldValue.newInstance()
                    .setName("report")
                    .setStatus(
                        Spotter.Status.newInstance()
                            .setLevel(Spotter.Level.LEVEL_WARNING)
                            .setMessage("slow")));
    WireCheck.check(
        WireCheck.EVENT,
        Spotter.Event.newInstance()
            .setRun(Spotter.RunEvent.newInstance().setRun("0123").setFinished(result))
            .toByteArray());
    WireCheck.check(
        WireCheck.EVENT,
        Spotter.Event.newInstance()
            .setRuns(
                Spotter.Runs.newInstance()
                    .addRuns(Spotter.RunState.newInstance().setRun("0123").setResult(result)))
            .toByteArray());
    WireCheck.check(
        WireCheck.LOG_PAGE,
        Spotter.LogPage.newInstance()
            .addEntries(Spotter.LogEntry.newInstance().setMessage("hello").setCursor("1"))
            .toByteArray());
    WireCheck.check(
        WireCheck.PACK_BUNDLE,
        Spotter.PackBundle.newInstance()
            .addFiles(
                Spotter.PackFile.newInstance()
                    .setPath("debian/pack.yaml")
                    .setContent("pack: debian\n".getBytes(StandardCharsets.UTF_8)))
            .toByteArray());
    // Nothing at all is a message too: every field unset.
    WireCheck.check(WireCheck.PROBLEM, new byte[0]);
  }

  @Test
  void aStringThatSaysMoreThanIsLeftIsRefusedAtAnyDepth() {
    // Event { described: Description { problems: a string of 2 GiB, with nothing after it } }:
    // what made QuickBuffers 1.4 allocate 2 GiB for eight bytes.
    byte[] described = field(1, field(8, Integer.MAX_VALUE, new byte[0]));
    assertThatThrownBy(() -> WireCheck.check(WireCheck.EVENT, described))
        .isInstanceOf(WireCheck.Malformed.class)
        .hasMessage("spotter.v2.Description's field 8 says 2147483647 bytes, and 0 are left");

    // Values { values: FieldValue { unavailable: 5 bytes, with 3 there } }, deeper.
    byte[] value = field(9, 5, "why".getBytes(StandardCharsets.UTF_8));
    byte[] values = field(2, field(4, value));
    assertThatThrownBy(() -> WireCheck.check(WireCheck.EVENT, values))
        .hasMessage("spotter.v2.FieldValue's field 9 says 5 bytes, and 3 are left");

    // A Problem's message, as a refusal's body: as a whole message, too long for its bytes.
    byte[] problem = field(1, 1000, new byte[10]);
    assertThatThrownBy(() -> WireCheck.check(WireCheck.PROBLEM, problem))
        .hasMessage("spotter.v2.Problem's field 1 says 1000 bytes, and 10 are left");

    // A message nested in another can't take more than its holder has, either.
    byte[] nested = field(1, field(4, 100, field(1, new byte[3])));
    assertThatThrownBy(() -> WireCheck.check(WireCheck.EVENT, nested))
        .hasMessage("spotter.v2.Description's field 4 says 100 bytes, and 5 are left");
  }

  @Test
  void aFieldThisVersionDoesntKnowIsCheckedAndPassedOver() throws Exception {
    // A later minor version's field, number 99: fine when it fits.
    byte[] later = concat(description().toByteArray(), field(99, new byte[] {1, 2, 3}));
    WireCheck.check(WireCheck.DESCRIPTION, later);
    assertThat(
            ProtoMessage.mergeFrom(Spotter.Description.newInstance(), later)
                .getIdentity()
                .getHostname())
        .isEqualTo("vision-front");
    byte[] tooLong = concat(description().toByteArray(), field(99, 1 << 30, new byte[0]));
    assertThatThrownBy(() -> WireCheck.check(WireCheck.DESCRIPTION, tooLong))
        .hasMessageContaining("field 99 says 1073741824 bytes");
  }

  @Test
  void moreListElementsThanABoardEverSendsAreRefused() throws Exception {
    // Values with empty FieldValues, each two bytes and a whole FieldValue's objects to parse.
    ByteArrayOutputStream many = new ByteArrayOutputStream();
    for (int i = 0; i < WireCheck.MAX_REPEATED; i++) {
      many.writeBytes(field(4, new byte[0]));
    }
    WireCheck.check(WireCheck.VALUES, many.toByteArray());
    many.writeBytes(field(4, new byte[0]));
    assertThatThrownBy(() -> WireCheck.check(WireCheck.VALUES, many.toByteArray()))
        .hasMessage(
            "more than "
                + WireCheck.MAX_REPEATED
                + " elements of lists in one message, at spotter.v2.Values's field 4");
  }

  @Test
  void wireTypesProto3HasNoneOfAndCutMessagesAreRefused() {
    // A group (wire type 3), which proto3 doesn't have.
    assertThatThrownBy(() -> WireCheck.check(WireCheck.EVENT, new byte[] {(byte) (7 << 3 | 3)}))
        .hasMessage("spotter.v2.Event's field 7 has wire type 3, which spotter.proto has none of");
    // A varint cut short: QuickBuffers' own words.
    assertThatThrownBy(() -> WireCheck.check(WireCheck.EVENT, new byte[] {8, (byte) 0x80}))
        .isInstanceOf(WireCheck.Malformed.class)
        .hasMessageStartingWith("spotter.v2.Event: ");
    // A field numbered 0.
    assertThatThrownBy(() -> WireCheck.check(WireCheck.EVENT, new byte[] {0, 0}))
        .isInstanceOf(WireCheck.Malformed.class);
  }

  @Test
  void aStreamIsCheckedAsBytesAre() throws Exception {
    byte[] bundle =
        Spotter.PackBundle.newInstance()
            .addFiles(Spotter.PackFile.newInstance().setPath("a/pack.yaml"))
            .toByteArray();
    WireCheck.check(
        WireCheck.PACK_BUNDLE,
        ProtoSource.newInstance(new ByteArrayInputStream(bundle)),
        bundle.length);
    byte[] lying = field(1, field(3, Integer.MAX_VALUE, new byte[0]));
    assertThatThrownBy(
            () ->
                WireCheck.check(
                    WireCheck.PACK_BUNDLE,
                    ProtoSource.newInstance(new ByteArrayInputStream(lying)),
                    lying.length))
        .hasMessage("spotter.v2.PackFile's field 3 says 2147483647 bytes, and 0 are left");
  }
}
