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

  /** The last number of the robot controller's address, 10.TE.AM.2: who may change a board. */
  public static final int CONTROLLER = 2;

  private Protocol() {}
}
