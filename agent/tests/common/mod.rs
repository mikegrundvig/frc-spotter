//! The agent as its own program, for the black-box tests: a board's files in a folder, the program
//! started on it (built with its test feature, `--fixture`: its files count as root's and its
//! address as 127.0.0.1), and a minimal client of protocol 2 that plays the robot, or anyone else
//! on its network (127.0.0.2, say).
#![allow(dead_code)]

use std::fs;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::{Buf, Bytes, BytesMut};
use frc_spotter::proto::{Event, FieldValue, Problem, Values, event};
use http_body_util::{BodyExt, Full};
use hyper::body::Incoming;
use hyper_util::rt::TokioIo;
use prost::Message;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::Notify;

/// The agent's program, built with its test feature.
pub const PROGRAM: &str = env!("CARGO_BIN_EXE_frc-spotter");

/// The version a test's agent says it is.
pub const VERSION: &str = "0.4.0-test";

/// The robot controller, as the tests play it.
pub const CONTROLLER: IpAddr = IpAddr::V4(Ipv4Addr::LOCALHOST);

/// Someone else on the robot's network.
pub const SOMEONE: IpAddr = IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2));

/// The longest a test waits for the agent to do something before it fails.
pub const WAIT: Duration = Duration::from_secs(20);

/// A board in a folder: vision-front, Debian 13, nothing configured and no packs.
pub struct Board {
    pub dir: tempfile::TempDir,
    pub root: PathBuf,
}

impl Board {
    pub fn new() -> Board {
        let dir = tempfile::tempdir().expect("a folder for the board");
        let root = dir.path().join("root");
        let board = Board { dir, root };
        board.write("/proc/sys/kernel/hostname", "vision-front\n");
        board.write(
            "/proc/sys/kernel/random/boot_id",
            "3c1e6a2e-0000-4000-8000-000000000001\n",
        );
        board.write(
            "/etc/os-release",
            "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nID=debian\n",
        );
        board
    }

    /// A path as on the board, in the folder.
    pub fn path(&self, absolute: &str) -> PathBuf {
        self.root.join(absolute.trim_start_matches('/'))
    }

    pub fn write(&self, absolute: &str, text: &str) -> &Board {
        let file = self.path(absolute);
        fs::create_dir_all(file.parent().expect("a folder")).expect("its folders");
        fs::write(file, text).expect("the file");
        self
    }

    /// An installed pack, `/etc/frc-spotter/packs/<name>/pack.yaml`.
    pub fn pack(&self, name: &str, yaml: &str) -> &Board {
        self.write(&format!("/etc/frc-spotter/packs/{name}/pack.yaml"), yaml)
    }

    /// A shell script in an installed pack's folder, executable.
    pub fn script(&self, pack: &str, name: &str, body: &str) -> &Board {
        let absolute = format!("/etc/frc-spotter/packs/{pack}/{name}");
        self.write(&absolute, &format!("#!/bin/sh\n{body}\n"));
        fs::set_permissions(self.path(&absolute), fs::Permissions::from_mode(0o755))
            .expect("its mode");
        self
    }

    /// The board's `agent.json`.
    pub fn config(&self, json: &str) -> &Board {
        self.write("/etc/frc-spotter/agent.json", json)
    }

    /// Starts the agent on the board, taking writes from the controller: on a port the system
    /// picks, unless one is given. Returns once it serves.
    pub async fn start(&self, extra: &[&str]) -> Agent {
        self.start_on(0, extra).await
    }

    pub async fn start_on(&self, port: u16, extra: &[&str]) -> Agent {
        self.launch(port, Some(CONTROLLER), extra).await
    }

    /// Starts the agent naming no controller on its command line: its settings', or else the
    /// robot controller's on its own network, as its addresses (`--fixture-addresses=`) say.
    pub async fn start_unnamed(&self, extra: &[&str]) -> Agent {
        self.launch(0, None, extra).await
    }

