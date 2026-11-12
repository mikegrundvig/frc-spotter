//! The agent's protocol, version 2, on hyper: every answer a spotter.proto message, in protobuf, or
//! in its JSON form when the request's `Accept` asks for `application/json`; every response with
//! `Spotter-Protocol: 2.0`. Reading is open to anyone on the robot's network; a write is taken only
//! from the robot controller's address, refused, every one, while the board's `agent.json` is there
//! but can't be read, and must be signed when the board lists trusted keys.
//!
//! Each connection is served on its own, so a client that's slow to send (or never finishes)
//! holds up nobody else; a request must arrive within the protocol's time for one, and
//! [`MAX_CONNECTIONS`] are open at most. It answers only requests addressed to it, so a web page
//! elsewhere can't reach it through a name that resolves to it.

pub mod answer;
pub mod describer;
pub mod events;
pub mod pacing;
mod routes;
pub mod signatures;
pub mod stream;
mod writes;

use std::collections::HashMap;
use std::convert::Infallible;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use hyper::body::Incoming;
use hyper::server::conn::http1;
use hyper::service::service_fn;
use hyper::{Request, Response};
use hyper_util::rt::{TokioIo, TokioTimer};
use tokio::net::TcpListener;
use tokio::sync::{Notify, Semaphore};

use crate::agent::Agent;
use crate::board::log::RateLimited;
use crate::protocol;
use answer::{Body, Refused};

/// How many connections are open at once: one more waits to be taken.
pub const MAX_CONNECTIONS: usize = 32;

/// How many requests one address may have under way at once: one more is refused.
const PER_ADDRESS: usize = 8;

/// How long between log lines about refused writes, and about failures, in microseconds.
const REFUSAL_LOG_MICROS: u64 = 10_000_000;

/// How long a stream's write may stall, its reader not reading, before the stream is closed.
pub const STALL: Duration = Duration::from_secs(10);

/// The server's own state: who has what under way, and the open streams.
pub struct Server {
    agent: Arc<Agent>,
    under_way: Mutex<HashMap<IpAddr, usize>>,
    streams: Mutex<Vec<Arc<stream::Open>>>,
    pushes: Semaphore,
    refusals: RateLimited,
    failures: RateLimited,
    stall: Duration,
    /// How often a stream sends every value again: [`stream::COMPLETE_NANOS`] on a board.
    complete: u64,
}

impl Server {
    /// A server for the agent: `stall` is how long a stream's write may stall, `complete` how often
    /// a stream sends every value again ([`STALL`] and [`stream::COMPLETE_NANOS`] on a board).
    pub fn new(agent: Arc<Agent>, stall: Duration, complete: Duration) -> Arc<Server> {
        Arc::new(Server {
            agent,
            under_way: Mutex::new(HashMap::new()),
            streams: Mutex::new(Vec::new()),
            pushes: Semaphore::new(1),
            refusals: RateLimited::new(REFUSAL_LOG_MICROS),
            failures: RateLimited::new(REFUSAL_LOG_MICROS),
            stall,
            complete: complete.as_nanos() as u64,
        })
    }

    /// Logs a refused write, at most a line each 10 s.
    fn refused(&self, message: &str) {
        if let Some(line) = self.refusals.line(message, self.agent.nanos() / 1000) {
            self.agent.log(&line);
        }
    }

    /// How many streams are open: for tests.
    pub fn streams(&self) -> usize {
        self.streams.lock().expect("the streams' lock").len()
    }

