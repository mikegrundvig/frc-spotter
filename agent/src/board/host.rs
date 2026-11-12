//! The computer the agent runs on: its files, from a root (`/` on a board, a folder in tests), who
//! owns them, its network addresses, its monotonic clock, and the log the agent writes to. Every
//! path the agent uses is written as on the board (`/etc/frc-spotter/packs`) and found under the
//! root, so tests run against any tree.

use std::fs;
use std::io::{self, Read};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use super::log::escaped;

/// The most addresses read: a coprocessor has one or two.
pub const MAX_ADDRESSES: usize = 16;

/// The most of any one file [`Host::read`] reads, in bytes.
pub const MAX_FILE: usize = 256 * 1024;

/// A file's owner and permissions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Owner {
    /// Its owner's name, such as `root`.
    pub user: String,
    /// Its permission bits, such as `0o644`.
    pub mode: u32,
}

impl Owner {
    /// The account an installed pack, and a program it names, must belong to.
    pub const ROOT: &'static str = "root";

    /// A file of root's, with these permission bits.
    pub fn root(mode: u32) -> Owner {
        Owner {
            user: Owner::ROOT.to_string(),
            mode,
        }
    }

    /// Whether it's root's.
    pub fn is_root(&self) -> bool {
        self.user == Owner::ROOT
    }

    /// Whether its group or anyone else may write it.
    pub fn writable_by_others(&self) -> bool {
        self.mode & 0o022 != 0
    }
}

/// Who owns a file, and who may write it: the filesystem's own on a board; in tests, a table, as
/// a test's files are the tester's.
pub trait Owners: Send + Sync {
    /// The owner and mode of an absolute path (as on the board), its links followed.
    fn of(&self, host: &Host, path: &str) -> io::Result<Owner>;
}

/// The computer's network addresses: the system's on a board, a list in tests.
pub trait Addresses: Send + Sync {
    /// Every interface's addresses but loopback's, IPv4 first, IPv6 link-local ones left out, at
    /// most [`MAX_ADDRESSES`].
    fn read(&self) -> io::Result<Vec<String>>;
}

/// Where the agent's log lines go: its standard error (the journal) on a board, a list in tests.
pub type LogSink = Arc<dyn Fn(&str) + Send + Sync>;

/// The monotonic clock, in nanoseconds since boot.
pub type Clock = Arc<dyn Fn() -> u64 + Send + Sync>;

pub struct Host {
    root: PathBuf,
    owners: Box<dyn Owners>,
    addresses: Box<dyn Addresses>,
    clock: Clock,
    log: LogSink,
}

impl Host {
    pub fn new(
        root: PathBuf,
        owners: Box<dyn Owners>,
        addresses: Box<dyn Addresses>,
        clock: Clock,
        log: LogSink,
    ) -> Host {
        Host {
            root,
            owners,
            addresses,
            clock,
            log,
        }
    }

    /// The computer's files from `root`, its owners, addresses and clock its own, its log the
    /// agent's standard error.
    pub fn system(root: PathBuf) -> Host {
        Host::new(
            root,
            Box::new(SystemOwners),
            Box::new(SystemAddresses),
            Arc::new(monotonic_nanos),
            Arc::new(|line: &str| log::info!("{line}")),
        )
    }

    /// The root the board's files are read from.
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// A path as on the board (`/etc/os-release`), under the root.
    pub fn path(&self, absolute: &str) -> PathBuf {
        self.root.join(absolute.trim_start_matches('/'))
    }

