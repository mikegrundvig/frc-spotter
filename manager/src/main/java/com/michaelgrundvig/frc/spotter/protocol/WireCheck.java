package com.michaelgrundvig.frc.spotter.protocol;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import us.hebi.quickbuf.Descriptors;
import us.hebi.quickbuf.ProtoSource;

/**
 * Spotter's messages, checked before they're parsed, on both sides: what a board sends the robot,
 * and what the robot pushes a board.
 *
 * <p>QuickBuffers 1.4 sizes a string's or bytes' buffer by the length a message declares before it
 * checks that length against what's there, so a few bytes declaring 2 GiB make it allocate 2 GiB.
 * And each element of a repeated field costs a whole message's objects, so a megabyte of empty
 * elements costs hundreds. This walks a message's bytes by its type's fields ({@code
 * spotter.proto}'s, in the table below), with QuickBuffers' own reads, building nothing: every
 * length-delimited field must fit within the message that holds it, and a message may hold at most
 * {@link #MAX_REPEATED} elements of repeated fields in all. A message that passes parses within its
 * own size, and a bounded number of objects. It checks the structure only, never what's in it.
 */
public final class WireCheck {
  /**
   * The most elements of repeated fields one message may hold, at every depth: a board at every
   * limit ({@link Protocol#MAX_VALUES} values, {@link Protocol#MAX_ACTIONS} actions with their
   * responses) describes itself in under half as many, and a bundle of packs holds at most 4,096
   * files.
   */
  public static final int MAX_REPEATED = 1 << 14;

  // The wire types proto3 uses.
  private static final int VARINT = 0;
  private static final int FIXED64 = 1;
  private static final int LENGTH_DELIMITED = 2;
  private static final int FIXED32 = 5;

  private WireCheck() {}

  /** A message that can't be parsed safely, and why. */
  public static final class Malformed extends IOException {
    private static final long serialVersionUID = 1L;

    Malformed(String message) {
      super(message);
    }
  }

  /**
   * A message type, as the check walks it: which of its fields hold a message, and of which type,
   * and which repeat.
   */
  public static final class Type {
    private final String name;
    private Type[] messages = new Type[0];
    private boolean[] repeated = new boolean[0];

    private Type(Descriptors.Descriptor descriptor) {
      this.name = descriptor.getFullName();
    }

    /** Its name in {@code spotter.proto}: {@code spotter.v2.Event}. */
    public String name() {
      return name;
    }

    /** A field that holds a message of a type. */
    private Type message(int field, Type type) {
      if (field >= messages.length) {
        messages = java.util.Arrays.copyOf(messages, field + 1);
      }
      messages[field] = type;
      return this;
    }

    /** A field that repeats: a list of messages, or of strings. */
    private Type list(int field) {
      if (field >= repeated.length) {
        repeated = java.util.Arrays.copyOf(repeated, field + 1);
      }
      repeated[field] = true;
      return this;
    }

    /** A repeated field of messages of a type. */
    private Type messages(int field, Type type) {
      return message(field, type).list(field);
    }

    /** The type of message a field holds; null for any other field. */
    @Nullable Type message(int field) {
      return field < messages.length ? messages[field] : null;
    }

    /** Whether a field repeats. */
    boolean repeats(int field) {
      return field < repeated.length && repeated[field];
    }