    async fn launch(&self, port: u16, controller: Option<IpAddr>, extra: &[&str]) -> Agent {
        let mut command = Command::new(PROGRAM);
        command
            .arg("--fixture")
            .arg(format!("--fixture-version={VERSION}"))
            .arg(format!("--root={}", self.root.display()))
            .arg(format!("--port={port}"))
            .args(extra)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        if let Some(controller) = controller
            && !extra.iter().any(|arg| arg.starts_with("--controller="))
        {
            command.arg(format!("--controller={controller}"));
        }
        let mut child = command.spawn().expect("the agent starts");
        let log = Arc::new(Mutex::new(Vec::new()));
        let said = Arc::new(Notify::new());
        let stderr = child.stderr.take().expect("its log");
        let lines = log.clone();
        let told = said.clone();
        tokio::spawn(async move {
            let mut reader = BufReader::new(stderr).lines();
            while let Ok(Some(line)) = reader.next_line().await {
                lines.lock().expect("the log").push(line);
                told.notify_waiters();
            }
            told.notify_waiters();
        });
        let agent = Agent {
            child,
            log,
            said,
            port: 0,
        };
        let line = agent.said("serving protocol 2 on port").await;
        let port = line
            .rsplit(' ')
            .next()
            .and_then(|p| p.parse().ok())
            .expect("its port");
        Agent { port, ..agent }
    }
}

/// The agent running.
pub struct Agent {
    pub child: Child,
    pub log: Arc<Mutex<Vec<String>>>,
    said: Arc<Notify>,
    pub port: u16,
}

/// An answer: its status, headers, and body.
pub struct Answer {
    pub status: u16,
    pub headers: hyper::HeaderMap,
    pub body: Bytes,
}

impl Answer {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(name).and_then(|v| v.to_str().ok())
    }

    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    /// The body as a message.
    pub fn message<M: Message + Default>(&self) -> M {
        M::decode(self.body.clone()).expect("a message")
    }

    /// A refusal's reason.
    pub fn problem(&self) -> String {
        Problem::decode(self.body.clone())
            .expect("a Problem")
            .message
    }
}

impl Agent {
    pub fn address(&self) -> String {
        format!("127.0.0.1:{}", self.port)
    }

    /// The log so far, a line each.
    pub fn lines(&self) -> Vec<String> {
        self.log.lock().expect("the log").clone()
    }

    /// Waits for the agent to log a line holding this text: that line.
    pub async fn said(&self, text: &str) -> String {
        let until = tokio::time::Instant::now() + WAIT;
        loop {
            let waiting = self.said.notified();
            if let Some(line) = self.lines().into_iter().find(|line| line.contains(text)) {
                return line;
            }
            if tokio::time::timeout_at(until, waiting).await.is_err() {
                panic!(
                    "the agent never said \"{text}\"; it said:\n{}",
                    self.lines().join("\n")
                );
            }
        }
    }

    /// Stops it as systemd does (SIGTERM), and waits for it to end: its exit code.
    pub async fn stop(mut self) -> Option<i32> {
        if let Some(pid) = self.child.id() {
            let _ = nix::sys::signal::kill(
                nix::unistd::Pid::from_raw(pid as i32),
                nix::sys::signal::Signal::SIGTERM,
            );
        }
        let status = tokio::time::timeout(WAIT, self.child.wait())
            .await
            .expect("it stops")
            .ok()?;
        status.code()
    }

    /// Waits for it to end by itself (after a push): its exit code.
    pub async fn ended(mut self) -> Option<i32> {
        tokio::time::timeout(WAIT, self.child.wait())
            .await
            .expect("it ends")
            .ok()?
            .code()
    }

    /// A request from an address, with headers, and a body.
    pub async fn request(
        &self,
        from: IpAddr,
        method: &str,
        path: &str,
        headers: &[(&str, &str)],
        body: &[u8],
    ) -> Answer {
        let response = self.send(from, method, path, headers, body).await;
        let status = response.status().as_u16();
        let headers = response.headers().clone();
        let body = response
            .into_body()
            .collect()
            .await
            .map(|b| b.to_bytes())
            .unwrap_or_default();
        Answer {
            status,
            headers,
            body,
        }
    }

    /// A GET from the controller.
    pub async fn get(&self, path: &str) -> Answer {
        self.request(CONTROLLER, "GET", path, &[], b"").await
    }

    /// A POST from the controller.
    pub async fn post(&self, path: &str, body: &[u8]) -> Answer {
        self.request(CONTROLLER, "POST", path, &[], body).await
    }

