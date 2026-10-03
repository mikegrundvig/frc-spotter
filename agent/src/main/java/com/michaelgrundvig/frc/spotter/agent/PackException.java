package com.michaelgrundvig.frc.spotter.agent;

import java.util.List;

/** A pack that can't be read: every problem with it, each with its file and line. */
final class PackException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The problems, each a line. */
  @SuppressWarnings("serial") // an immutable list, never serialized
  private final List<String> problems;

  PackException(List<String> problems) {
    super(String.join("; ", problems));
    this.problems = List.copyOf(problems);
  }

  List<String> problems() {
    return problems;
  }
}
