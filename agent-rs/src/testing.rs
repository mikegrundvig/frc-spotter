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

impl Default for Fixture {
    fn default() -> Self {
        Self::new()
    }
}

/// A signing key for a test, the same for every run so a failure repeats: its seed a byte.
pub fn signing_key(seed: u8) -> ed25519_dalek::SigningKey {
    ed25519_dalek::SigningKey::from_bytes(&[seed; 32])
}

/// A key's public half as agent.json lists it: its X.509 encoding, in base64.
pub fn trusted(key: &ed25519_dalek::SigningKey) -> String {
    use base64::Engine;
    use ed25519_dalek::pkcs8::EncodePublicKey;
    let der = key
        .verifying_key()
        .to_public_key_der()
        .expect("its X.509 encoding");
    base64::engine::general_purpose::STANDARD.encode(der.as_bytes())
}

/// One of the spec's example packs, from the tests' pack folders: its pack.yaml.
pub fn example(name: &str) -> String {
    let file = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/resources/packs")
        .join(name)
        .join(FILE);
    fs::read_to_string(&file).unwrap_or_else(|e| panic!("{}: {e}", file.display()))
}

/// The repository's catalog of packs, beside the crate.
pub fn catalog() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../packs")
}

/// A folder's packs as a bundle, as the manager makes and sends it: every file by its path, with
/// `/`, executable as a push carries it.
pub fn bundle(folder: &Path) -> crate::proto::PackBundle {
    fn walk(folder: &Path, prefix: &str, files: &mut Vec<crate::proto::PackFile>) {
        let mut entries: Vec<_> = fs::read_dir(folder)
            .expect("the folder")
            .flatten()
            .collect();
        entries.sort_by_key(|entry| entry.file_name());
        for entry in entries {
            let name = entry.file_name().to_string_lossy().into_owned();
            let path = if prefix.is_empty() {
                name
            } else {
                format!("{prefix}/{name}")
            };
            if entry.path().is_dir() {
                walk(&entry.path(), &path, files);
            } else {
                files.push(crate::proto::PackFile {
                    path,
                    executable: crate::protocol::pack_hash::executable(&entry.path())
                        .expect("its mode"),
                    content: fs::read(entry.path()).expect("its bytes"),
                });
            }
        }
    }
    let mut files = Vec::new();
    walk(folder, "", &mut files);
    crate::proto::PackBundle { files }
}

/// A bundle made by hand: a path and its text each, all plain files.
pub fn files(paths_and_texts: &[(&str, &str)]) -> crate::proto::PackBundle {
    crate::proto::PackBundle {
        files: paths_and_texts
            .iter()
            .map(|(path, text)| crate::proto::PackFile {
                path: path.to_string(),
                executable: false,
                content: text.as_bytes().to_vec(),
            })
            .collect(),
    }
}

/// A request a test's web page was sent.
#[derive(Clone, Debug)]
pub struct Asked {
    pub method: String,
    pub path: String,
    pub content_type: String,
    pub body: Vec<u8>,
}

/// What a test's web page answers: its status and body, after a wait.
pub struct Page {
    pub status: u16,
    pub body: Vec<u8>,
    pub after: std::time::Duration,
}

impl Page {
    pub fn new(status: u16, body: &str) -> Page {
        Page {
            status,
            body: body.as_bytes().to_vec(),
            after: std::time::Duration::ZERO,
        }
    }
}

/// Serves the board's own web pages, on 127.0.0.1 at a port the system picks, until the test's
/// runtime ends: each request answered as the handler says. Its port.
pub async fn web(handler: impl Fn(Asked) -> Page + Send + Sync + 'static) -> u16 {
    use http_body_util::{BodyExt, Full};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("a port");
    let port = listener.local_addr().expect("its address").port();
    let handler = Arc::new(handler);
    tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            let handler = handler.clone();
            let service = hyper::service::service_fn(move |request: hyper::Request<_>| {
                let handler = handler.clone();
                async move {
                    let method = request.method().to_string();
                    let path = request.uri().path().to_string();
                    let content_type = request
                        .headers()
                        .get("content-type")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or("")
                        .to_string();
                    let body: hyper::body::Incoming = request.into_body();
                    let body = body.collect().await.map(|b| b.to_bytes().to_vec());
                    let page = handler(Asked {
                        method,
                        path,
                        content_type,
                        body: body.unwrap_or_default(),
                    });
                    tokio::time::sleep(page.after).await;
                    let mut response =
                        hyper::Response::new(Full::new(bytes::Bytes::from(page.body)));
                    *response.status_mut() =
                        hyper::StatusCode::from_u16(page.status).expect("a status");
                    Ok::<_, std::convert::Infallible>(response)
                }
            });
            tokio::spawn(
                hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), service),
            );
        }
    });
    port
}

/// A port on the board's loopback that nobody serves, kept so as long as what's returned is: bound
/// (on 127.0.0.1 and ::1), so no other test can take it, but never listening, so a connection to
/// it is refused.
pub fn unserved_port() -> (u16, Vec<tokio::net::TcpSocket>) {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
    loop {
        let v4 = tokio::net::TcpSocket::new_v4().expect("a socket");
        v4.bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0))
            .expect("a port");
        let port = v4.local_addr().expect("its address").port();
        let v6 = tokio::net::TcpSocket::new_v6().expect("a socket");
        // Taken on ::1 already: another.
        if v6
            .bind(SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), port))
            .is_ok()
        {
            return (port, vec![v4, v6]);
        }
    }
}