    /// Serves connections on a listener until the task is dropped.
    pub async fn serve(self: Arc<Self>, listener: TcpListener) {
        let room = Arc::new(Semaphore::new(MAX_CONNECTIONS));
        loop {
            let Ok(permit) = room.clone().acquire_owned().await else {
                return;
            };
            let (socket, from) = match listener.accept().await {
                Ok(accepted) => accepted,
                Err(e) => {
                    // Out of files, say: wait a moment rather than spin.
                    self.agent
                        .log(&format!("Couldn't accept a connection: {e}"));
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            };
            let server = self.clone();
            tokio::spawn(async move {
                server.connection(socket, from).await;
                drop(permit);
            });
        }
    }

    /// One connection, its requests one after another, until it closes, or a stream on it is
    /// closed (stalled, or replaced), which drops it at once.
    async fn connection(self: Arc<Self>, socket: tokio::net::TcpStream, from: SocketAddr) {
        let kill = Arc::new(Notify::new());
        let server = self.clone();
        let killer = kill.clone();
        let from = canonical(from.ip());
        let service = service_fn(move |request: Request<Incoming>| {
            let server = server.clone();
            let kill = killer.clone();
            async move { Ok::<_, Infallible>(server.handle(from, kill, request).await) }
        });
        let connection = http1::Builder::new()
            .timer(TokioTimer::new())
            .header_read_timeout(Duration::from_secs(protocol::REQUEST_SECONDS))
            .serve_connection(TokioIo::new(socket), service);
        tokio::select! {
            _ = connection => {}
            _ = kill.notified() => {}
        }
    }

    async fn handle(
        self: Arc<Self>,
        from: IpAddr,
        kill: Arc<Notify>,
        request: Request<Incoming>,
    ) -> Response<Body> {
        let json = answer::wants_json(header(&request, "accept"));
        let method = request.method().to_string();
        let path = request.uri().path().to_string();
        let answered = async {
            if !self.addressed_here(header(&request, "host")) {
                return Err(Refused::new(
                    421,
                    "this agent answers requests addressed to it only",
                ));
            }
            let Some(under_way) = UnderWay::enter(&self, from) else {
                return Err(Refused::new(
                    503,
                    format!("{from} has {PER_ADDRESS} requests under way, as many as one may"),
                ));
            };
            routes::route(&self, request, from, json, kill, under_way).await
        };
        match answered.await {
            Ok(response) => response,
            Err(refused) if refused.internal => {
                // The detail (which may name files) goes to the journal, at most a line each 10 s;
                // the answer says only that.
                let detail = format!("Answering {method} {path} failed: {}", refused.reason);
                if let Some(line) = self.failures.line(&detail, self.agent.nanos() / 1000) {
                    self.agent.log(&line);
                }
                answer::refusal(
                    &Refused::new(500, "the agent couldn't answer; its journal says why"),
                    json,
                )
            }
            Err(refused) => answer::refusal(&refused, json),
        }
    }

    /// Whether the request was addressed to this computer: by an address on the robot's network
    /// (10/8), a link-local (169.254/16) or loopback (127/8) address, `localhost`, or its own name.
    /// A request with no Host is an old client's, and allowed.
    fn addressed_here(&self, header: Option<&str>) -> bool {
        let Some(header) = header else {
            return true;
        };
        let host = header.trim().to_lowercase();
        if host.starts_with('[') {
            return host.starts_with("[::1]");
        }
        let host = host.split(':').next().unwrap_or("");
        own_address(host)
            || host == "localhost"
            || self.agent.names().iter().any(|name| name == host)
    }
}

/// The robot network (10/8), link-local (169.254/16), and loopback (127/8) addresses, written out
/// as four numbers of up to three digits.
fn own_address(host: &str) -> bool {
    let parts: Vec<&str> = host.split('.').collect();
    let number =
        |part: &&str| (1..=3).contains(&part.len()) && part.bytes().all(|b| b.is_ascii_digit());
    parts.len() == 4
        && parts.iter().all(number)
        && (parts[0] == "10" || parts[0] == "127" || (parts[0] == "169" && parts[1] == "254"))
}

/// An address as a request's is compared: an IPv4 one mapped into IPv6 as the IPv4 it is.
fn canonical(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => v6.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(ip),
        v4 => v4,
    }
}

fn header<'a>(request: &'a Request<Incoming>, name: &str) -> Option<&'a str> {
    request
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
}

/// A request from an address, under way until it's dropped.
pub struct UnderWay {
    server: Arc<Server>,
    from: IpAddr,
}

impl UnderWay {
    /// None when the address has as many under way as may.
    fn enter(server: &Arc<Server>, from: IpAddr) -> Option<UnderWay> {
        let mut under_way = server.under_way.lock().expect("the requests' lock");
        let now = under_way.entry(from).or_insert(0);
        if *now >= PER_ADDRESS {
            return None;
        }
        *now += 1;
        Some(UnderWay {
            server: server.clone(),
            from,
        })
    }
}

impl Drop for UnderWay {
    fn drop(&mut self) {
        let mut under_way = self.server.under_way.lock().expect("the requests' lock");
        if let Some(now) = under_way.get_mut(&self.from) {
            *now -= 1;
            if *now == 0 {
                under_way.remove(&self.from);
            }
        }
    }
}

/// Binds the agent's port: `--port` 0 takes one the system picks.
pub async fn bind(address: SocketAddr) -> io::Result<TcpListener> {
    let socket = match address {
        SocketAddr::V4(_) => tokio::net::TcpSocket::new_v4()?,
        SocketAddr::V6(_) => tokio::net::TcpSocket::new_v6()?,
    };
    // A restart takes its port again at once, whatever connections the last one left closing.
    socket.set_reuseaddr(true)?;
    socket.bind(address)?;
    socket.listen(MAX_CONNECTIONS as u32)
}

#[cfg(test)]
mod tests;
