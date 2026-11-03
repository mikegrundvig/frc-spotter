/**
 * Spotter's agent, on a coprocessor: {@link AgentMain} starts it, {@link Agent} wires its parts,
 * and {@link Configuration} is what it runs. Each package under it is one concern, building on
 * those before it: {@code board} (the computer), {@code packs}, {@code commands}, {@code values},
 * {@code actions}, {@code logs}, and {@code server} (the protocol).
 */
package com.michaelgrundvig.frc.spotter.agent;
