package com.michaelgrundvig.frc.spotter.client;

import com.michaelgrundvig.frc.spotter.api.Health;
import com.michaelgrundvig.frc.spotter.api.ProbeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A requirement judged against a coprocessor's latest health: met or not, and why, in words a
 * person reads on a card or in an alert.
 *
 * @param requirement what was needed
 * @param met whether it's met
 * @param said why not, naming the probe and both values; empty when it's met
 */
public record Verdict(Requirement requirement, boolean met, String said) {
  /** Each requirement judged against one health answer, in order. */
  public static List<Verdict> judge(List<Requirement> requirements, Health health) {
    List<Verdict> verdicts = new ArrayList<>();
    for (Requirement requirement : requirements) {
      verdicts.add(judge(requirement, health.probe(requirement.probe())));
    }
    return verdicts;
  }

  /** One requirement judged against its probe's latest result, if the computer has the probe. */
  public static Verdict judge(Requirement requirement, Optional<ProbeResult> found) {
    String probe = requirement.probe();
    if (found.isEmpty()) {
      return new Verdict(
          requirement, false, probe + " isn't defined on this computer: is its pack installed?");
    }
    ProbeResult result = found.get();
    switch (result.status()) {
      case ProbeResult.PASS:
        break;
      case ProbeResult.PENDING:
        return new Verdict(requirement, false, probe + " hasn't run yet");
      case ProbeResult.ERROR:
        return new Verdict(requirement, false, probe + " couldn't run: " + result.detail());
      default:
        return new Verdict(
            requirement,
            false,
            probe + " failed" + (result.detail().isEmpty() ? "" : ": " + result.detail()));
    }
    if (!requirement.equals().isEmpty() && !requirement.equals().equals(result.value())) {
      return new Verdict(
          requirement,
          false,
          probe + " is \"" + result.value() + "\", not \"" + requirement.equals() + "\"");
    }
    return new Verdict(requirement, true, "");
  }
}
