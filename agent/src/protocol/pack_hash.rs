//! The hash of a folder of packs, which the robot's manager and the agent compute alike
//! (`PackHash` on the manager's side) to tell whether a board has the robot's packs: SHA-256 over
//! each file's path (relative, with `/`), its permissions (`755` or `644`), its length and its
//! bytes, in the order of their paths, as lowercase hex. A link in the folder is no file of its
//! packs': it's left out, never followed. No files hash to the empty text.

use std::fs;
use std::io::{self, Read};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use sha2::{Digest, Sha256};

use crate::proto::PackBundle;

/// The permissions an executable file is hashed with, and written with.
pub const EXECUTABLE: &str = "755";

/// The permissions any other file is hashed with, and written with.
pub const PLAIN: &str = "644";

/// The hash of a folder of packs; empty when it has no files, or isn't there.
pub fn of_folder(folder: &Path) -> io::Result<String> {
    let paths = files(folder)?;
    if paths.is_empty() {
        return Ok(String::new());
    }
    let real = fs::canonicalize(folder)?;
    let mut digest = Sha256::new();
    let mut chunk = vec![0u8; 64 * 1024];
    for path in &paths {
        let file = real.join(path);
        let length = fs::metadata(&file)?.len();
        let permissions = if executable(&file)? {
            EXECUTABLE
        } else {
            PLAIN
        };
        header(&mut digest, path, permissions, length);
        let mut input = fs::File::open(&file)?;
        loop {
            let read = input.read(&mut chunk)?;
            if read == 0 {
                break;
            }
            digest.update(&chunk[..read]);
        }
    }
    Ok(hex(&digest.finalize()))
}

/// The hash of a bundle of packs, from its files, as [`of_folder`] hashes them on a disk.
pub fn of_bundle(bundle: &PackBundle) -> String {
    if bundle.files.is_empty() {
        return String::new();
    }
    let mut files: Vec<_> = bundle.files.iter().collect();
    files.sort_by(|a, b| java_order(&a.path, &b.path));
    let mut digest = Sha256::new();
    for file in files {
        let permissions = if file.executable { EXECUTABLE } else { PLAIN };
        header(
            &mut digest,
            &file.path,
            permissions,
            file.content.len() as u64,
        );
        digest.update(&file.content);
    }
    hex(&digest.finalize())
}

/// A folder's regular files, by path in it with `/`, sorted as Java sorts strings; none when it
/// isn't there. The folder itself may be a link (the agent swaps its pushed packs in by one); a
/// link in it is never followed.
fn files(folder: &Path) -> io::Result<Vec<String>> {
    let mut paths = Vec::new();
    if !folder.is_dir() {
        return Ok(paths);
    }
    let real = fs::canonicalize(folder)?;
    walk(&real, "", &mut paths)?;
    paths.sort_by(|a, b| java_order(a, b));
    Ok(paths)
}

fn walk(folder: &Path, prefix: &str, paths: &mut Vec<String>) -> io::Result<()> {
    for entry in fs::read_dir(folder)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().into_owned();
        let relative = if prefix.is_empty() {
            name
        } else {
            format!("{prefix}/{name}")
        };
        let kind = entry.file_type()?;
        if kind.is_dir() {
            walk(&entry.path(), &relative, paths)?;
        } else if kind.is_file() {
            paths.push(relative);
        }
    }
    Ok(())
}

/// Strings in the order Java's `String.compareTo` puts them, by UTF-16 code unit, as the
/// manager sorts the paths it hashes: the same as by bytes for every path but those with
/// characters past U+FFFF.
pub fn java_order(a: &str, b: &str) -> std::cmp::Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

fn header(digest: &mut Sha256, path: &str, permissions: &str, length: u64) {
    digest.update(path.as_bytes());
    digest.update([0u8]);
    digest.update(permissions.as_bytes());
    digest.update([0u8]);
    digest.update(length.to_string().as_bytes());
    digest.update([0u8]);
}

/// Whether a pack's file is executable, as a push carries it: any execute bit is set, or it starts
/// with `#!`, so a script whose execute bit a copy dropped still runs once pushed.
pub fn executable(file: &Path) -> io::Result<bool> {
    if fs::metadata(file)?.permissions().mode() & 0o111 != 0 {
        return Ok(true);
    }
    let mut start = [0u8; 2];
    let mut input = fs::File::open(file)?;
    let mut read = 0;
    while read < 2 {
        let more = input.read(&mut start[read..])?;
        if more == 0 {
            break;
        }
        read += more;
    }
    Ok(read == 2 && &start == b"#!")
}

/// Bytes as lowercase hex.
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}