    /** One past the highest field number the table says anything of. */
    int fields() {
      return Math.max(messages.length, repeated.length);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  // spotter.proto's messages, leaves first: each field that holds a message, and each that repeats.
  // WireCheckTest checks this table against the proto's own descriptor, so it can't drift.
  private static final Type SCALAR = new Type(Spotter.Scalar.getDescriptor());
  private static final Type LIMIT =
      new Type(Spotter.Limit.getDescriptor()).message(3, SCALAR).message(4, SCALAR);
  private static final Type FIELD_DECLARATION =
      new Type(Spotter.FieldDeclaration.getDescriptor()).message(5, LIMIT).message(6, LIMIT);
  private static final Type OS_RELEASE_ENTRY =
      new Type(Spotter.Identity.OsReleaseEntry.getDescriptor());
  private static final Type IDENTITY =
      new Type(Spotter.Identity.getDescriptor()).list(2).messages(5, OS_RELEASE_ENTRY);
  private static final Type PACK = new Type(Spotter.Pack.getDescriptor());
  private static final Type LOG_DECLARATION = new Type(Spotter.LogDeclaration.getDescriptor());
  private static final Type ACTION_DECLARATION =
      new Type(Spotter.ActionDeclaration.getDescriptor()).messages(8, FIELD_DECLARATION);
  private static final Type STATUS = new Type(Spotter.Status.getDescriptor());
  private static final Type FIELD_VALUE =
      new Type(Spotter.FieldValue.getDescriptor()).message(6, STATUS);
  private static final Type LOG_ENTRY = new Type(Spotter.LogEntry.getDescriptor());
  private static final Type RUN_RESULT =
      new Type(Spotter.RunResult.getDescriptor()).messages(3, FIELD_VALUE);
  private static final Type HEARTBEAT = new Type(Spotter.Heartbeat.getDescriptor());
  private static final Type PACK_FILE = new Type(Spotter.PackFile.getDescriptor());

  /** {@code Description}: what a board has. */
  public static final Type DESCRIPTION =
      new Type(Spotter.Description.getDescriptor())
          .message(3, IDENTITY)
          .messages(4, PACK)
          .messages(5, FIELD_DECLARATION)
          .messages(6, LOG_DECLARATION)
          .messages(7, ACTION_DECLARATION)
          .list(8);

  /** {@code Values}: a board's values, or those that changed. */
  public static final Type VALUES =
      new Type(Spotter.Values.getDescriptor()).messages(4, FIELD_VALUE);

  /** {@code RunState}: a kept run. */
  public static final Type RUN_STATE =
      new Type(Spotter.RunState.getDescriptor()).message(4, RUN_RESULT);

  /** {@code RunEvent}: a run started, logged a line, or finished. */
  public static final Type RUN_EVENT =
      new Type(Spotter.RunEvent.getDescriptor()).message(5, LOG_ENTRY).message(6, RUN_RESULT);

  /** {@code Runs}: the runs a board keeps. */
  public static final Type RUNS = new Type(Spotter.Runs.getDescriptor()).messages(1, RUN_STATE);

  /** {@code Event}: one of a stream's. */
  public static final Type EVENT =
      new Type(Spotter.Event.getDescriptor())
          .message(1, DESCRIPTION)
          .message(2, VALUES)
          .message(3, RUNS)
          .message(4, RUN_EVENT)
          .message(5, HEARTBEAT);

  /** {@code LogPage}: a page of a log. */
  public static final Type LOG_PAGE =
      new Type(Spotter.LogPage.getDescriptor()).messages(1, LOG_ENTRY);

  /** {@code Started}: a run's id. */
  public static final Type STARTED = new Type(Spotter.Started.getDescriptor());

  /** {@code Problem}: why a request was refused. */
  public static final Type PROBLEM = new Type(Spotter.Problem.getDescriptor());

  /** {@code PackBundle}: packs, as the robot pushes them. */
  public static final Type PACK_BUNDLE =
      new Type(Spotter.PackBundle.getDescriptor()).messages(1, PACK_FILE);

  /** Every type, by its name: for the test that checks the table against the proto. */
  static Map<String, Type> types() {
    Map<String, Type> all = new LinkedHashMap<>();
    for (Type type :
        new Type[] {
          SCALAR,
          LIMIT,
          FIELD_DECLARATION,
          OS_RELEASE_ENTRY,
          IDENTITY,
          PACK,
          LOG_DECLARATION,
          ACTION_DECLARATION,
          STATUS,
          FIELD_VALUE,
          LOG_ENTRY,
          RUN_RESULT,
          HEARTBEAT,
          PACK_FILE,
          DESCRIPTION,
          VALUES,
          RUN_STATE,
          RUN_EVENT,
          RUNS,
          EVENT,
          LOG_PAGE,
          STARTED,
          PROBLEM,
          PACK_BUNDLE
        }) {
      all.put(type.name(), type);
    }
    return Collections.unmodifiableMap(all);
  }

  /**
   * Checks a message's bytes before they're parsed.
   *
   * @throws Malformed when they can't be parsed safely, saying why
   */
  public static void check(Type type, byte[] bytes, int offset, int length) throws Malformed {
    check(type, ProtoSource.newArraySource().setInput(bytes, offset, length), length);
  }

  /** Checks a whole message's bytes before they're parsed. */
  public static void check(Type type, byte[] bytes) throws Malformed {
    check(type, bytes, 0, bytes.length);
  }

  /**
   * Checks a message's bytes, read from a source, before they're parsed: {@code length} of them,
   * all the source has. The source is read past them; a caller that parses them sets its source's
   * input again. Builds nothing, so a caller that reuses its source allocates nothing.
   *
   * @throws Malformed when they can't be parsed safely, saying why
   */
  public static void check(Type type, ProtoSource source, int length) throws Malformed {
    try {
      int outer = source.pushLimit(length);
      walk(type, source, 0);
      source.popLimit(outer);
    } catch (Malformed e) {
      throw e;
    } catch (IOException e) {
      // QuickBuffers' own words: cut short, a varint too long, a field numbered 0.
      throw new Malformed(type.name + ": " + e.getMessage());
    }
  }

  /** Walks one message's fields to its limit: the count of repeated elements so far, after it. */
  private static int walk(Type type, ProtoSource source, int count) throws IOException {
    while (!source.isAtEnd()) {
      int tag = source.readTag();
      int field = tag >>> 3;
      switch (tag & 7) {
        case VARINT:
          source.readRawVarint64();
          break;
        case FIXED64:
          source.skipRawBytes(8);
          break;
        case FIXED32:
          source.skipRawBytes(4);
          break;
        case LENGTH_DELIMITED:
          int length = source.readRawVarint32();
          int left = source.getBytesUntilLimit();
          if (length < 0 || length > left) {
            throw new Malformed(
                type.name
                    + "'s field "
                    + field
                    + " says "
                    + (length & 0xffffffffL)
                    + " bytes, and "
                    + left
                    + " are left");
          }
          if (type.repeats(field) && ++count > MAX_REPEATED) {
            throw new Malformed(
                "more than "
                    + MAX_REPEATED
                    + " elements of lists in one message, at "
                    + type.name
                    + "'s field "
                    + field);
          }
          Type nested = type.message(field);
          if (nested == null) {
            source.skipRawBytes(length);
          } else {
            int outer = source.pushLimit(length);
            count = walk(nested, source, count);
            source.popLimit(outer);
          }
          break;
        default:
          throw new Malformed(
              type.name
                  + "'s field "
                  + field
                  + " has wire type "
                  + (tag & 7)
                  + ", which spotter.proto has none of");
      }
    }
    return count;
  }
}
