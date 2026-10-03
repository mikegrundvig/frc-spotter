package com.michaelgrundvig.frc.spotter.harness;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that runs a coprocessor's agent in a container (Docker, or rootless Podman),
 * with a test client of protocol 2 playing the robot. {@code ./gradlew test -Pquick} leaves these
 * out unless {@code --tests} names them; {@code ./gradlew ci} runs them. Without a container
 * runtime running Linux containers they skip themselves, as on a Windows laptop, but never in CI on
 * Linux.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("container")
@ExtendWith(ContainerRuntime.class)
public @interface ContainerTest {}
