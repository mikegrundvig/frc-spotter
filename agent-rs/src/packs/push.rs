//! Packs the robot pushes: a bundle of pack folders (spotter.proto's `PackBundle`: each file's
//! path, whether it's executable, and its bytes, exactly what the pack hash covers), put in place
//! in one step. Its files are written into a folder of their own beside the others, `755` or `644`
//! as the bundle says; then [`PUSHED`], a link, is renamed over to point at it, which is atomic. A
//! push that fails partway (a full disk, say) leaves the packs as they were. The agent then exits,
//! and systemd starts it again, reading the new packs.

use std::collections::HashSet;
use std::fs;
use std::io::{self, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt, symlink};
use std::path::{Component, Path, PathBuf};

use prost::Message;

use super::last_good;
use super::load::PUSHED;
use crate::board::host::Host;
use crate::proto::PackBundle;
use crate::protocol::{self, pack_hash, wire_check};

/// Where pushed bundles are unpacked, each in a folder of its own.
pub const BUNDLES: &str = "/var/lib/frc-spotter/pushed";

/// The start of the name packs put there by hand are kept aside under, among the bundles.
const BEFORE: &str = "before-";

/// Why a push failed.
#[derive(Debug)]
pub enum Refusal {
    /// A bundle the agent doesn't take (`400`), and why.
    Rejected(String),
    /// It couldn't be written (`500`): the packs are as they were.
    Failed(io::Error),
}

impl From<io::Error> for Refusal {
    fn from(e: io::Error) -> Refusal {
        Refusal::Failed(e)
    }
}

/// A bundle's name: 16 random hex digits.
pub fn random_id() -> String {
    let mut some = [0u8; 8];
    getrandom::fill(&mut some).expect("the system's random numbers");
    pack_hash::hex(&some)
}

/// A file to take a bundle into, beside the packs: the request's body, read to its end first.
pub fn bundle_file(host: &Host) -> io::Result<PathBuf> {
    let bundles = host.path(BUNDLES);
    fs::create_dir_all(&bundles)?;
    let file = bundles.join(format!(".bundle-{}.pb", random_id()));
    fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&file)?;
    Ok(file)
}

/// Puts a bundle's packs in place, in one step, its files named by `id`. Once it returns they're in
/// place, and nothing after the swap can fail it: the agent is to restart to read them.
pub fn apply(host: &Host, received: &Path, id: &str) -> Result<(), Refusal> {
    let bundles = host.path(BUNDLES);
    let unpacked = bundles.join(id);
    let packs = host.path(PUSHED);
    let mut aside = None;
    let swapped = (|| -> Result<String, Refusal> {
        let hash = unpack(received, &unpacked)?;
        if fs::symlink_metadata(&packs).is_ok_and(|m| m.is_dir()) {
            // Packs put there by hand, before any push: kept aside, as a bundle of their own.
            let kept = bundles.join(format!("{BEFORE}{id}"));
            fs::rename(&packs, &kept)?;
            aside = Some(kept);
        }
        let parent = packs.parent().expect("the packs' folder");
        let link = parent.join(format!(".packs-{id}"));
        let target = relative(parent, &unpacked);
        symlink(&target, &link)?;
        fs::rename(&link, &packs)?;
        Ok(hash)
    })();
    let _ = fs::remove_file(received);
    match swapped {
        Ok(hash) => {
            host.log(&format!("Packs pushed ({hash}): restarting to read them"));
            last_good::pushed(host);
            tidy(host);
            Ok(())
        }
        Err(e) => {
            if let Some(kept) = aside {
                if let Err(back) = fs::rename(&kept, &packs) {
                    host.log(&format!(
                        "Couldn't put the packs back from {} after a failed push: {back}",
                        kept.display()
                    ));
                }
            }
            delete(host, &unpacked);
            Err(e)
        }
    }
}

/// A path from a folder to a file within the same tree, as a link names it: `pushed/<id>` from
/// `/var/lib/frc-spotter`.
fn relative(from: &Path, to: &Path) -> PathBuf {
    match to.strip_prefix(from) {
        Ok(inside) => inside.to_path_buf(),
        Err(_) => to.to_path_buf(),
    }
}