    /// Sends a request, its answer's head back as it comes, its body to read.
    pub async fn send(
        &self,
        from: IpAddr,
        method: &str,
        path: &str,
        headers: &[(&str, &str)],
        body: &[u8],
    ) -> hyper::Response<Incoming> {
        let socket = tokio::net::TcpSocket::new_v4().expect("a socket");
        socket.bind(SocketAddr::new(from, 0)).expect("its address");
        let stream = socket
            .connect(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), self.port))
            .await
            .expect("the agent answers");
        let (mut sender, connection) =
            hyper::client::conn::http1::handshake::<_, Full<Bytes>>(TokioIo::new(stream))
                .await
                .expect("HTTP");
        tokio::spawn(connection);
        let mut request = hyper::Request::builder()
            .method(method)
            .uri(path)
            .header("host", self.address());
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let request = request
            .body(Full::new(Bytes::copy_from_slice(body)))
            .expect("a request");
        sender.send_request(request).await.expect("an answer")
    }

    /// A request written by hand, from the controller's address, as a client that sends what it
    /// likes would: the whole answer, as text, once the agent closes the connection.
    pub async fn raw(&self, request: &str) -> String {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let mut socket = tokio::net::TcpStream::connect(self.address())
            .await
            .expect("the agent answers");
        socket
            .write_all(request.as_bytes())
            .await
            .expect("the request");
        let mut answer = Vec::new();
        tokio::time::timeout(WAIT, socket.read_to_end(&mut answer))
            .await
            .expect("an answer in time")
            .expect("an answer");
        String::from_utf8_lossy(&answer).into_owned()
    }

    /// How many streams are open (`GET /fixture/streams`).
    pub async fn streams(&self) -> usize {
        self.get("/fixture/streams")
            .await
            .text()
            .parse()
            .expect("a count")
    }

    /// Waits for the agent to have this many streams open.
    pub async fn streams_open(&self, count: usize) {
        let until = tokio::time::Instant::now() + WAIT;
        loop {
            let open = self.streams().await;
            if open == count {
                return;
            }
            assert!(
                tokio::time::Instant::now() < until,
                "{open} streams open, never {count}"
            );
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }

    /// Sets values as their collectors would, all at once (`POST /fixture/values`): by id.
    pub async fn set(&self, values: &[(&str, FieldValue)]) {
        let values = Values {
            values: values
                .iter()
                .map(|(id, value)| FieldValue {
                    name: id.to_string(),
                    ..value.clone()
                })
                .collect(),
            ..Values::default()
        };
        let answer = self.post("/fixture/values", &values.encode_to_vec()).await;
        assert_eq!(answer.status, 204, "{}", answer.text());
    }

    /// Opens a stream from an address: its answer's head, and its events as they come.
    pub async fn stream(&self, from: IpAddr, query: &str) -> Stream {
        let response = self
            .send(from, "GET", &format!("/v2/stream{query}"), &[], b"")
            .await;
        Stream::of(response)
    }
}

/// A stream, read an event at a time.
pub struct Stream {
    pub status: u16,
    pub headers: hyper::HeaderMap,
    body: Incoming,
    buffer: BytesMut,
}

impl Stream {
    /// A stream's answer, its events to read as they come.
    pub fn of(response: hyper::Response<Incoming>) -> Stream {
        let status = response.status().as_u16();
        let headers = response.headers().clone();
        Stream {
            status,
            headers,
            body: response.into_body(),
            buffer: BytesMut::new(),
        }
    }

    pub fn challenge(&self) -> String {
        self.headers
            .get("spotter-challenge")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string()
    }

    /// The next line of a stream asked for as JSON lines, or none when it ends.
    pub async fn line(&mut self) -> Option<String> {
        let until = tokio::time::Instant::now() + WAIT;
        loop {
            if let Some(end) = self.buffer.iter().position(|b| *b == b'\n') {
                let line = self.buffer.split_to(end + 1);
                return Some(String::from_utf8_lossy(&line[..end]).into_owned());
            }
            match tokio::time::timeout_at(until, self.body.frame()).await {
                Err(_) | Ok(None) | Ok(Some(Err(_))) => return None,
                Ok(Some(Ok(frame))) => {
                    if let Ok(data) = frame.into_data() {
                        self.buffer.extend_from_slice(&data);
                    }
                }
            }
        }
    }

