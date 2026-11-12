//! A board for the crate's own tests, in a folder: vision-front at 10.12.34.11 on team 1234's
//! network, Debian 13, its wired link up, nothing configured and no packs; its files root's and
//! written by root alone unless a test says otherwise; a clock the test moves, and the log the
//! agent writes. Its packs are folders the test writes, with scripts the agent really runs.

use std::collections::HashMap;
use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use crate::agent::Agent;
use crate::board::host::{Addresses, Host, Owner, Owners};
use crate::configuration::Configuration;
use crate::packs::load::{INSTALLED, PUSHED};
use crate::packs::pack::FILE;

/// Its address on team 1234's robot network.
pub const ADDRESS: &str = "10.12.34.11";

pub struct Fixture {
    /// Holds the folder until the test ends.
    pub dir: tempfile::TempDir,
    pub root: PathBuf,
    /// Who owns each file, and its mode, where it isn't root's alone (0644, or 0755 to run).
    pub owners: Arc<Mutex<HashMap<String, Owner>>>,
    /// The computer's addresses, as it has them now; an error when they can't be read.
    pub addresses: Arc<Mutex<Result<Vec<String>, String>>>,
    pub nanos: Arc<AtomicU64>,
    pub log: Arc<Mutex<Vec<String>>>,
    pub host: Arc<Host>,
    /// How often the agent ended, for systemd to start it again: after a push.
    pub exits: Arc<AtomicUsize>,
}

struct TableOwners(Arc<Mutex<HashMap<String, Owner>>>);

impl Owners for TableOwners {
    fn of(&self, host: &Host, path: &str) -> io::Result<Owner> {
        if let Some(owner) = self.0.lock().expect("the owners").get(path) {
            return Ok(owner.clone());
        }
        let executable =
            fs::metadata(host.path(path)).is_ok_and(|m| m.permissions().mode() & 0o111 != 0);
        Ok(Owner::root(if executable { 0o755 } else { 0o644 }))
    }
}

struct ListedAddresses(Arc<Mutex<Result<Vec<String>, String>>>);

impl Addresses for ListedAddresses {
    fn read(&self) -> io::Result<Vec<String>> {
        self.0
            .lock()
            .expect("the addresses")
            .clone()
            .map_err(io::Error::other)
    }
}

impl Fixture {
    pub fn new() -> Fixture {
        let dir = tempfile::tempdir().expect("a folder for the board");
        let root = dir.path().join("root");
        let owners = Arc::new(Mutex::new(HashMap::new()));
        let addresses = Arc::new(Mutex::new(Ok(vec![ADDRESS.to_string()])));
        let nanos = Arc::new(AtomicU64::new(1_234_560_000_000));
        let log = Arc::new(Mutex::new(Vec::new()));
        let clock = nanos.clone();
        let lines = log.clone();
        let host = Arc::new(Host::new(
            root.clone(),
            Box::new(TableOwners(owners.clone())),
            Box::new(ListedAddresses(addresses.clone())),
            Arc::new(move || clock.load(Ordering::SeqCst)),
            Arc::new(move |line: &str| lines.lock().expect("the log").push(line.to_string())),
        ));
        let fixture = Fixture {
            dir,
            root,
            owners,
            addresses,
            nanos,
            log,
            host,
            exits: Arc::default(),
        };
        fixture.write("/proc/sys/kernel/hostname", "vision-front\n");
        fixture.write(
            "/proc/sys/kernel/random/boot_id",
            "3c1e6a2e-0000-4000-8000-000000000001\n",
        );
        fixture.write(
            "/etc/os-release",
            "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\nVERSION_ID=\"13\"\n",
        );
        fixture.write("/sys/class/net/end0/address", "C0:74:2B:FE:12:34\n");
        fixture.write("/sys/class/net/end0/operstate", "up\n");
        fs::create_dir_all(fixture.path("/sys/class/net/end0/device")).expect("a device");
        fixture
    }

    /// A path as on the board, in the fixture.
    pub fn path(&self, absolute: &str) -> PathBuf {
        self.root.join(absolute.trim_start_matches('/'))
    }

    /// Writes a file, making its folders.
    pub fn write(&self, absolute: &str, text: &str) {
        let file = self.path(absolute);
        fs::create_dir_all(file.parent().expect("a folder")).expect("its folders");
        fs::write(file, text).expect("the file");
    }

    /// Writes a program the agent may run: a shell script, executable.
    pub fn script(&self, absolute: &str, body: &str) {
        self.write(absolute, &format!("#!/bin/sh\n{body}\n"));
        fs::set_permissions(self.path(absolute), fs::Permissions::from_mode(0o755))
            .expect("its mode");
    }

    /// An installed pack: its folder, with its pack.yaml.
    pub fn pack(&self, name: &str, yaml: &str) -> String {
        let folder = format!("{INSTALLED}/{name}");
        self.write(&format!("{folder}/{FILE}"), yaml);
        folder
    }

    /// A pushed pack: its folder, with its pack.yaml.
    pub fn pushed(&self, name: &str, yaml: &str) -> String {
        let folder = format!("{PUSHED}/{name}");
        self.write(&format!("{folder}/{FILE}"), yaml);
        folder
    }

    /// The board's settings.
    pub fn config(&self, json: &str) {
        self.write(crate::board::config::PATH, json);
    }

    /// The owner of a path as the board has it, in place of root's alone.
    pub fn own(&self, path: &str, owner: Owner) {
        self.owners
            .lock()
            .expect("the owners")
            .insert(path.to_string(), owner);
    }

    /// Sets the computer's addresses.
    pub fn set_addresses(&self, addresses: &[&str]) {
        *self.addresses.lock().expect("the addresses") =
            Ok(addresses.iter().map(|a| a.to_string()).collect());
    }

    /// The board's settings and packs, as the agent reads them as it starts.
    pub fn configuration(&self) -> Configuration {
        Configuration::read(&self.host, false)
    }

    /// The agent on this board, its packs read; its collectors not started. `controller` is the
    /// one address it takes writes from, as the command line names it.
    pub fn agent(&self, controller: Option<&str>) -> Arc<Agent> {
        let exits = self.exits.clone();
        Agent::new(
            self.host.clone(),
            Configuration::read(&self.host, controller.is_some()),
            "0.4.0-test",
            controller.map(str::to_string),
            Box::new(move || {
                exits.fetch_add(1, Ordering::SeqCst);
            }),
        )
    }

    /// What the agent logged, one line each.
    pub fn log(&self) -> String {
        self.log.lock().expect("the log").join("\n")
    }

    /// The folder the board is in.
    pub fn folder(&self) -> &Path {
        self.dir.path()
    }
}
