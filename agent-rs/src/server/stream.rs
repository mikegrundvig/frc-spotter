//! One manager's stream: on connect, the description, then every value, then the runs the agent
//! keeps; after that, as things happen. A value that changes is sent with whatever else changed
//! within 20 ms of it, by its index; every value again each [`COMPLETE_NANOS`], so a missed change
//! can't stick; a run's start, log lines and finish at once; the description again whenever its
//! revision changes; and a heartbeat whenever nothing else was sent for the interval the manager
//! asked for. Between them the stream sleeps until the next thing is due, or something happens.
//!
//! The robot controller's address may have two streams open (its manager's, and one it's
//! replacing); one more closes its oldest. Everyone else together may have two; one more is
//! refused. A stream whose reader stops reading is closed once a write to it has stalled.

use std::net::IpAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use bytes::Bytes;
use hyper::body::Incoming;
use hyper::{Request, Response};
use prost::Message;
use tokio::sync::{Notify, mpsc};

use super::answer::{self, Body, Refused};
use super::events::Inbox;
use super::pacing::Pacing;
use super::routes::query;
use super::{Server, UnderWay};
use crate::agent::Agent;
use crate::board::host::monotonic_nanos;
use crate::packs::duration;
use crate::proto::{Event, Heartbeat, RunState, Runs, Values, event, json};
use crate::protocol;

/// How often every value is sent again.
pub const COMPLETE_NANOS: u64 = 10_000_000_000;

/// The heartbeat interval unless asked, and the least and most it may be.
const HEARTBEAT: Duration = Duration::from_millis(250);
const MIN_HEARTBEAT: Duration = Duration::from_millis(50);
const MAX_HEARTBEAT: Duration = Duration::from_secs(5);

/// How many streams the robot controller's address may have open at once; one more closes its
/// oldest.
const CONTROLLER_STREAMS: usize = 2;

/// How many streams others may have open at once: one more is refused.
const OTHER_STREAMS: usize = 2;

/// The most an event's message may hold, in bytes: the protocol's most for an event, less room
/// for the event around it.
const MAX_HELD: usize = protocol::MAX_EVENT - 64;

/// An open stream: whose, and how to close it.
pub struct Open {
    controller: bool,
    closed: AtomicBool,
    /// Drops its connection at once.
    kill: Arc<Notify>,
}

impl Open {
    fn close(&self) {
        if !self.closed.swap(true, Ordering::SeqCst) {
            self.kill.notify_one();
        }
    }
}

/// The heartbeat interval a request asks for (`250ms`, `1s`), kept between the least and most;
/// none when it isn't a duration.
pub fn heartbeat(asked: &str) -> Option<Duration> {
    if asked.is_empty() {
        return Some(HEARTBEAT);
    }
    duration::parse(asked).map(|d| d.clamp(MIN_HEARTBEAT, MAX_HEARTBEAT))
}

/// Opens a stream, if one more may be, and answers with it.
pub fn open(
    server: &Arc<Server>,
    request: &Request<Incoming>,
    from: IpAddr,
    json: bool,
    kill: Arc<Notify>,
    under_way: UnderWay,
) -> Result<Response<Body>, Refused> {
    let query = query(request.uri().query())?;
    let asked = query.get("heartbeat").map(String::as_str).unwrap_or("");
    let heartbeat = heartbeat(asked).ok_or_else(|| {
        Refused::new(
            400,
            format!("heartbeat is a duration, such as 250ms, not {asked}"),
        )
    })?;
    let entry = admit(server, from, kill)?;
    let challenge = server.agent.signatures.open();
    let (sender, events) = mpsc::channel(1);
    let mut response = answer::response(200, Body::Stream(events));
    let headers = response.headers_mut();
    headers.insert(
        hyper::header::CONTENT_TYPE,
        hyper::header::HeaderValue::from_static(if json {
            protocol::JSON_LINES
        } else {
            protocol::PROTOBUF
        }),
    );
    if let Ok(value) = hyper::header::HeaderValue::from_str(&challenge) {
        headers.insert(protocol::CHALLENGE, value);
    }
    let server = server.clone();
    tokio::spawn(async move {
        let agent = server.agent.clone();
        let stream = Stream {
            agent: agent.clone(),
            sink: sender,
            json,
            stall: server.stall,
            inbox: Inbox::new(Pacing::new(
                heartbeat.as_nanos() as u64,
                COMPLETE_NANOS,
                monotonic_nanos(),
            )),
        };
        agent.events.subscribe(stream.inbox.clone());
        let ended = stream.serve().await;
        agent.events.unsubscribe(&stream.inbox);
        agent.signatures.close(&challenge);
        if ended == Ended::Stalled {
            // Its reader stopped reading: its connection is dropped, never answered to its end.
            entry.close();
        }
        server
            .streams
            .lock()
            .expect("the streams' lock")
            .retain(|each| !Arc::ptr_eq(each, &entry));
        drop(under_way);
    });
    Ok(response)
}

