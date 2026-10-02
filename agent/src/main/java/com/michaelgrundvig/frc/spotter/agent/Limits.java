package com.michaelgrundvig.frc.spotter.agent;

/**
 * How much the agent reads and sends: every bound in one place, so tests can set them small. The
 * defaults fit a 64 MB heap, as measured: PhotonVision's settings parse to about nine times their
 * text, so one value of 2 MB (a calibration is about 1 MB) parses within the heap beside 16 MB of
 * text held while it's sent. Values of 4 MB ran out of heap.
 *
 * @param maxFile the most of any one file read, in bytes: sysfs and procfs files are far smaller
 * @param maxRead the most of a command's output read, in characters: a journal page
 * @param maxAnswer the most a JSON answer may be, in bytes, but the settings'
 * @param maxDatabase the largest settings database read, in bytes of file
 * @param maxSettings the most settings text read, in characters, all values together
 * @param maxValue the longest one setting (a column's text) read, in characters
 */
record Limits(
    int maxFile, int maxRead, int maxAnswer, long maxDatabase, long maxSettings, int maxValue) {
  /** The agent's limits on a coprocessor. */
  static final Limits DEFAULT =
      new Limits(
          256 * 1024,
          4 * 1024 * 1024,
          4 * 1024 * 1024,
          32L * 1024 * 1024,
          16L * 1024 * 1024,
          2 * 1024 * 1024);
}
