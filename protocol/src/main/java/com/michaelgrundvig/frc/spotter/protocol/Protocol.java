package com.michaelgrundvig.frc.spotter.protocol;

/**
 * Spotter's protocol, version 2.0: HTTP on {@link #PORT}, its messages {@code spotter.proto}'s
 * ({@link Spotter}), in protobuf unless JSON is asked for. The version is in HTTP, never in a
 * message: the major version in the path ({@code /v2/}), and {@code major.minor} in the {@link
 * #HEADER} of every response, which a reader checks before it reads a byte of the body.
 */
public final class Protocol {
  /** The protocol's version, in {@link #HEADER} on every response. */
  public static final String VERSION = "2.0";

  /** The header every response carries the protocol's version in. */
  public static final String HEADER = "Spotter-Protocol";

  /** The agent's port unless its {@code agent.json} says otherwise: in FRC's team range. */
  public static final int PORT = 5808;

  /** Protobuf's media type: what every endpoint answers in unless JSON is asked for. */
  public static final String PROTOBUF = "application/x-protobuf";

  /** JSON's media type: asked for with {@code Accept}, it answers in QuickBuffers' JSON form. */
  public static final String JSON = "application/json";

  /** {@code GET}: the agent's {@code Description}. */
  public static final String DESCRIBE = "/v2/describe";

  /** {@code GET}: every value, as a complete {@code Values}. */
  public static final String VALUES = "/v2/values";

  /**
   * {@code GET ?heartbeat=250ms&packs=<hash>}: the stream, each {@code Event} preceded by its
   * length as a varint.
   */
  public static final String STREAM = "/v2/stream";

  /** {@code POST}: a bundle of pack folders (a zip), for a board that accepts pushes. */
  public static final String PACKS = "/v2/packs";

  /** {@code GET /v2/logs/<id>?from=&cursor=&limit=&level=}: a {@code LogPage}. */
  public static final String LOGS = "/v2/logs/";

  /** {@code POST /v2/actions/<id>}: starts an action, its input the body. */
  public static final String ACTIONS = "/v2/actions/";

  /**
   * {@code GET /v2/runs/<run>}, {@code .../files/<field>}, {@code .../log}; {@code DELETE} cancels
   * it.
   */
  public static final String RUNS = "/v2/runs/";

  /** The header a stream's response carries its connection's challenge in. */
  public static final String CHALLENGE = "Spotter-Challenge";

  /** The header a signed write carries: {@code <key id> <counter> <signature>}. */
  public static final String SIGNATURE = "Spotter-Signature";

  /** The last number of the robot controller's address, 10.TE.AM.2: who may change a board. */
  public static final int CONTROLLER = 2;

  private Protocol() {}
}
