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

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use super::*;

    fn pack(dir: &Path, name: &str, yaml: &str) -> PathBuf {
        let folder = dir.join("packs").join(name);
        fs::create_dir_all(&folder).expect("its folder");
        fs::write(folder.join(pack::FILE), yaml).expect("its pack.yaml");
        folder
    }

    #[test]
    fn a_good_pack_passes_with_its_notes() {
        let dir = tempfile::tempdir().expect("a folder");
        let folder = pack(
            dir.path(),
            "web",
            "pack: web\ncollectors:\n  - id: page\n    http: {get: \"http://localhost:5800/\"}\n    every: 1s\n    fields:\n      status: {type: number}\n",
        );
        let report = check(std::slice::from_ref(&folder));
        assert!(report.passed(), "{:?}", report.problems);
        assert_eq!(report.packs, 1);
        assert_eq!(
            report.notes,
            [
                format!(
                    "{}/pack.yaml:7: collector page's field status is its command's own exit code or HTTP status, not a key of its output",
                    show(&folder)
                ),
                TRUST_SKIPPED.to_string(),
            ]
        );
    }

    #[test]
    fn a_program_of_the_packs_own_must_run_once_pushed() {
        let dir = tempfile::tempdir().expect("a folder");
        let folder = pack(dir.path(), "p", "pack: p\n");
        fs::write(folder.join("plain"), "echo 1\n").expect("plain");
        fs::write(folder.join("script"), "#!/bin/sh\necho 1\n").expect("script");
        fs::write(folder.join("binary"), "\u{7f}ELF").expect("binary");
        fs::set_permissions(folder.join("binary"), fs::Permissions::from_mode(0o755))
            .expect("its mode");
        let plain = program(&folder, "plain").expect("plain won't run");
        assert!(plain.contains("neither an execute bit nor a #!"), "{plain}");
        assert_eq!(program(&folder, "script"), None);
        assert_eq!(program(&folder, "binary"), None);
        assert_eq!(
            program(&folder, "nothing"),
            Some("./nothing isn't in the pack's folder".to_string())
        );
    }

    #[test]
    fn a_folder_of_packs_is_held_to_a_boards_bounds() {
        let dir = tempfile::tempdir().expect("a folder");
        let mut actions = "actions:\n".to_string();
        for i in 0..32 {
            actions.push_str(&format!("  - {{id: a{i}, run: [true]}}\n"));
        }
        for i in 0..4 {
            pack(
                dir.path(),
                &format!("a{i}"),
                &format!("pack: a{i}\n{actions}"),
            );
        }
        let report = check(&[dir.path().join("packs")]);
        assert!(!report.passed());
        assert_eq!(
            report.problems,
            [format!(
                "{}: ignored, as its 32 actions would pass the 128 a board may have (98 so far)",
                show(&dir.path().join("packs").join("a3"))
            )]
        );
    }
}
