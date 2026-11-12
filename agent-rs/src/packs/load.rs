//! The packs a board has, read once as the agent starts (a change takes a restart): those
//! installed with it, in [`INSTALLED`], and those the robot pushed, in [`PUSHED`]. A pushed pack
//! wins over an installed one of the same name: the robot's repository is the source of truth, and
//! the description's packs say which was pushed.
//!
//! An installed pack is trusted by its file, as `sshd` trusts its configuration: one whose
//! `pack.yaml` isn't root's, or that its group or anyone else may write, is ignored, and so is one
//! in a folder that isn't, any folder on its way from `/`. So is a collector, log or action whose
//! program anyone but root could change, or that isn't there, when it names it by path. A pushed
//! pack is trusted because only the robot controller may push.
//!
//! Bounded, so a board's description always fits a stream's event: at most [`MAX_PACKS`] packs,
//! the protocol's most values and actions, and [`MAX_DECLARED`] bytes of declarations. Packs are
//! taken in the order of their names, and one that would pass a bound is ignored whole.

use std::collections::BTreeMap;
use std::io;

use super::command::Command;
use super::last_good;
use super::pack::{self, Pack};
use super::reader;
use crate::board::host::{Host, Owner};
use crate::protocol::{self, pack_hash};

/// Where the packs installed with the board are, each a folder.
pub const INSTALLED: &str = "/etc/frc-spotter/packs";

/// Where the packs the robot pushed are, each a folder.
pub const PUSHED: &str = "/var/lib/frc-spotter/packs";

/// The most packs a board may have.
pub const MAX_PACKS: usize = 32;

/// The most bytes a board's packs may declare, as its description carries them: half a stream's
/// event, the rest for its identity and problems.
pub const MAX_DECLARED: usize = protocol::MAX_EVENT / 2;

/// The packs read, and what was ignored.
#[derive(Clone, Debug, Default)]
pub struct Loaded {
    /// Every pack that loaded, by name.
    pub packs: Vec<Pack>,
    /// What was ignored, and why.
    pub problems: Vec<String>,
    /// The hash of [`PUSHED`] as it is on disk; empty when there's nothing there, or the board
    /// refuses pushes.
    pub pushed_hash: String,
}

/// Reads the board's packs: its installed ones, and its pushed ones unless it refuses them (why, in
/// `refused`; empty when it takes them) or sets them aside for this start (the last good start).
pub fn load(host: &Host, refused: &str, set_aside: bool) -> Loaded {
    let mut problems = Vec::new();
    let installed = folder(host, INSTALLED, false, &mut problems);
    let mut pushed = BTreeMap::new();
    let mut hash = String::new();
    if refused.is_empty() {
        if set_aside {
            problems.push(last_good::set_aside());
        } else {
            pushed = folder(host, PUSHED, true, &mut problems);
        }
        match pack_hash::of_folder(&host.path(PUSHED)) {
            Ok(of) => hash = of,
            Err(e) => problems.push(format!("{PUSHED}: can't be read to hash it: {e}")),
        }
    } else if host.list(PUSHED).is_ok_and(|names| !names.is_empty()) {
        problems.push(format!("{PUSHED}: ignored, as {refused}"));
    }
    let mut all: BTreeMap<String, Pack> = BTreeMap::new();
    for (name, pack) in installed {
        if let Some(loaded) = pack {
            all.insert(name, loaded);
        }
    }
    // The documented rule, not a problem: the description's packs say which is pushed.
    for (name, pack) in pushed {
        all.remove(&name);
        if let Some(loaded) = pack {
            all.insert(name, loaded);
        }
    }
    let mut ordered: Vec<Pack> = all.into_values().collect();
    ordered.sort_by(|a, b| pack_hash::java_order(&a.name, &b.name));
    let packs = bounded(ordered, &mut problems);
    Loaded {
        packs,
        problems,
        pushed_hash: hash,
    }
}

/// The packs, in order, as many as a board may have: one that would pass a bound is ignored.
pub fn bounded(packs: Vec<Pack>, problems: &mut Vec<String>) -> Vec<Pack> {
    let mut kept = Vec::new();
    let mut values = 0usize;
    let mut actions = pack::built_in().len();
    let mut declared = 0usize;
    for each in packs {
        let its = each.values();
        let size = each.declared_size();
        let past = if kept.len() == MAX_PACKS {
            format!("a board has at most {MAX_PACKS} packs")
        } else if values + its > protocol::MAX_VALUES {
            format!(
                "its {its} values would pass the {} a board may have ({values} so far)",
                protocol::MAX_VALUES
            )
        } else if actions + each.actions.len() > protocol::MAX_ACTIONS {
            format!(
                "its {} actions would pass the {} a board may have ({actions} so far)",
                each.actions.len(),
                protocol::MAX_ACTIONS
            )
        } else if declared + size > MAX_DECLARED {
            format!(
                "its declarations ({} KiB) would pass the {} KiB a board's description may hold",
                size / 1024,
                MAX_DECLARED / 1024
            )
        } else {
            String::new()
        };
        if !past.is_empty() {
            problems.push(format!("{}: ignored, as {past}", each.folder));
            continue;
        }
        values += its;
        actions += each.actions.len();
        declared += size;
        kept.push(each);
    }
    kept
}

