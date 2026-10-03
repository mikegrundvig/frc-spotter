# The coprocessors, from the robot

The robot program's manager is `:manager` (`com.michaelgrundvig.frc:spotter-manager`), plain Java 17
with nothing but `:protocol`. It's given the agents' addresses, what it needs of the robot (whether
it's enabled, the field, its clock), and optionally its settings and a recorder. It keeps each
agent's stream open on a thread of its own, judges each value against its limits (or robot code's,
by field id), and raises an alert, as data, per value that's warning or failing, per board that goes
missing, and per board on another major version of the protocol. The robot loop calls `update()`
once each loop and reads each board without waiting; in steady state neither side makes garbage.
Its javadoc has the details for now.

Running actions with their logs and responses, paging logs, pushing the team's packs, and signed
requests come next, and this page with them. 0.3's client (`client/`,
`com.michaelgrundvig.frc:spotter-client`), which polled protocol 1, is gone. `docs/agent.md` is
protocol 2 in full.
