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
use frc_spotter::proto::{Event, Problem};
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
        if !extra.iter().any(|arg| arg.starts_with("--controller=")) {
            command.arg(format!("--controller={CONTROLLER}"));
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

    /// Opens a stream from an address: its answer's head, and its events as they come.
    pub async fn stream(&self, from: IpAddr, query: &str) -> Stream {
        let response = self
            .send(from, "GET", &format!("/v2/stream{query}"), &[], b"")
            .await;
        let status = response.status().as_u16();
        let headers = response.headers().clone();
        Stream {
            status,
            headers,
            body: response.into_body(),
            buffer: BytesMut::new(),
        }
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
    pub fn challenge(&self) -> String {
        self.headers
            .get("spotter-challenge")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string()
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
