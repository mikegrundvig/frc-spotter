package com.michaelgrundvig.frc.spotter.api;

import java.util.List;

/**
 * The coprocessor agent's HTTP API, version 1: JSON, on the computer's {@code agentPort} (5808
 * unless the table says otherwise). Read-only but for one action, {@link #SHUTDOWN}, which only the
 * robot controller may ask for. It runs nothing a request says, only what the computer's packs
 * define, by name, and bounds every answer. A refusal or a failure answers {@code {"error": "..."}}
 * with its status: 400 for a bad query, 403, 404, 405, 503 when busy, or 500.
 */
public final class AgentApi {
  /** Which image this is, and as which computer: a {@link Stamp}. */
  public static final String STAMP = "/v1/stamp";

  /** The computer's health: a {@link Health}. */
  public static final String HEALTH = "/v1/health";

  /**
   * A page of the journal: a {@link JournalPage}. Asks with {@code cursor} (the entries after it;
   * without one, the latest), {@code priority} (0-7: that and worse), {@code unit} (one of {@link
   * #JOURNAL_UNITS}; without one, all of them), and {@code limit} (at most {@link
   * #MAX_JOURNAL_PAGE}).
   */
  public static final String JOURNAL = "/v1/journal";

  /** The agent's own systemd unit. */
  public static final String AGENT_UNIT = "frc-coprocessor-agent.service";

  /**
   * What {@link #JOURNAL} always shows, and the units it may be asked for: the kernel's messages,
   * and the agent's (with systemd's about it). Each pack the computer runs adds its own units (a
   * computer's {@code ProbeSet.journalUnits}); the rest of the journal isn't served.
   */
  public static final List<String> JOURNAL_UNITS = List.of("kernel", AGENT_UNIT);

  /**
   * Every probe's latest result ({@code GET}), as {@link Health#probes} has them; {@code GET
   * /v1/probes/<id>} runs one now and answers its result, at most once in {@link
   * #PROBE_RUN_SECONDS} each (a run asked for sooner answers the last one).
   */
  public static final String PROBES = "/v1/probes";

  /** How often one probe may be run when asked: more often answers its last result. */
  public static final int PROBE_RUN_SECONDS = 2;

  /** A file a pack serves: {@code GET /v1/downloads/<name>}. */
  public static final String DOWNLOADS = "/v1/downloads";

  /**
   * API version 1's settings, kept as a path: the download {@code settings.json}, which
   * PhotonVision's pack defines (its settings as canonical rows, with their hash).
   */
  public static final String SETTINGS = "/v1/settings";

  /**
   * API version 1's settings backup, kept as a path: the download {@code settings.zip}, which
   * PhotonVision's pack defines (laid out like the repository's folder for the computer).
   */
  public static final String SETTINGS_ZIP = "/v1/settings.zip";

  /**
   * Shuts the computer down (POST): runs the steps its packs define first (PhotonVision's stops
   * PhotonVision), then powers off. Only the robot controller ({@link #CONTROLLER}, or the address
   * the computer's configuration names) may ask; anyone else gets 403. It answers 202 with a {@link
   * ShutdownAnswer} at once, then acts; asking again while it's under way answers 202 and does
   * nothing more.
   */
  public static final String SHUTDOWN = "/v1/shutdown";

  /** The last number of the robot controller's address, 10.TE.AM.2: who may ask to shut down. */
  public static final int CONTROLLER = 2;

  /** Where the image keeps its stamp. */
  public static final String STAMP_FILE = "/etc/coprocessor/stamp.json";

  /** How many journal entries a page has unless the request says. */
  public static final int DEFAULT_JOURNAL_PAGE = 100;

  /** The most journal entries a page may have. */
  public static final int MAX_JOURNAL_PAGE = 500;

  /** The longest a journal message is sent; longer ones are cut. */
  public static final int MAX_MESSAGE = 2048;

  private AgentApi() {}
}
