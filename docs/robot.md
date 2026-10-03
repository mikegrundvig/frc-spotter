# The coprocessors, from the robot

The robot program's manager is coming. It will be given the agents' addresses (and, optionally, the
team's packs to push), keep each agent's stream open, judge each value against its limits, raise an
alert per value that's warning or failing and per board that goes missing, run actions with their
logs and responses, and power boards down.

0.3's client (`client/`, `com.michaelgrundvig.frc:spotter-client`), which polled protocol 1, is
gone with it. What the manager will be built on is already here: `:protocol`
(`com.michaelgrundvig.frc:spotter-protocol`), with `spotter.proto`'s generated messages, the
protocol's paths and headers (`Protocol`), and the pack hash both sides compute (`PackHash`).
`docs/agent.md` is protocol 2 in full.
