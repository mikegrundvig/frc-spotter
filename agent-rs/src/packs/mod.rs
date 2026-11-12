//! Packs: folders, `<name>/pack.yaml` beside whatever its commands run, that declare collectors,
//! logs and actions. Read once as the agent starts, checked, bounded, and trusted or not; pushed
//! by the robot and swapped in at once; and checked off any board by the command line.

pub mod check;
pub mod command;
pub mod duration;
pub mod field;
pub mod last_good;
pub mod load;
pub mod pack;
pub mod push;
pub mod reader;
pub mod yaml;
