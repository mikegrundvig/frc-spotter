package com.michaelgrundvig.frc.spotter.table;

import java.util.List;

/** A coprocessor table that can't be used, with every problem found, each saying where. */
public class TableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final List<String> problems;

  /** A table with these problems. */
  public TableException(List<String> problems) {
    super(String.join("\n", problems));
    this.problems = List.copyOf(problems);
  }

  /** Every problem found, one per entry. */
  public List<String> problems() {
    return problems;
  }
}