/// Every pack folder in a folder, by name: each one loaded, or none when it was ignored (and
/// `problems` says why). Anything there that isn't a pack's folder is ignored too.
fn folder(
    host: &Host,
    folder: &str,
    pushed: bool,
    problems: &mut Vec<String>,
) -> BTreeMap<String, Option<Pack>> {
    let mut packs = BTreeMap::new();
    let names = match host.list(folder) {
        Ok(names) => names,
        Err(e) => {
            problems.push(format!("{folder}: can't be read: {e}"));
            return packs;
        }
    };
    for name in names {
        let path = format!("{folder}/{name}");
        if !host.is_folder(&path) {
            problems.push(format!(
                "{path}: not a pack (a pack is a folder, <name>/pack.yaml), ignored"
            ));
            continue;
        }
        packs.insert(name, one(host, &path, pushed, problems));
    }
    packs
}

fn one(host: &Host, folder: &str, pushed: bool, problems: &mut Vec<String>) -> Option<Pack> {
    let file = format!("{folder}/{}", pack::FILE);
    match read_one(host, folder, &file, pushed, problems) {
        Ok(pack) => pack,
        Err(e) => {
            problems.push(format!("{file}: can't be read: {e}, ignored"));
            None
        }
    }
}

/// One pack, trusted or not, its problems said; an error only when its files can't be read.
fn read_one(
    host: &Host,
    folder: &str,
    file: &str,
    pushed: bool,
    problems: &mut Vec<String>,
) -> io::Result<Option<Pack>> {
    if !host.exists(file) {
        problems.push(format!("{folder}: has no {}, ignored", pack::FILE));
        return Ok(None);
    }
    if !pushed {
        let untrusted = untrusted(host, file)?;
        if !untrusted.is_empty() {
            problems.push(format!("{file}: {untrusted}, ignored"));
            return Ok(None);
        }
    }
    let Some(text) = host.read(file)? else {
        problems.push(format!("{file}: gone as it was read, ignored"));
        return Ok(None);
    };
    match reader::read(&text, folder, pushed) {
        Ok(pack) if pushed => Ok(Some(pack)),
        Ok(pack) => Ok(Some(trusted(host, pack, problems)?)),
        Err(found) => {
            problems.extend(
                found
                    .into_iter()
                    .map(|p| format!("{p} (the pack is ignored)")),
            );
            Ok(None)
        }
    }
}

/// An installed pack with only the collectors, logs and actions whose programs root alone may
/// change: any program it names by path, as it is now. A program that isn't there isn't run: what
/// comes to be there later was never checked.
fn trusted(host: &Host, pack: Pack, problems: &mut Vec<String>) -> io::Result<Pack> {
    let mut collectors = Vec::new();
    for collector in &pack.collectors {
        if runs(
            host,
            &pack,
            &collector.command,
            &format!("collector {}", collector.id),
            problems,
        )? {
            collectors.push(collector.clone());
        }
    }
    let mut logs = Vec::new();
    for log in &pack.logs {
        if runs(host, &pack, &log.run, &format!("log {}", log.id), problems)? {
            logs.push(log.clone());
        }
    }
    let mut actions = Vec::new();
    for action in &pack.actions {
        if runs(
            host,
            &pack,
            &action.command,
            &format!("action {}", action.id),
            problems,
        )? {
            actions.push(action.clone());
        }
    }
    Ok(Pack {
        collectors,
        logs,
        actions,
        pushed: false,
        ..pack
    })
}

fn runs(
    host: &Host,
    pack: &Pack,
    command: &Command,
    what: &str,
    problems: &mut Vec<String>,
) -> io::Result<bool> {
    for program in command.programs_by_path(&pack.folder) {
        if !host.exists(&program) {
            problems.push(format!(
                "{program}: isn't there, so {}'s {what} isn't run",
                pack.name
            ));
            return Ok(false);
        }
        let untrusted = untrusted(host, &program)?;
        if !untrusted.is_empty() {
            problems.push(format!(
                "{program}: {untrusted}, so {}'s {what} isn't run",
                pack.name
            ));
            return Ok(false);
        }
    }
    Ok(true)
}

/// Why a file isn't to be trusted; empty when root alone may change it, and every folder on its
/// way from `/`, and on the way to where it leads when it's a link: a folder anyone else may write
/// lets them put another file in its place.
fn untrusted(host: &Host, file: &str) -> io::Result<String> {
    let why = untrusted_owner(&host.owner(file)?);
    if !why.is_empty() {
        return Ok(why);
    }
    let mut folders = folders(file);
    for folder in self::folders(&host.real_path(file)?) {
        if !folders.contains(&folder) {
            folders.push(folder);
        }
    }
    for folder in folders {
        let why = untrusted_owner(&host.owner(&folder)?);
        if !why.is_empty() {
            return Ok(format!("its folder {folder} {why}"));
        }
    }
    Ok(String::new())
}

/// The folders a path is in, from its own up to `/`.
fn folders(path: &str) -> Vec<String> {
    let mut folders = Vec::new();
    let mut folder = path;
    while let Some(at) = folder.rfind('/').filter(|at| *at > 0) {
        folder = &folder[..at];
        folders.push(folder.to_string());
    }
    folders.push("/".to_string());
    folders
}

/// Why a file isn't to be trusted; empty when root alone may change it.
fn untrusted_owner(owner: &Owner) -> String {
    if !owner.is_root() {
        return format!("isn't root's (its owner is {})", owner.user);
    }
    if owner.writable_by_others() {
        return format!(
            "may be written by its group or others (mode {:o})",
            owner.mode
        );
    }
    String::new()
}
