package com.michaelgrundvig.frc.spotter.manager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The manager's settings, each with a default ({@link #DEFAULTS}); robot code changes those it
 * needs: {@code Settings.DEFAULTS.withPacks(deploy.resolve("spotter-packs"))}.
 *
 * @param heartbeat how often each agent says it's there when nothing else happens: asked for on
 *     connect, kept by the agent between 50 ms and 5 s (default 250 ms)
 * @param missing how long a board may be silent before it counts as missing, on the robot's clock;
 *     longer than the heartbeat (default 1 s). A connection that's silent this long is also
 *     dropped, and made again.
 * @param backoff the longest wait between attempts to reach a board: the first comes 250 ms after a
 *     failure, and each next one waits twice as long, up to this; a stream that opened starts it
 *     over (default 5 s)
 * @param limits robot code's limits for fields, by id, in place of their packs': a value's {@code
 *     pack.collector.field}, or an action's response field's {@code pack.action.field} (default
 *     none)
 * @param refuseWhileEnabled whether to refuse an action while the robot is enabled or the field is
 *     attached, unless the action says {@code whileEnabled: true} (default true)
 * @param pushAutomatically whether to push the team's packs to a board whose packs differ, while
 *     the robot is disabled and the field isn't attached; never on the field (default true)
 * @param packs the team's packs: a folder of pack folders, which every board that accepts pushes is
 *     to have (default none: nothing is pushed)
 * @param key the private key that signs writes, for boards that require signatures (default {@code
 *     /home/systemcore/spotter.key}); without it, reading still works
 * @param publicKey its public key (default: {@code spotter.pub} beside the private key, as
 *     docs/robot.md's openssl commands make them; see {@link #publicKeyFile})
 */
public record Settings(
    Duration heartbeat,
    Duration missing,
    Duration backoff,
    Map<String, Limits> limits,
    boolean refuseWhileEnabled,
    boolean pushAutomatically,
    Optional<Path> packs,
    Path key,
    Optional<Path> publicKey) {
  /** Where the private key is unless robot code says: on the robot controller, outside Git. */
  public static final Path KEY = Path.of("/home/systemcore/spotter.key");

  /** Every setting's default. */
  public static final Settings DEFAULTS =
      new Settings(
          Duration.ofMillis(250),
          Duration.ofSeconds(1),
          Duration.ofSeconds(5),
          Map.of(),
          true,
          true,
          Optional.empty(),
          KEY,
          Optional.empty());

  public Settings {
    if (heartbeat.isNegative() || heartbeat.isZero()) {
      throw new IllegalArgumentException("the heartbeat must be positive: " + heartbeat);
    }
    if (missing.compareTo(heartbeat) <= 0) {
      throw new IllegalArgumentException(
          "a board counts as missing only after longer than its heartbeat ("
              + heartbeat
              + "): "
              + missing);
    }
    if (backoff.isNegative() || backoff.isZero()) {
      throw new IllegalArgumentException("the backoff must be positive: " + backoff);
    }
    limits = Map.copyOf(limits);
  }

  /** These settings with another heartbeat interval. */
  public Settings withHeartbeat(Duration heartbeat) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings with another missing threshold. */
  public Settings withMissing(Duration missing) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings with another longest backoff. */
  public Settings withBackoff(Duration backoff) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings with one field's limits overridden, by its id. */
  public Settings withLimits(String id, Limits override) {
    Map<String, Limits> all = new HashMap<>(limits);
    all.put(id, override);
    return new Settings(
        heartbeat,
        missing,
        backoff,
        all,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings, refusing actions while enabled or on the field, or not. */
  public Settings withRefuseWhileEnabled(boolean refuseWhileEnabled) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings, pushing the team's packs automatically off the field, or not. */
  public Settings withPushAutomatically(boolean pushAutomatically) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings with the team's packs: a folder of pack folders. */
  public Settings withPacks(Path folder) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        Optional.of(folder),
        key,
        publicKey);
  }

  /** These settings with the private key elsewhere: its public key beside it, unless given. */
  public Settings withKey(Path key) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        publicKey);
  }

  /** These settings with the public key elsewhere than beside the private key. */
  public Settings withPublicKey(Path publicKey) {
    return new Settings(
        heartbeat,
        missing,
        backoff,
        limits,
        refuseWhileEnabled,
        pushAutomatically,
        packs,
        key,
        Optional.of(publicKey));
  }

  /** Where the public key is: as given, else {@code spotter.pub} beside the private key. */
  public Path publicKeyFile() {
    return publicKey.orElseGet(() -> key.resolveSibling("spotter.pub"));
  }
}