/// Opens a stream, if one more may be: the robot controller's address may have
/// [`CONTROLLER_STREAMS`], its oldest closed for one more; anyone else's together
/// [`OTHER_STREAMS`], one more refused.
fn admit(server: &Arc<Server>, from: IpAddr, kill: Arc<Notify>) -> Result<Arc<Open>, Refused> {
    let controller = from.to_string() == server.agent.controller().address;
    let mut streams = server.streams.lock().expect("the streams' lock");
    let theirs: Vec<&Arc<Open>> = streams
        .iter()
        .filter(|each| each.controller == controller && !each.closed.load(Ordering::SeqCst))
        .collect();
    if controller && theirs.len() >= CONTROLLER_STREAMS {
        theirs[0].close();
    } else if !controller && theirs.len() >= OTHER_STREAMS {
        return Err(Refused::new(
            503,
            format!(
                "{OTHER_STREAMS} streams are open to others than the robot controller, as many as may"
            ),
        ));
    }
    let entry = Arc::new(Open {
        controller,
        closed: AtomicBool::new(false),
        kill,
    });
    streams.push(entry.clone());
    Ok(entry)
}

/// How a stream ended.
#[derive(Debug, PartialEq)]
enum Ended {
    /// Its reader went away, or its connection was closed.
    Gone,
    /// A write to it stalled past its bound: its reader stopped reading.
    Stalled,
    /// It fell too far behind the runs' events.
    Behind,
}

struct Stream {
    agent: Arc<Agent>,
    sink: mpsc::Sender<Bytes>,
    json: bool,
    stall: Duration,
    inbox: Arc<Inbox>,
}

impl Stream {
    /// Sends events until the manager goes away, or a write stalls.
    async fn serve(&self) -> Ended {
        match self.events().await {
            Ok(()) => Ended::Gone,
            Err(ended) => ended,
        }
    }

