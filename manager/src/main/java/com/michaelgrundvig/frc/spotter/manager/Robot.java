package com.michaelgrundvig.frc.spotter.manager;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * What the manager needs of the robot, which robot code supplies: each read whenever the manager
 * checks it, from the robot loop or the manager's own threads, so each must be safe to read from
 * any thread (WPILib's are).
 *
 * @param enabled whether the robot is enabled ({@code DriverStation::isEnabled})
 * @param fieldAttached whether the field system is attached ({@code DriverStation::isFMSAttached})
 * @param nanos the robot's clock, in nanoseconds (WPILib's time, in microseconds, times 1000): the
 *     manager has no clock of its own, so when a board was last heard from, whether it's missing,
 *     and when each value was sent are all on this one
 */
public record Robot(BooleanSupplier enabled, BooleanSupplier fieldAttached, LongSupplier nanos) {}
