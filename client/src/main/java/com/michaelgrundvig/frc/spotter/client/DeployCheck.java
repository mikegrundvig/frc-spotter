package com.michaelgrundvig.frc.spotter.client;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The check a deploy runs first, on the laptop: asks each coprocessor the robot program expects for
 * its identity ({@code /v1/stamp}, a second to connect, a second to answer), and compares the two.
 * One that doesn't answer is asked once more, two seconds on, as one restarting would answer then:
 * at most about 8 s for a coprocessor that never answers.
 *
 * <p>A coprocessor whose hostname isn't the one expected, that doesn't have the address it was
 * asked at, or whose {@code /etc/os-release} lacks a label the caller expects or says another
 * value, fails the deploy; so does something else answering on the agent's port, or (when the
 * caller's asker checks for it) the computer's software answering without its agent. One that
 * doesn't answer at all only warns, so a robot can be deployed with its vision off.
 *
 * <p>It knows nothing of what built the coprocessor's image: the labels it compares are the
 * caller's, by name, as an image builder writes them ({@code IMAGE_ID}, {@code IMAGE_VERSION}, and
 * its own prefixed keys).
 */
public final class DeployCheck {
  /** How long each coprocessor has to connect, and then to answer. */
  static final double TIMEOUT_SECONDS = 1;

  /** What a coprocessor's stamp can hold, at most. */
  private static final int MAX_STAMP_BYTES = 16 * 1024;

  /** How long to wait before asking a coprocessor that didn't answer once more. */
  static final long RETRY_MILLIS = 2000;

  private DeployCheck() {}

  /**
   * A coprocessor as the robot program expects it.
   *
   * @param name its hostname
   * @param address the address it's asked at, such as {@code 10.12.34.11}; it must have it
   * @param agentPort its agent's port
   * @param osRelease the {@code /etc/os-release} keys it must have, each with its value; empty for
   *     none
   */
  public record Expected(
      String name, String address, int agentPort, Map<String, String> osRelease) {
    public Expected {
      osRelease = Collections.unmodifiableMap(new TreeMap<>(osRelease));
    }

    /** A coprocessor expected by its name and address alone, its agent on 5808. */
    public Expected(String name, String address) {
      this(name, address, AgentApi.PORT, Map.of());
    }
  }

  /** What became of asking one coprocessor. */
  public sealed interface Asked {
    /** Its agent answered with its identity. */
    record Stamped(Stamp stamp) implements Asked {}

    /**
     * Something answered on its agent's port, but not with an identity this build reads (a 404, or
     * not JSON): not Spotter's agent, or not this version of it.
     */
    record Unreadable(String why) implements Asked {}

    /**
     * Its agent didn't answer (refused, or no answer in time), but something else on the computer
     * did, as the caller's asker checks (the software it runs, on its own port): the agent isn't
     * running.
     *
     * @param what what answered, for people: "Its vision page"
     * @param why why the agent didn't
     */
    record AgentMissing(String what, String why) implements Asked {}

    /** Its agent answered that it's busy (503), twice: not checked, not failed. */
    record Busy(String why) implements Asked {}

    /** Nothing answered: off, unplugged, or not on this network. */
    record Unreachable(String why) implements Asked {}
  }

  /** Asks a coprocessor: the network, or a test's stand-in. */
  @FunctionalInterface
  public interface Asker {
    Asked ask(String address, int agentPort);
  }

  /** How a coprocessor stands against what the robot program expects. */
  public enum Verdict {
    OK,
    WARN,
    FAIL
  }

  /**
   * One coprocessor's verdict, and why.
   *
   * @param computer its name
   * @param verdict whether the deploy may go on
   * @param text what was found
   */
  public record Finding(String computer, Verdict verdict, String text) {
    @Override
    public String toString() {
      return verdict + " " + computer + ": " + text;
    }
  }

  /**
   * How a coprocessor's identity differs from what's expected, each as a sentence naming both
   * values; empty when it's as expected.
   */
  public static List<String> differences(Expected expected, Stamp stamp) {
    List<String> differences = new ArrayList<>();
    if (!stamp.hostname().equals(expected.name())) {
      differences.add(
          "its hostname is \"" + stamp.hostname() + "\", not \"" + expected.name() + "\"");
    }
    if (!stamp.addresses().contains(expected.address())) {
      differences.add(
          "its addresses are "
              + (stamp.addresses().isEmpty() ? "none" : String.join(", ", stamp.addresses()))
              + ", without "
              + expected.address());
    }
    expected
        .osRelease()
        .forEach(
            (key, value) -> {
              String found = stamp.osRelease().get(key);
              if (found == null) {
                differences.add(
                    key + " isn't in its os-release, and \"" + value + "\" is expected");
              } else if (!found.equals(value)) {
                differences.add(
                    key
                        + " is \""
                        + found
                        + "\" on the coprocessor and \""
                        + value
                        + "\" in this build");
              }
            });
    return differences;
  }