    /// The next event that isn't a heartbeat.
    pub async fn next_not_heartbeat(&mut self) -> Option<Event> {
        loop {
            let event = self.next().await?;
            if !matches!(event.event, Some(event::Event::Heartbeat(_))) {
                return Some(event);
            }
        }
    }

    /// The next event, or none when the stream ends (or nothing comes within the wait).
    pub async fn next(&mut self) -> Option<Event> {
        self.next_within(WAIT).await
    }

    pub async fn next_within(&mut self, wait: Duration) -> Option<Event> {
        let until = tokio::time::Instant::now() + wait;
        loop {
            let mut peek = &self.buffer[..];
            if let Ok(length) = prost::encoding::decode_varint(&mut peek) {
                let header = self.buffer.len() - peek.len();
                if peek.len() >= length as usize {
                    let event = Event::decode(&peek[..length as usize]).expect("an event");
                    self.buffer.advance(header + length as usize);
                    return Some(event);
                }
            }
            match tokio::time::timeout_at(until, self.body.frame()).await {
                Err(_) | Ok(None) | Ok(Some(Err(_))) => return None,
                Ok(Some(Ok(frame))) => {
                    if let Ok(data) = frame.into_data() {
                        self.buffer.extend_from_slice(&data);
                    }
                }
            }
        }
    }
}

/// Writes text to a file, making its folders.
pub fn write(file: &Path, text: &str) {
    fs::create_dir_all(file.parent().expect("a folder")).expect("its folders");
    fs::write(file, text).expect("the file");
}

/// A key pair, and what a robot's manager does with it to sign a write.
pub struct Signer {
    key: ed25519_dalek::SigningKey,
    /// Its public half, as agent.json lists it: its X.509 encoding, in base64.
    pub public: String,
    /// Its id, as a signature names it.
    pub id: String,
}

impl Signer {
    /// A key from a seed, the same for every run so a failure repeats.
    pub fn new(seed: u8) -> Signer {
        use base64::Engine;
        use ed25519_dalek::pkcs8::EncodePublicKey;
        let key = ed25519_dalek::SigningKey::from_bytes(&[seed; 32]);
        let der = key
            .verifying_key()
            .to_public_key_der()
            .expect("its X.509 encoding");
        let public = base64::engine::general_purpose::STANDARD.encode(der.as_bytes());
        let id = frc_spotter::protocol::signing::key_id(der.as_bytes());
        Signer { key, public, id }
    }

    /// A write's `Spotter-Signature`: over the challenge, its counter, method, path and body.
    pub fn sign(
        &self,
        challenge: &str,
        counter: i64,
        method: &str,
        path: &str,
        body: &[u8],
    ) -> String {
        use base64::Engine;
        use ed25519_dalek::Signer as _;
        use sha2::Digest;
        let hash = sha2::Sha256::digest(body);
        let message =
            frc_spotter::protocol::signing::message(challenge, counter, method, path, &hash);
        let signature = self.key.sign(&message).to_bytes();
        format!(
            "{} {counter} {}",
            self.id,
            base64::engine::general_purpose::STANDARD.encode(signature)
        )
    }
}

/// A number, as a value.
pub fn number(value: f64) -> FieldValue {
    FieldValue {
        value: Some(frc_spotter::proto::field_value::Value::Number(value)),
        ..FieldValue::default()
    }
}

/// Text, as a value.
pub fn text(value: &str) -> FieldValue {
    FieldValue {
        value: Some(frc_spotter::proto::field_value::Value::Text(
            value.to_string(),
        )),
        ..FieldValue::default()
    }
}

/// A port on this computer's loopback that nobody serves, kept so as long as what's returned is:
/// bound, so no other test can take it, but never listening, so a connection to it is refused.
pub fn unserved_port() -> (u16, tokio::net::TcpSocket) {
    let socket = tokio::net::TcpSocket::new_v4().expect("a socket");
    socket
        .bind(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0))
        .expect("a port");
    let port = socket.local_addr().expect("its address").port();
    (port, socket)
}
