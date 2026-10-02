package com.michaelgrundvig.frc.spotter.probes;

import java.util.List;

/** A pack that can't be used, with every problem found, each saying where (its file and line). */
public class PackException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final List<String> problems;

  /** A pack with these problems. */
  public PackException(List<String> problems) {
    super(String.join("\n", problems));
    this.problems = List.copyOf(problems);
  }

  /** Every problem found, one per entry. */
  public List<String> problems() {
    return problems;
  }
}