  /** One coprocessor's verdict, from what asking it found. */
  public static Finding judge(Expected expected, Asked asked) {
    String name = expected.name();
    String at = expected.address();
    if (asked instanceof Asked.Stamped) {
      Stamp stamp = ((Asked.Stamped) asked).stamp();
      List<String> differences = differences(expected, stamp);
      if (!differences.isEmpty()) {
        return new Finding(
            name,
            Verdict.FAIL,
            "not the computer this build expects at "
                + at
                + ": "
                + String.join("; ", differences)
                + ". Flash it with the image this build expects, or deploy with its check"
                + " skipped to leave it as it is");
      }
      return new Finding(
          name,
          Verdict.OK,
          stamp.hostname()
              + " at "
              + at
              + (expected.osRelease().isEmpty()
                  ? ""
                  : ", with " + String.join(", ", expected.osRelease().keySet()) + " as expected"));
    }
    if (asked instanceof Asked.Unreadable) {
      return new Finding(
          name,
          Verdict.FAIL,
          "something else answers at "
              + at
              + ": its agent's port answered, but not with an identity ("
              + ((Asked.Unreadable) asked).why()
              + "). Is Spotter's agent 0.3.0 or newer installed there?");
    }
    if (asked instanceof Asked.AgentMissing) {
      Asked.AgentMissing missing = (Asked.AgentMissing) asked;
      return new Finding(
          name,
          Verdict.FAIL,
          missing.what()
              + " answers at "
              + at
              + ", but its agent doesn't ("
              + missing.why()
              + "): the agent isn't running. Read its journal");
    }
    if (asked instanceof Asked.Busy) {
      return new Finding(
          name,
          Verdict.WARN,
          "its agent at "
              + at
              + " is busy ("
              + ((Asked.Busy) asked).why()
              + "): not checked; deploying anyway");
    }
    return new Finding(
        name,
        Verdict.WARN,
        "nothing answers at "
            + at
            + " ("
            + ((Asked.Unreachable) asked).why()
            + "): deploying anyway, without it until it answers");
  }

  /** Waits, before asking again: the clock for real, nothing in a test. */
  @FunctionalInterface
  public interface Sleeper {
    /** Waits for real; interrupted, it stops waiting and keeps the thread's interrupt. */
    Sleeper REAL =
        millis -> {
          try {
            Thread.sleep(millis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };

    void sleep(long millis);
  }

  /**
   * Every expected coprocessor's verdict, in order. One that doesn't answer with its identity is
   * asked once more, {@link #RETRY_MILLIS} on.
   */
  public static List<Finding> check(List<Expected> coprocessors, Asker asker, Sleeper sleeper) {
    List<Finding> findings = new ArrayList<>();
    for (Expected expected : coprocessors) {
      Asked asked = asker.ask(expected.address(), expected.agentPort());
      if (!(asked instanceof Asked.Stamped)) {
        sleeper.sleep(RETRY_MILLIS);
        asked = asker.ask(expected.address(), expected.agentPort());
      }
      findings.add(judge(expected, asked));
    }
    return findings;
  }

  /**
   * Asks a coprocessor's agent for its identity over the network. A caller's asker may go on to
   * check what else answers when the agent doesn't ({@link Asked.AgentMissing}).
   */
  public static Asked ask(String address, int agentPort) {
    AgentHttp agent =
        new AgentHttp(address, agentPort, TIMEOUT_SECONDS, TIMEOUT_SECONDS, MAX_STAMP_BYTES);
    byte[] body;
    try {
      body = agent.get(AgentApi.STAMP);
    } catch (AgentHttp.Answered another) {
      return another.busy()
          ? new Asked.Busy(String.valueOf(another.getMessage()))
          : new Asked.Unreadable(String.valueOf(another.getMessage()));
    } catch (IOException noAgent) {
      return new Asked.Unreachable(Poller.why(noAgent));
    }
    try {
      Stamp stamp = Stamp.parse(new String(body, StandardCharsets.UTF_8));
      if (stamp.hostname().isEmpty()) {
        return new Asked.Unreadable("its answer has no hostname");
      }
      return new Asked.Stamped(stamp);
    } catch (JsonException | IllegalArgumentException e) {
      return new Asked.Unreadable(String.valueOf(e.getMessage()));
    }
  }

  /**
   * Prints every finding, and whether the deploy may go on.
   *
   * @return whether it may: nothing failed
   */
  public static boolean report(List<Finding> findings, PrintStream out) {
    if (findings.isEmpty()) {
      out.println("Coprocessor check: no coprocessors are expected");
      return true;
    }
    boolean failed = false;
    int passed = 0;
    for (Finding finding : findings) {
      out.println("Coprocessor check: " + finding);
      failed |= finding.verdict() == Verdict.FAIL;
      passed += finding.verdict() == Verdict.OK ? 1 : 0;
    }
    out.println(
        failed
            ? "Coprocessor check: FAILED. Nothing was deployed."
            : passed == 0
                ? "Coprocessor check: none answered, so none was compared with this build."
                : "Coprocessor check: "
                    + passed
                    + (passed == 1 ? " answered" : " answered, each")
                    + " as this build expects.");
    return !failed;
  }
}
