/**
 * Following a board: its connection and stream, on a thread of its own ({@link Link}), decoded into
 * the buffers the robot loop takes without a lock ({@link Exchange}, {@link Table}), timed on the
 * robot's clock ({@link ClockMap}). The manager's machinery: robot code uses the package above.
 */
package com.michaelgrundvig.frc.spotter.manager.stream;
