package com.michaelgrundvig.frc.spotter.manager;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * What the manager needs of the robot, which robot code supplies: each read whenever the manager
 * checks it, from the robot loop or the manager's own threads, so each must be safe to read from
 * any thread (WPILib's are). With WPILib 2027:
 *
 * <pre>{@code
 * new Robot(RobotState::isEnabled, RobotState::isFMSAttached, RobotController::getTime)
 * }</pre>
 *
 * @param enabled whether the robot is enabled
 * @param fieldAttached whether the field system is attached
 * @param nanos the robot's clock, in nanoseconds: the manager has no clock of its own, so when a
 *     board was last heard from, whether it's missing, and when each value was sent are all on this
 *     one (a simulation's, or a replay's, as much as the robot's)
 */
public record Robot(BooleanSupplier enabled, BooleanSupplier fieldAttached, LongSupplier nanos) {}