    async fn events(&self) -> Result<(), Ended> {
        let connected = monotonic_nanos();
        let agent = &self.agent;
        let mut description = agent.description();
        let mut revision = description.revision;
        self.send(event::Event::Described(description)).await?;
        let delta = agent.store.delta(None, revision, agent.nanos());
        let mut seen = delta.changes;
        self.values(delta.values).await?;
        self.send(event::Event::Runs(runs(&agent.runs.states())))
            .await?;
        self.inbox
            .state
            .lock()
            .expect("the inbox's lock")
            .pacing
            .complete_sent(connected);
        loop {
            // Each wake decides on one reading of the clock, and each send is timed as it starts:
            // so what's due follows from the time alone, however long a send or a wake takes.
            let (events, changed, complete, now) = loop {
                let wait = {
                    let mut state = self.inbox.state.lock().expect("the inbox's lock");
                    if state.behind {
                        return Err(Ended::Behind);
                    }
                    let now = monotonic_nanos();
                    let wake = state.pacing.wake();
                    if !state.queued.is_empty() || state.pacing.changes_due(now) || wake <= now {
                        let events: Vec<_> = state.queued.drain(..).collect();
                        let changed = state.pacing.changes_due(now);
                        if changed {
                            state.pacing.changes_taken();
                        }
                        break (events, changed, state.pacing.complete_due(now), now);
                    }
                    Duration::from_nanos(wake - now)
                };
                tokio::select! {
                    _ = self.inbox.wake.notified() => {}
                    _ = tokio::time::sleep(wait) => {}
                    _ = self.sink.closed() => return Ok(()),
                }
            };
            let mut everything = false;
            if agent.revision() != revision {
                description = agent.description();
                revision = description.revision;
                self.send(event::Event::Described(description.clone()))
                    .await?;
                everything = true;
            }
            for run in events {
                self.send(event::Event::Run(run)).await?;
            }
            if everything || complete {
                let delta = agent.store.delta(None, revision, agent.nanos());
                seen = delta.changes;
                let sending = monotonic_nanos();
                self.values(delta.values).await?;
                self.inbox
                    .state
                    .lock()
                    .expect("the inbox's lock")
                    .pacing
                    .complete_sent(sending);
            } else if changed {
                let delta = agent.store.delta(Some(seen), revision, agent.nanos());
                seen = delta.changes;
                if !delta.values.values.is_empty() {
                    self.values(delta.values).await?;
                }
            }
            let heartbeat = self
                .inbox
                .state
                .lock()
                .expect("the inbox's lock")
                .pacing
                .heartbeat_due(now);
            if heartbeat {
                self.send(event::Event::Heartbeat(Heartbeat {
                    time_nanos: agent.nanos(),
                }))
                .await?;
            }
        }
    }

    /// Sends an event, timed as it starts: protobuf's delimited form, or a JSON line.
    async fn send(&self, sent: event::Event) -> Result<(), Ended> {
        let now = monotonic_nanos();
        let event = Event { event: Some(sent) };
        let bytes = if self.json {
            json::line(&event)
        } else {
            event.encode_length_delimited_to_vec()
        };
        match tokio::time::timeout(self.stall, self.sink.send(Bytes::from(bytes))).await {
            Err(_) => return Err(Ended::Stalled),
            Ok(Err(_)) => return Err(Ended::Gone),
            Ok(Ok(())) => {}
        }
        self.inbox
            .state
            .lock()
            .expect("the inbox's lock")
            .pacing
            .sent(now);
        Ok(())
    }

    /// Sends values: in one event, or, past [`MAX_HELD`], in as many as they take.
    async fn values(&self, values: Values) -> Result<(), Ended> {
        for part in parts(values) {
            self.send(event::Event::Values(part)).await?;
        }
        Ok(())
    }
}

/// Values as the events that carry them: one, or several each within [`MAX_HELD`], each with the
/// same revision, time and completeness.
pub fn parts(values: Values) -> Vec<Values> {
    if values.encoded_len() <= MAX_HELD {
        return vec![values];
    }
    let mut parts: Vec<Values> = Vec::new();
    let mut size = 0;
    let empty = Values {
        values: Vec::new(),
        ..values.clone()
    };
    for value in values.values {
        let its = value.encoded_len() + 8;
        if parts.is_empty() || size + its > MAX_HELD {
            size = empty.encoded_len();
            parts.push(empty.clone());
        }
        parts.last_mut().expect("a part").values.push(value);
        size += its;
    }
    parts
}

/// The runs listed on connect, in the order they started: every running one, then the newest
/// finished, as many as fit [`MAX_HELD`]. One left out is still kept, and fetched by its id.
pub fn runs(states: &[RunState]) -> Runs {
    let mut chosen = vec![false; states.len()];
    let mut size = 0;
    for running in [true, false] {
        for (at, state) in states.iter().enumerate().rev() {
            let its = state.encoded_len() + 8;
            if state.running == running && size + its <= MAX_HELD {
                chosen[at] = true;
                size += its;
            }
        }
    }
    Runs {
        runs: states
            .iter()
            .zip(chosen)
            .filter(|(_, chosen)| *chosen)
            .map(|(state, _)| state.clone())
            .collect(),
    }
}
