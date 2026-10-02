package com.michaelgrundvig.frc.spotter.client;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.JournalPage;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * One coprocessor's agent, as the robot sees it: asked for its health once a period on a thread of
 * this client's own, so the robot loop only ever reads the latest answer and never waits on the
 * network; asked for more (a probe run now, a download, a page of its journal) on the caller's
 * thread; and asked to power down, watched until it's gone from its agent's port and its
 * software's. Nothing here knows what the coprocessor runs: what the robot needs of it is a list of
 * {@link Requirement}s, judged against each answer.
 */
public final class AgentClient implements AutoCloseable {
  /** The most a health answer may hold: a computer's 64 probes keep it well under. */
  public static final int MAX_HEALTH_BYTES = 64 * 1024;

  /** The most a download may hold: a settings backup, calibrations included. */
  public static final int MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;

  /** The most a page of the journal may hold: twice what the agent sends at most. */
  public static final int MAX_JOURNAL_BYTES = 8 * 1024 * 1024;

  private final String name;
  private final ClientSettings settings;
  private final LongSupplier robotNanos;
  private final AgentHttp agent;
  private final AgentHttp downloads;
  private final Poller<Health> poller;
  private final PowerDowner powerDowner;

  /**
   * Starts polling a coprocessor's agent, on a thread of its own.
   *
   * @param name the coprocessor's name, for its threads and messages
   * @param address its address, such as {@code 10.12.34.11}
   * @param agentPort its agent's port
   * @param softwarePort the port its software answers on (PhotonVision's page, 5800), which must
   *     close too before it counts as powered down
   * @param settings how often to ask, and how long to wait
   * @param robotNanos the robot's clock, in nanoseconds: what an answer's age is measured on
   */
  public AgentClient(
      String name,
      String address,
      int agentPort,
      int softwarePort,
      ClientSettings settings,
      LongSupplier robotNanos) {
    this.name = name;
    this.settings = settings;
    this.robotNanos = robotNanos;
    agent =
        new AgentHttp(
            address,
            agentPort,
            settings.connectTimeoutSeconds(),
            settings.answerTimeoutSeconds(),
            MAX_HEALTH_BYTES);
    downloads =
        agent.with(
            settings.connectTimeoutSeconds(),
            settings.downloadTimeoutSeconds(),
            MAX_DOWNLOAD_BYTES);
    poller = new Poller<>(name, this::fetch, settings.pollPeriodSeconds(), robotNanos);
    powerDowner =
        new PowerDowner(
            name,
            new PowerDowner.Target() {
              @Override
              public int ask() throws IOException {
                return agent.post(AgentApi.SHUTDOWN);
              }

              @Override
              public boolean agentAnswers() {
                return agent.accepts(agentPort);
              }

              @Override
              public boolean softwareAnswers() {
                return agent.accepts(softwarePort);
              }
            },
            Math.round(settings.pollPeriodSeconds() * 1000),
            Math.round(settings.powerDownTimeoutSeconds() * 1000));
    poller.start();
  }

  /** Its name. */
  public String name() {
    return name;
  }

  private Health fetch() throws IOException {
    return read(agent.get(AgentApi.HEALTH), "health");
  }

  /** An answer read as health, or why it can't be. */
  static Health read(byte[] body, String what) throws IOException {
    try {
      return Health.parse(new String(body, StandardCharsets.UTF_8));
    } catch (JsonException | IllegalArgumentException e) {
      throw new IOException("its " + what + " can't be read: " + e.getMessage(), e);
    }
  }

  /** The latest poll, and the latest answer however old: never waits. */
  public Poller.Snapshot<Health> latest() {
    return poller.latest();
  }

  /** How old the latest answer is, on the robot's clock, in seconds; infinite before the first. */
  public double ageSeconds() {
    Poller.Snapshot<Health> latest = poller.latest();
    if (latest.answer() == null) {
      return Double.POSITIVE_INFINITY;
    }
    return Math.max(0, (robotNanos.getAsLong() - latest.answeredNanos()) / 1e9);
  }

  /** Whether it's missing: no answer for longer than {@link ClientSettings#staleAfterSeconds}. */
  public boolean missing() {
    return ageSeconds() > settings.staleAfterSeconds();
  }

  /**
   * Runs one of its probes now (at most once in {@link AgentApi#PROBE_RUN_SECONDS}; sooner, the
   * agent answers its last result), on the caller's thread.
   *
   * @throws IOException saying why there's no result: no such probe (404), the agent busy (503), or
   *     no answer in time
   */
  public ProbeResult runProbe(String id) throws IOException {
    byte[] body =
        AgentHttp.onceMoreIfBusy(() -> downloads.get(AgentApi.PROBES + "/" + encoded(id)));
    try {
      return ProbeResult.fromJson(Json.parse(new String(body, StandardCharsets.UTF_8)));
    } catch (JsonException | IllegalArgumentException e) {
      throw new IOException("its probe's result can't be read: " + e.getMessage(), e);
    }
  }

  /**
   * One of its packs' downloads ({@code /v1/downloads/<name>}) as it arrives: close it once read.
   * Asked once more, a second on, when the agent is busy with another download.
   */
  public AgentHttp.Streamed download(String file) throws IOException {
    return AgentHttp.onceMoreIfBusy(
        () -> downloads.stream(AgentApi.DOWNLOADS + "/" + encoded(file)));
  }

  /**
   * A page of its journal ({@code /v1/journal}), with the query as the API takes it: {@code
   * priority=3&unit=photonvision.service}, say. Fetched now, on the caller's thread.
   */
  public JournalPage journal(String query) throws IOException {
    AgentHttp journals =
        agent.with(
            settings.connectTimeoutSeconds(), settings.downloadTimeoutSeconds(), MAX_JOURNAL_BYTES);
    byte[] body =
        AgentHttp.onceMoreIfBusy(
            () -> journals.get(AgentApi.JOURNAL + (query.isEmpty() ? "" : "?" + query)));
    try {
      return JournalPage.parse(new String(body, StandardCharsets.UTF_8));
    } catch (JsonException | IllegalArgumentException e) {
      throw new IOException("its journal can't be read: " + e.getMessage(), e);
    }
  }

  /**
   * Asks it to power down, again each poll period until its agent takes the request (or refuses, or
   * {@link ClientSettings#powerDownTimeoutSeconds} passes), then tries its ports until it's gone,
   * on a thread of its own: never waits.
   */
  public void powerDown() {
    powerDowner.ask();
  }

  /** Where powering it down stands. */
  public PowerDowner.State powerDownState() {
    return powerDowner.state();
  }

  /** Stops asking it to power down, and watching it go. */
  public void stopPowerDown() {
    powerDowner.stop();
  }

  private static String encoded(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
  }

  @Override
  public void close() {
    poller.close();
    powerDowner.close();
  }
}
