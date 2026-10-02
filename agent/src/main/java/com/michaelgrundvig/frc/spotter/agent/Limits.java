package com.michaelgrundvig.frc.spotter.agent;

/**
 * How much the agent reads and sends: every bound in one place, so tests can set them small. A
 * probe's own bounds (its output, its answer, its time) are {@link ProbeRunner}'s and its
 * definition's; a download's, its definition's.
 *
 * @param maxFile the most of any one file read, in bytes: sysfs and procfs files are far smaller
 * @param maxRead the most of a command's output read, in characters: a journal page
 * @param maxAnswer the most a JSON answer may be, in bytes
 */
record Limits(int maxFile, int maxRead, int maxAnswer) {
  /** The agent's limits on a coprocessor. */
  static final Limits DEFAULT = new Limits(256 * 1024, 4 * 1024 * 1024, 4 * 1024 * 1024);
}
