//! `check <folder>...`: packs, read as the agent reads them, on any computer.

use std::io::Write;
use std::path::PathBuf;

use super::{FOUND, OK, USAGE};
use crate::packs::check;

pub fn run(folders: &[String], out: &mut dyn Write, err: &mut dyn Write) -> u8 {
    if folders.is_empty() {
        let _ = writeln!(
            err,
            "frc-spotter: check needs a folder: a pack, or a folder of packs"
        );
        return USAGE;
    }
    let paths: Vec<PathBuf> = folders.iter().map(PathBuf::from).collect();
    let report = check::check(&paths);
    for problem in &report.problems {
        let _ = writeln!(out, "{problem}");
    }
    for note in &report.notes {
        let _ = writeln!(out, "note: {note}");
    }
    let packs = if report.packs == 1 { "pack" } else { "packs" };
    let found = match report.problems.len() {
        0 => "no problems".to_string(),
        1 => "1 problem".to_string(),
        many => format!("{many} problems"),
    };
    let _ = writeln!(out, "{} {packs} checked: {found}", report.packs);
    if report.passed() { OK } else { FOUND }
}
