package com.michaelgrundvig.frc.spotter.client;

import com.michaelgrundvig.frc.spotter.api.AgentApi;
import com.michaelgrundvig.frc.spotter.api.Stamp;
import com.michaelgrundvig.frc.spotter.json.JsonException;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import com.michaelgrundvig.frc.spotter.table.Computer;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The check {@code deploy} runs first: asks each coprocessor in the table for its stamp (a second
 * to connect, a second to answer), and compares it with this build. One that doesn't answer is
 * asked once more, two seconds on, as one restarting would answer then: at most about 8 s for a
 * coprocessor that never answers. A coprocessor that answers with another PhotonVision, image
 * recipe, name, team, or address than this build's fails the deploy, as does something else
 * answering on the agent's port (a different image), or PhotonVision answering without its agent
 * (the agent isn't running, or it isn't this repository's image). One that doesn't answer at all
 * only warns, so a robot can be deployed with its vision off. The template's example table (team 0)
 * is checked against nothing.
 *
 * <p>Run by Gradle ({@code ./gradlew coprocessorCheck}, which {@code deploy} depends on), on the
 * laptop, never on the robot. {@code -PskipCoprocessorCheck} skips it, and says so.
 */
public final class DeployCheck {
  /**
   * The port the software a coprocessor's image runs answers on, which says a computer is up when
   * its agent isn't: PhotonVision's page, 5800.
   */
  public static final int SOFTWARE_PORT = 5800;

  /**
   * Whether a table is the robot template's example: team 0, with computers listed, which a robot
   * has none of; one with no computers says none on purpose.
   */
  public static boolean isExample(CompiledTable table) {
    return table.table().team() == 0 && !table.table().computers().isEmpty();
  }

  /** How long each coprocessor has to connect, and then to answer. */
  static final double TIMEOUT_SECONDS = 1;

  /** What a coprocessor's stamp can hold, at most. */
  private static final int MAX_STAMP_BYTES = 16 * 1024;

  /** How long to wait before asking a coprocessor that didn't answer once more. */
  static final long RETRY_MILLIS = 2000;

  private DeployCheck() {}

  /** What became of asking one coprocessor. */
  public sealed interface Asked {
    /** Its agent answered with its stamp. */
    record Stamped(Stamp stamp) implements Asked {}

    /**
     * Something answered on its agent's port, but not with a stamp this build reads (a 404, or not
     * JSON): a different image.
     */
    record Unreadable(String why) implements Asked {}

    /**
     * Its agent didn't answer (refused, or no answer in time), but PhotonVision's port did: the
     * agent isn't running, or it isn't this repository's image.
     */
    record PhotonVisionOnly(String why) implements Asked {}

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

  /** How a coprocessor stands against this build. */
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
   * One coprocessor's verdict, from what asking it found.
   *
   * @param table the table compiled into this build, with what it expects
   * @param computer the coprocessor, from that table
   * @param asked what asking it found
   */
  public static Finding judge(CompiledTable table, Computer computer, Asked asked) {
    String name = computer.name();
    String at = table.table().ip(computer);
    if (asked instanceof Asked.Stamped) {
      Stamp stamp = ((Asked.Stamped) asked).stamp();
      // The table's own comparison: the wrong computer or PhotonVision fails the deploy, an older
      // recipe or one this build can't judge (no recipe hash) only warns.
      List<CompiledTable.Mismatch> mismatches = table.compare(computer, stamp);
      List<String> errors = messages(mismatches, CompiledTable.Severity.ERROR);
      List<String> warnings = messages(mismatches, CompiledTable.Severity.WARNING);
      List<String> unknown = messages(mismatches, CompiledTable.Severity.UNKNOWN);
      String release =
          "release " + stamp.release() + ", PhotonVision " + stamp.photonvisionVersion();
      if (!errors.isEmpty()) {
        return new Finding(
            name,
            Verdict.FAIL,
            "the wrong image at "
                + at
                + ": "
                + String.join("; ", errors)
                + ". Flash it with this build's release, or deploy with"
                + " -PskipCoprocessorCheck to leave it as it is");
      }
      if (!warnings.isEmpty()) {
        return new Finding(
            name,
            Verdict.WARN,
            release
                + ", built from another recipe: "
                + String.join("; ", warnings)
                + ". Flash this build's release when convenient");
      }
      if (!unknown.isEmpty()) {
        return new Finding(
            name,
            Verdict.WARN,
            release
                + ", as this build's; its recipe can't be checked: "
                + String.join("; ", unknown));
      }
      return new Finding(name, Verdict.OK, release + ", as this build's");
    }
    if (asked instanceof Asked.Unreadable) {
      return new Finding(
          name,
          Verdict.FAIL,
          "a different image answers at "
              + at
              + ": its agent's port answered, but not with a stamp ("
              + ((Asked.Unreadable) asked).why()
              + "). Flash it with this build's release");
    }
    if (asked instanceof Asked.PhotonVisionOnly) {
      return new Finding(
          name,
          Verdict.FAIL,
          "PhotonVision answers at "
              + at
              + ", but its health agent doesn't ("
              + ((Asked.PhotonVisionOnly) asked).why()
              + "): the agent isn't running, or this isn't this repository's image. Read its"
              + " journal, or flash it with this build's release");
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
            + "): deploying anyway, with its cameras off until it answers");
  }

  /** The comparison's sentences of one severity. */
  private static List<String> messages(
      List<CompiledTable.Mismatch> mismatches, CompiledTable.Severity severity) {
    return mismatches.stream()
        .filter(mismatch -> mismatch.severity() == severity)
        .map(CompiledTable.Mismatch::message)
        .toList();
  }

  /** Waits, before asking again: the clock for real, nothing in a test. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(long millis);
  }

  /**
   * Every coprocessor's verdict, and one for the table itself when its team isn't the one being
   * deployed to. One that doesn't answer with a stamp is asked once more, {@link #RETRY_MILLIS} on.
   *
   * @param deployTeam the team number the deploy goes to; 0 or less when unknown
   */
  public static List<Finding> check(
      CompiledTable table, int deployTeam, Asker asker, Sleeper sleeper) {
    List<Finding> findings = new ArrayList<>();
    if (isExample(table)) {
      findings.add(
          new Finding(
              "the table",
              Verdict.WARN,
              "coprocessors/coprocessors.yaml is the template's example (team 0), so no"
                  + " coprocessor was checked: set your team number and list your coprocessors,"
                  + " or computers: [] for none"));
      return findings;
    }
    int team = table.table().team();
    if (deployTeam > 0 && team != deployTeam) {
      findings.add(
          new Finding(
              "the table",
              Verdict.WARN,
              "coprocessors/coprocessors.yaml is team "
                  + team
                  + "'s, and this deploy is to team "
                  + deployTeam
                  + "'s robot: set its team to ask the right addresses"));
    }
    for (Computer computer : table.table().computers()) {
      String address = table.table().ip(computer);
      Asked asked = asker.ask(address, computer.agentPort());
      if (!(asked instanceof Asked.Stamped)) {
        sleeper.sleep(RETRY_MILLIS);
        asked = asker.ask(address, computer.agentPort());
      }
      findings.add(judge(table, computer, asked));
    }
    return findings;
  }

  /** Asks a coprocessor over the network: its agent's stamp, else whether PhotonVision answers. */
  public static Asked ask(String address, int agentPort) {
    return ask(address, agentPort, SOFTWARE_PORT);
  }

  /**
   * Asks a coprocessor over the network: its agent's stamp, else whether PhotonVision answers.
   *
   * @param photonVisionPort where PhotonVision's page answers: 5800, but for a test's stand-in
   */
  public static Asked ask(String address, int agentPort, int photonVisionPort) {
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
      String why = Poller.why(noAgent);
      try (Socket photonVision = new Socket()) {
        photonVision.connect(
            new InetSocketAddress(address, photonVisionPort), (int) (TIMEOUT_SECONDS * 1000));
        return new Asked.PhotonVisionOnly(why);
      } catch (IOException nothing) {
        return new Asked.Unreachable(why);
      }
    }
    try {
      return new Asked.Stamped(Stamp.parse(new String(body, StandardCharsets.UTF_8)));
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
      out.println("Coprocessor check: the table has no coprocessors");
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
                    + (passed == 1 ? " answered, running" : " answered, each running")
                    + " this build's image.");
    return !failed;
  }

  /**
   * Checks every coprocessor in the table compiled into this build; exits 1 if the deploy must
   * stop.
   *
   * @param args the team number deployed to, if known
   */
  public static void main(String[] args) {
    int team = args.length > 0 && args[0].matches("\\d{1,5}") ? Integer.parseInt(args[0]) : 0;
    CompiledTable table = CompiledTable.load();
    Sleeper sleeper =
        millis -> {
          try {
            Thread.sleep(millis);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    if (!report(check(table, team, DeployCheck::ask, sleeper), System.out)) {
      System.exit(1);
    }
  }
}