    /// A file's text, or none when it doesn't exist; at most [`MAX_FILE`] bytes of it.
    pub fn read(&self, absolute: &str) -> io::Result<Option<String>> {
        match fs::File::open(self.path(absolute)) {
            Ok(file) => {
                let mut bytes = Vec::new();
                file.take(MAX_FILE as u64).read_to_end(&mut bytes)?;
                Ok(Some(String::from_utf8_lossy(&bytes).into_owned()))
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// A file's first line, trimmed, or none when it doesn't exist.
    pub fn line(&self, absolute: &str) -> io::Result<Option<String>> {
        Ok(self
            .read(absolute)?
            .map(|text| text.lines().next().unwrap_or("").trim().to_string()))
    }

    /// The names in a folder, sorted; none when it isn't a folder.
    pub fn list(&self, absolute: &str) -> io::Result<Vec<String>> {
        let folder = self.path(absolute);
        if !folder.is_dir() {
            return Ok(Vec::new());
        }
        let mut names = Vec::new();
        for entry in fs::read_dir(folder)? {
            names.push(entry?.file_name().to_string_lossy().into_owned());
        }
        names.sort_by(|a, b| crate::protocol::pack_hash::java_order(a, b));
        Ok(names)
    }

    /// Whether a path exists, its links followed.
    pub fn exists(&self, absolute: &str) -> bool {
        self.path(absolute).exists()
    }

    /// Whether a path is a folder, its links followed.
    pub fn is_folder(&self, absolute: &str) -> bool {
        self.path(absolute).is_dir()
    }

    /// A path's real one, as on the board: its links followed. An error when it isn't there, or
    /// its links lead outside the root.
    pub fn real_path(&self, absolute: &str) -> io::Result<String> {
        let real = fs::canonicalize(self.path(absolute))?;
        let top = fs::canonicalize(&self.root)?;
        let Ok(inside) = real.strip_prefix(&top) else {
            return Err(io::Error::other(format!(
                "{absolute} leads outside {}",
                self.root.display()
            )));
        };
        Ok(format!("/{}", inside.to_string_lossy()))
    }

    /// Who owns a path, and who may write it, its links followed.
    pub fn owner(&self, absolute: &str) -> io::Result<Owner> {
        self.owners.of(self, absolute)
    }

    /// The computer's network addresses, as they are now.
    pub fn addresses(&self) -> io::Result<Vec<String>> {
        self.addresses.read()
    }

    /// The monotonic clock (CLOCK_MONOTONIC), nanoseconds since boot.
    pub fn nanos(&self) -> u64 {
        (self.clock)()
    }

    /// Writes a line to the agent's log, its control characters escaped and cut at
    /// [`super::log::MAX_LINE`].
    pub fn log(&self, message: &str) {
        (self.log)(&escaped(message));
    }
}

/// CLOCK_MONOTONIC, in nanoseconds: what values and events are timed by.
pub fn monotonic_nanos() -> u64 {
    match nix::time::clock_gettime(nix::time::ClockId::CLOCK_MONOTONIC) {
        Ok(now) => now.tv_sec() as u64 * 1_000_000_000 + now.tv_nsec() as u64,
        Err(_) => 0,
    }
}

/// The filesystem's own owners: the user who owns a file, by name, and its permission bits.
pub struct SystemOwners;

impl Owners for SystemOwners {
    fn of(&self, host: &Host, path: &str) -> io::Result<Owner> {
        let metadata = fs::metadata(host.path(path))?;
        let uid = metadata.uid();
        let user = match nix::unistd::User::from_uid(nix::unistd::Uid::from_raw(uid)) {
            Ok(Some(user)) => user.name,
            _ => uid.to_string(),
        };
        Ok(Owner {
            user,
            mode: metadata.permissions().mode() & 0o777,
        })
    }
}

/// The system's addresses, interface by interface in the kernel's order: IPv4 first, then IPv6,
/// without loopback's or IPv6 link-local ones, and without a scope.
pub struct SystemAddresses;

impl Addresses for SystemAddresses {
    fn read(&self) -> io::Result<Vec<String>> {
        let mut found: Vec<(u32, bool, String)> = Vec::new();
        for each in nix::ifaddrs::getifaddrs().map_err(io::Error::from)? {
            if each
                .flags
                .contains(nix::net::if_::InterfaceFlags::IFF_LOOPBACK)
            {
                continue;
            }
            let Some(address) = each.address else {
                continue;
            };
            let index = nix::net::if_::if_nametoindex(each.interface_name.as_str()).unwrap_or(0);
            if let Some(v4) = address.as_sockaddr_in() {
                let ip = v4.ip();
                if !ip.is_loopback() {
                    found.push((index, true, ip.to_string()));
                }
            } else if let Some(v6) = address.as_sockaddr_in6() {
                let ip = v6.ip();
                let link_local = (ip.segments()[0] & 0xffc0) == 0xfe80;
                if !ip.is_loopback() && !link_local {
                    found.push((index, false, ip.to_string()));
                }
            }
        }
        // Stable: within an interface, the kernel's order.
        found.sort_by_key(|(index, _, _)| *index);
        let mut all: Vec<String> = found
            .iter()
            .filter(|(_, v4, _)| *v4)
            .map(|(_, _, ip)| ip.clone())
            .collect();
        all.extend(
            found
                .iter()
                .filter(|(_, v4, _)| !*v4)
                .map(|(_, _, ip)| ip.clone()),
        );
        all.truncate(MAX_ADDRESSES);
        Ok(all)
    }
}

/// A fixed list of addresses: a test's board.
pub struct FixedAddresses(pub Vec<String>);

impl Addresses for FixedAddresses {
    fn read(&self) -> io::Result<Vec<String>> {
        Ok(self.0.clone())
    }
}

/// Every file root's, written by root alone unless it may be run: a test's board, whose files are
/// the tester's but count as an installer leaves them.
pub struct RootOwners;

impl Owners for RootOwners {
    fn of(&self, host: &Host, path: &str) -> io::Result<Owner> {
        let metadata = fs::metadata(host.path(path))?;
        let executable = metadata.permissions().mode() & 0o111 != 0;
        Ok(Owner::root(if executable { 0o755 } else { 0o644 }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// The board as the agent finds it on a real filesystem: its files' owners, and the rest.
    #[test]
    fn a_files_owner_and_mode_are_the_filesystems_own() {
        let dir = tempfile::tempdir().expect("a folder");
        let log = Arc::new(Mutex::new(Vec::<String>::new()));
        let lines = log.clone();
        let host = Host::new(
            dir.path().to_path_buf(),
            Box::new(SystemOwners),
            Box::new(SystemAddresses),
            Arc::new(monotonic_nanos),
            Arc::new(move |line: &str| lines.lock().expect("the log").push(line.to_string())),
        );
        let file = dir.path().join("etc/frc-spotter/packs/vision/pack.yaml");
        fs::create_dir_all(file.parent().expect("its folder")).expect("its folders");
        fs::write(&file, "pack: vision\n").expect("the file");
        fs::set_permissions(&file, fs::Permissions::from_mode(0o664)).expect("its mode");
        let owner = host
            .owner("/etc/frc-spotter/packs/vision/pack.yaml")
            .expect("its owner");
        let uid = fs::metadata(&file).expect("its metadata").uid();
        let user = nix::unistd::User::from_uid(nix::unistd::Uid::from_raw(uid))
            .ok()
            .flatten()
            .map_or(uid.to_string(), |user| user.name);
        assert_eq!(owner.user, user);
        assert_eq!(owner.is_root(), user == "root");
        assert_eq!(owner.mode, 0o664);
        assert!(owner.writable_by_others());
        assert_eq!(
            host.list("/etc/frc-spotter/packs").expect("the folder"),
            ["vision"]
        );
        assert!(host.list("/nothing").expect("nothing").is_empty());
        assert!(host.is_folder("/etc/frc-spotter/packs/vision"));
        assert_eq!(
            host.line("/etc/frc-spotter/packs/vision/pack.yaml")
                .expect("the line"),
            Some("pack: vision".to_string())
        );
        assert_eq!(host.read("/nothing").expect("nothing"), None);
        assert!(host.nanos() > 0);
        host.log("logged");
        assert_eq!(*log.lock().expect("the log"), ["logged"]);
    }

    #[test]
    fn its_addresses_leave_out_loopback_and_link_local() {
        let addresses = SystemAddresses.read().expect("the addresses");
        assert!(addresses.len() <= MAX_ADDRESSES);
        for address in addresses {
            assert!(
                !address.starts_with("127.")
                    && address != "::1"
                    && !address.starts_with("fe80:")
                    && !address.contains('%'),
                "{address}"
            );
        }
    }
}
