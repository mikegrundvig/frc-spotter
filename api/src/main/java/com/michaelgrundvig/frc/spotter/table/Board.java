package com.michaelgrundvig.frc.spotter.table;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A coprocessor board the image is built for. Every one is an RK3588 or RK3588S, with four
 * Cortex-A76 cores for PhotonVision and four small cores for everything else.
 */
public enum Board {
  /** Orange Pi 5 (RK3588S): SPI flash and an M.2 slot for an NVMe drive. */
  ORANGEPI_5("orangepi-5"),
  /** Orange Pi 5B (RK3588S): soldered eMMC, no M.2 slot. */
  ORANGEPI_5B("orangepi-5b"),
  /** Orange Pi 5 Plus (RK3588): SPI flash and an M.2 slot. */
  ORANGEPI_5_PLUS("orangepi-5-plus"),
  /** Orange Pi 5 Pro (RK3588S): an eMMC module or SPI flash, and an M.2 slot. */
  ORANGEPI_5_PRO("orangepi-5-pro"),
  /** Orange Pi 5 Max (RK3588): SPI flash and an M.2 slot. */
  ORANGEPI_5_MAX("orangepi-5-max");

  private final String id;

  Board(String id) {
    this.id = id;
  }

  /** The board's name in coprocessors.yaml, such as {@code orangepi-5}. */
  public String id() {
    return id;
  }

  /** The board with this name in coprocessors.yaml, if there is one. */
  public static Optional<Board> byId(String id) {
    return Arrays.stream(values()).filter(board -> board.id.equals(id)).findFirst();
  }

  /** Every board's name, for messages: {@code orangepi-5, orangepi-5b, ...}. */
  public static String ids() {
    return Arrays.stream(values()).map(Board::id).collect(Collectors.joining(", "));
  }

  @Override
  public String toString() {
    return id;
  }
}
