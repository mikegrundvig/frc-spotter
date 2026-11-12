//! Packs checked off any board, by the agent's own reader: what the command line's `check` runs,
//! so a pack's mistakes show where its author is (a laptop, the robot program's build), not on a
//! board. Every problem the agent would list, each with its file and line, and the bounds a board
//! holds packs to; and mistakes the agent takes, but a pack shouldn't have: a program of the
//! pack's own that isn't there or won't run once pushed, a `.` in an id or a field's name, and
//! `equals` or `notEquals` on a `status` field. And notes, which are no problem. The trust check
//! (root's, and written by root alone) is skipped: it means something only on a board.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

use super::load;
use super::pack::{self, Pack};
use super::reader::{self, Checks};
use crate::board::host::MAX_FILE;
use crate::protocol::pack_hash;

/// What a check found.
pub struct Report {
    /// How many packs it read.
    pub packs: usize,
    /// Every problem, each its file and line first: none when the packs pass.
    pub problems: Vec<String>,
    /// What's worth knowing, and no problem.
    pub notes: Vec<String>,
}

impl Report {
    /// Whether the packs pass: no problem.
    pub fn passed(&self) -> bool {
        self.problems.is_empty()
    }
}

/// What's said of the trust check, which a check off a board skips.
pub const TRUST_SKIPPED: &str = "the trust check (root's, and written by root alone) is skipped: it means something only on a board";

/// Checks packs: each folder a pack (`<name>/pack.yaml`), or a folder of them, as the robot
/// program's packs folder is; all of them together held to a board's bounds.
pub fn check(folders: &[PathBuf]) -> Report {
    let mut problems = Vec::new();
    let mut notes = Vec::new();
    let mut read = Vec::new();
    for folder in folders {
        if folder.join(pack::FILE).is_file() {
            one(folder, &mut read, &mut problems, &mut notes);
        } else if !folder.is_dir() {
            problems.push(format!("{}: no such folder", show(folder)));
        } else {
            for each in entries(folder, &mut problems) {
                if !each.is_dir() {
                    problems.push(format!(
                        "{}: not a pack (a pack is a folder, <name>/pack.yaml), ignored",
                        show(&each)
                    ));
                } else if !each.join(pack::FILE).is_file() {
                    problems.push(format!("{}: has no {}, ignored", show(&each), pack::FILE));
                } else {
                    one(&each, &mut read, &mut problems, &mut notes);
                }
            }
        }
    }
    // As one board would hold them all: its bounds on packs, values, actions, declarations.
    let count = read.len();
    load::bounded(read, &mut problems);
    notes.push(TRUST_SKIPPED.to_string());
    Report {
        packs: count,
        problems,
        notes,
    }
}

fn entries(folder: &Path, problems: &mut Vec<String>) -> Vec<PathBuf> {
    match fs::read_dir(folder) {
        Ok(each) => {
            let mut paths: Vec<PathBuf> = each.flatten().map(|entry| entry.path()).collect();
            paths.sort();
            paths
        }
        Err(e) => {
            problems.push(format!("{}: can't be read: {e}", show(folder)));
            Vec::new()
        }
    }
}

/// The checks a check off a board makes beyond the reader's own.
struct OffBoard<'a> {
    folder: &'a Path,
    notes: &'a mut Vec<String>,
}

impl Checks for OffBoard<'_> {
    fn program(&self, relative: &str) -> Option<String> {
        program(self.folder, relative)
    }

    fn note(&mut self, note: String) {
        self.notes.push(note);
    }
}

fn one(folder: &Path, read: &mut Vec<Pack>, problems: &mut Vec<String>, notes: &mut Vec<String>) {
    let path = show(folder);
    let file = format!("{path}/{}", pack::FILE);
    let mut bytes = Vec::new();
    let opened = fs::File::open(folder.join(pack::FILE))
        .and_then(|input| input.take(MAX_FILE as u64 + 1).read_to_end(&mut bytes));
    if let Err(e) = opened {
        problems.push(format!("{file}: can't be read: {e}"));
        return;
    }
    if bytes.len() > MAX_FILE {
        problems.push(format!(
            "{file}: more than the {} KiB read",
            MAX_FILE / 1024
        ));
        return;
    }
    let yaml = String::from_utf8_lossy(&bytes);
    let mut checks = OffBoard { folder, notes };
    match reader::read_checked(&yaml, &path, true, Some(&mut checks)) {
        Ok(pack) => read.push(pack),
        Err(found) => problems.extend(found),
    }
}

/// Why a pack's own program (`./name`) won't run once pushed: it isn't there, or it has neither an
/// execute bit nor a `#!` first line, which a push carries as executable; none when it will.
pub fn program(folder: &Path, relative: &str) -> Option<String> {
    let program = folder.join(relative);
    if !program.is_file() {
        return Some(format!("./{relative} isn't in the pack's folder"));
    }
    match pack_hash::executable(&program) {
        Ok(true) => None,
        Ok(false) => Some(format!(
            "./{relative} has neither an execute bit nor a #! first line, so it won't run once pushed: chmod +x it, start it with #!, or run: [sh, ./{relative}]"
        )),
        Err(e) => Some(format!("./{relative} can't be read: {e}")),
    }
}

/// A path as a problem names it: with `/`, on any computer.
fn show(path: &Path) -> String {
    path.to_string_lossy().replace('\\', "/")
}