/// Removes the bundles the packs don't point at: older ones, and any left half unpacked. Packs put
/// there by hand and kept aside by the first push stay.
pub fn tidy(host: &Host) {
    let bundles = host.path(BUNDLES);
    let packs = host.path(PUSHED);
    let current = if fs::symlink_metadata(&packs).is_ok_and(|m| m.file_type().is_symlink()) {
        fs::canonicalize(&packs).ok()
    } else {
        None
    };
    let Ok(entries) = fs::read_dir(&bundles) else {
        return;
    };
    for entry in entries.flatten() {
        if entry.file_name().to_string_lossy().starts_with(BEFORE) {
            continue;
        }
        let path = entry.path();
        let real = fs::canonicalize(&path).ok();
        if current.is_none() || real != current {
            delete(host, &path);
        }
    }
}

/// Writes a bundle's files into a folder: at most the protocol's most files, holding at most its
/// most bytes; each path relative, inside the folder, and named once; each file `755` or `644`, as
/// the bundle says. Its hash, as the folder written hashes.
fn unpack(file: &Path, into: &Path) -> Result<String, Refusal> {
    let bytes = fs::read(file)?;
    // Checked before it's parsed, so no list it holds costs more than its bytes.
    wire_check::check(&wire_check::PACK_BUNDLE, &bytes).map_err(|why| {
        Refusal::Rejected(format!(
            "the bundle isn't a PackBundle that can be read safely: {why}"
        ))
    })?;
    let bundle = PackBundle::decode(bytes.as_slice())
        .map_err(|e| Refusal::Rejected(format!("the bundle isn't a PackBundle: {e}")))?;
    drop(bytes);
    if bundle.files.len() > protocol::MAX_FILES {
        return Err(Refusal::Rejected(format!(
            "the bundle holds {} files, more than {}",
            bundle.files.len(),
            protocol::MAX_FILES
        )));
    }
    let mut total = 0u64;
    let mut written = HashSet::new();
    fs::create_dir_all(into)?;
    for each in &bundle.files {
        let target = target(into, &each.path)?;
        if !written.insert(target.clone()) {
            return Err(twice(&each.path));
        }
        total += each.content.len() as u64;
        if total > protocol::MAX_BUNDLE {
            return Err(Refusal::Rejected(format!(
                "the bundle holds more than {} MiB",
                protocol::MAX_BUNDLE / (1024 * 1024)
            )));
        }
        let parent = target.parent().expect("a file's folder");
        if let Err(e) = fs::create_dir_all(parent) {
            // A file where its folder would be: named twice.
            if e.kind() == io::ErrorKind::AlreadyExists || parent.is_file() {
                return Err(twice(&each.path));
            }
            return Err(e.into());
        }
        let mode = if each.executable { 0o755 } else { 0o644 };
        let mut out = match fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(mode)
            .open(&target)
        {
            Ok(out) => out,
            // A folder and a file of the same path, or one of them a link: either way, named twice.
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => return Err(twice(&each.path)),
            Err(e) if e.kind() == io::ErrorKind::NotADirectory => return Err(twice(&each.path)),
            Err(e) => return Err(e.into()),
        };
        out.write_all(&each.content)?;
        fs::set_permissions(&target, fs::Permissions::from_mode(mode))?;
    }
    folders_open(into)?;
    Ok(pack_hash::of_bundle(&bundle))
}

fn twice(path: &str) -> Refusal {
    Refusal::Rejected(format!("the bundle names a file twice: {path}"))
}

/// Every folder of an unpacked bundle `755`, whatever the umask.
fn folders_open(folder: &Path) -> io::Result<()> {
    fs::set_permissions(folder, fs::Permissions::from_mode(0o755))?;
    for entry in fs::read_dir(folder)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            folders_open(&entry.path())?;
        }
    }
    Ok(())
}

/// Where a bundle's file goes: its path relative, `/`-separated, each part a name (not empty, `.`
/// or `..`), inside the folder.
pub fn target(into: &Path, relative: &str) -> Result<PathBuf, Refusal> {
    let names = !relative.is_empty()
        && !relative.starts_with('/')
        && !relative.contains('\\')
        && relative
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != ".." && !part.contains('\0'));
    let outside = || {
        Refusal::Rejected(format!(
            "the bundle names a path outside its folder: {relative}"
        ))
    };
    if !names {
        return Err(outside());
    }
    let target = into.join(relative);
    if target
        .components()
        .any(|c| matches!(c, Component::ParentDir))
        || target == into
    {
        return Err(outside());
    }
    Ok(target)
}

fn delete(host: &Host, path: &Path) {
    let result = match fs::symlink_metadata(path) {
        Err(_) => return,
        Ok(metadata) if metadata.is_dir() => fs::remove_dir_all(path),
        Ok(_) => fs::remove_file(path),
    };
    if let Err(e) = result {
        host.log(&format!("Couldn't remove {}: {e}", path.display()));
    }
}
