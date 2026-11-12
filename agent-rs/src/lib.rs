//! Spotter's agent: a small web service each coprocessor on an FRC robot runs, speaking
//! Spotter's protocol 2 to the robot program's manager (docs/agent.md). It's a runner: it runs what
//! its packs declare (collectors, logs, actions) and measures nothing itself. The same program is
//! Spotter's command line (docs/tools.md): `check` packs, and `status` of a board.

pub mod actions;
pub mod agent;
pub mod board;
pub mod commands;
pub mod configuration;
pub mod logs;
pub mod packs;
pub mod proto;
pub mod protocol;
pub mod serve;
pub mod server;
pub mod tools;
pub mod values;

#[cfg(test)]
pub mod testing;
