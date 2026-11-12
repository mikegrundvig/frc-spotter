//! Spotter's command line, beside the agent in its program: `frc-spotter <command>`.
//!
//! - `check <folder>...`: reads packs with the agent's own reader, on any computer, with no
//!   board: every problem as `file:line: what`, and a non-zero exit on any, for a build and CI.
//! - `status <board>`: reads a board (its address, or `host:port`) as the robot's manager does:
//!   its problems first, then each value, then its identity and packs.
//!
//! What it prints is plain text on standard output, one fact a line; its usage and a failure to
//! run go to standard error. Its exit code: [`OK`] when all is well, [`FOUND`] for problems found
//! or a board not reached, [`USAGE`] for a command it doesn't know.

mod check;
mod judge;
mod status;

use std::io::Write;

/// All is well.
pub const OK: u8 = 0;
/// Problems found, or the board not reached.
pub const FOUND: u8 = 1;
/// Not a command it knows, or its arguments are wrong.
pub const USAGE: u8 = 2;

const USAGE_TEXT: &str = "Usage: frc-spotter <command>
  check <folder>...         Check packs as the agent reads them: each folder a pack, or
                            a folder of packs. Exits 1 on any problem.
  status <board> [--wait=5s]  A board's problems, values, identity and packs: its
                            address, or host:port (5808 unless given). Exits 1 if it
                            isn't reached.";

/// The commands, by their first word.
const COMMANDS: [&str; 4] = ["check", "status", "help", "--help"];

/// Whether a command line names one of these commands, rather than the agent's serve.
pub fn names(args: &[String]) -> bool {
    args.first()
        .is_some_and(|first| COMMANDS.contains(&first.as_str()))
}

/// Runs a command, printing to these: its exit code.
pub fn run(args: &[String], out: &mut dyn Write, err: &mut dyn Write) -> u8 {
    let Some(first) = args.first() else {
        let _ = writeln!(err, "{USAGE_TEXT}");
        return USAGE;
    };
    let rest = &args[1..];
    match first.as_str() {
        "check" => check::run(rest, out, err),
        "status" => status::run(rest, out, err),
        "help" | "--help" => {
            let _ = writeln!(out, "{USAGE_TEXT}");
            OK
        }
        other => {
            let _ = writeln!(err, "frc-spotter: no command {other}");
            let _ = writeln!(err, "{USAGE_TEXT}");
            USAGE
        }
    }
}
