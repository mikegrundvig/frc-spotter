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

#[cfg(test)]
mod tests {
    use std::fs;
    use std::time::Duration;

    use super::*;
    use crate::packs::check::TRUST_SKIPPED;
    use crate::packs::duration;
    use crate::testing::catalog;

    /// What a run printed, said on its standard error, and exited with.
    struct Ran {
        code: u8,
        out: String,
        err: String,
    }

    fn ran(args: &[&str]) -> Ran {
        let args: Vec<String> = args.iter().map(|a| a.to_string()).collect();
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let code = run(&args, &mut out, &mut err);
        Ran {
            code,
            out: String::from_utf8_lossy(&out).into_owned(),
            err: String::from_utf8_lossy(&err).into_owned(),
        }
    }

    #[test]
    fn the_catalogs_packs_pass_the_check() {
        let debian = catalog().join("debian").display().to_string();
        let pi = catalog().join("raspberry-pi").display().to_string();
        let ran = ran(&["check", &debian, &pi]);
        assert_eq!(ran.code, OK, "{}", ran.out);
        assert!(
            ran.out.ends_with("2 packs checked: no problems\n"),
            "{}",
            ran.out
        );
        assert!(ran.out.contains(&format!("note: {TRUST_SKIPPED}")));
    }

    #[test]
    fn a_packs_mistakes_are_each_at_their_file_and_line_and_fail_the_check() {
        let dir = tempfile::tempdir().expect("a folder");
        let packs = dir.path().join("spotter-packs");
        let team = packs.join("team");
        fs::create_dir_all(&team).expect("its folder");
        let yaml = [
            "pack: team",
            "collectors:",
            "  - id: health.main",
            "    run: [./health]",
            "    every: 1s",
            "    fields:",
            "      mood: {type: status, warn: {equals: sad}}",
            "  - id: gone",
            "    run: [./missing]",
            "    evry: 1s",
            "",
        ];
        fs::write(team.join("pack.yaml"), yaml.join("\n")).expect("its pack.yaml");
        // No execute bit, and no #!: it won't run once pushed.
        fs::write(team.join("health"), "echo '{}'\n").expect("its program");
        fs::write(packs.join("README.md"), "the team's packs\n").expect("a readme");
        let ran = ran(&["check", &packs.display().to_string()]);
        assert_eq!(ran.code, FOUND);
        let file = format!("{}/pack.yaml", team.display());
        for said in [
            format!("{file}:3: collector id \"health.main\" has a dot"),
            format!("{file}:4: ./health has neither an execute bit nor a #! first line"),
            format!("{file}:7: equals on a status is never applied"),
            format!("{file}:9: ./missing isn't in the pack's folder"),
            format!("{file}:10: unknown key \"evry\""),
            "/spotter-packs/README.md: not a pack".to_string(),
            "0 packs checked: ".to_string(),
        ] {
            assert!(ran.out.contains(&said), "{said}\n{}", ran.out);
        }
    }

    #[test]
    fn a_command_it_doesnt_know_or_nothing_to_check_is_usage() {
        for args in [
            &[][..],
            &["check"],
            &["status"],
            &["status", "a", "b"],
            &["status", "a", "--wait=soon"],
        ] {
            assert_eq!(ran(args).code, USAGE, "{args:?}");
        }
        assert!(
            ran(&["checks"])
                .err
                .starts_with("frc-spotter: no command checks")
        );
        assert!(ran(&["help"]).out.contains("check <folder>..."));
        let dir = tempfile::tempdir().expect("a folder");
        let nothing = dir.path().join("nothing").display().to_string();
        assert!(ran(&["check", &nothing]).out.contains(": no such folder"));
    }

    #[test]
    fn its_durations_are_written_as_a_packs_are() {
        // --wait takes what a pack's every and timeout take: one grammar for every duration.
        let ms = Duration::from_millis;
        for (text, read) in [
            ("500ms", Some(ms(500))),
            ("1.5s", Some(ms(1500))),
            ("2m", Some(ms(120_000))),
            ("1h", Some(ms(3_600_000))),
            ("soon", None),
        ] {
            assert_eq!(duration::parse(text), read, "{text}");
        }
    }
}
