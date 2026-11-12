//! The server's own bounds and choices, and a stream's description as the board changes: what
//! the black-box tests can't reach, as they need the board's clock and addresses moved.

use bytes::{Buf, BytesMut};
use http_body_util::{BodyExt, Empty};
use prost::Message;

use super::*;
use crate::agent::DESCRIPTION_NANOS;
use crate::proto::{Event, FieldValue, RunResult, RunState, Values, event, field_value};
use crate::testing::Fixture;

#[test]
fn an_address_has_at_most_its_requests_under_way() {
    let fixture = Fixture::new();
    let server = Server::new(fixture.agent(None), STALL, Duration::from_secs(10));
    let one: IpAddr = "10.12.34.99".parse().expect("an address");
    let other: IpAddr = "10.12.34.98".parse().expect("an address");
    let mut under_way: Vec<UnderWay> = (0..PER_ADDRESS)
        .map(|_| UnderWay::enter(&server, one).expect("room"))
        .collect();
    assert!(UnderWay::enter(&server, one).is_none());
    assert!(UnderWay::enter(&server, other).is_some());
    under_way.pop();
    assert!(UnderWay::enter(&server, one).is_some());
}

#[test]
fn json_is_asked_for_by_accept() {
    for (accept, json) in [
        (None, false),
        (Some("Application/JSON"), true),
        (Some("text/html,*/*;q=0.8"), false),
        (
            Some("application/x-protobuf, application/json;q=0.1"),
            false,
        ),
    ] {
        assert_eq!(answer::wants_json(accept), json, "{accept:?}");
    }
}

#[test]
fn the_heartbeat_asked_for_is_kept_within_its_bounds() {
    let ms = Duration::from_millis;
    for (asked, heartbeat) in [
        ("", Some(ms(250))),
        ("100ms", Some(ms(100))),
        ("10ms", Some(ms(50))),
        ("1m", Some(ms(5000))),
        ("often", None),
    ] {
        assert_eq!(stream::heartbeat(asked), heartbeat, "{asked}");
    }
}

fn text(index: u32, text: &str) -> FieldValue {
    FieldValue {
        index,
        name: String::new(),
        value: Some(field_value::Value::Text(text.into())),
    }
}

#[test]
fn values_past_what_an_event_holds_are_sent_as_several() {
    let small = Values {
        revision: 3,
        values: vec![FieldValue {
            value: Some(field_value::Value::Number(1.0)),
            ..text(0, "")
        }],
        ..Values::default()
    };
    assert_eq!(stream::parts(small.clone()), [small]);

    // Forty texts of 60 KiB, as forty collectors' whole outputs: 2.3 MiB in all.
    let long = "x".repeat(60 * 1024);
    let values = Values {
        revision: 3,
        time_nanos: 7,
        complete: true,
        values: (0..40).map(|i| text(i, &long)).collect(),
    };
    let parts = stream::parts(values);
    assert_eq!(parts.len(), 3);
    for part in &parts {
        assert!(part.encoded_len() <= protocol::MAX_EVENT - 64);
        assert!(part.revision == 3 && part.time_nanos == 7 && part.complete);
    }
    let indexes: Vec<u32> = parts
        .iter()
        .flat_map(|part| part.values.iter().map(|v| v.index))
        .collect();
    assert_eq!(indexes, (0..40).collect::<Vec<_>>());
}

#[test]
fn the_runs_listed_on_connect_fit_an_event_the_running_ones_first() {
    let mut states = vec![RunState {
        run: "00".into(),
        action: "p.wait".into(),
        running: true,
        result: None,
    }];
    let output = "y".repeat(250 * 1024);
    for i in 1..=10 {
        states.push(RunState {
            run: format!("{i:02}"),
            action: format!("p.a{i}"),
            running: false,
            result: Some(RunResult {
                response: vec![FieldValue {
                    name: "output".into(),
                    ..text(0, &output)
                }],
                ..RunResult::default()
            }),
        });
    }
    let runs = stream::runs(&states);
    assert!(runs.encoded_len() <= protocol::MAX_EVENT - 64);
    let listed: Vec<&str> = runs.runs.iter().map(|s| s.run.as_str()).collect();
    // The running one, then the newest four that fit, in the order they started.
    assert_eq!(listed, ["00", "07", "08", "09", "10"]);
}

/// A stream's events, read as they come.
struct Events {
    body: hyper::body::Incoming,
    buffer: BytesMut,
}

impl Events {
    async fn next(&mut self) -> Event {
        loop {
            let mut peek = &self.buffer[..];
            if let Ok(length) = prost::encoding::decode_varint(&mut peek) {
                let header = self.buffer.len() - peek.len();
                if peek.len() >= length as usize {
                    let event = Event::decode(&peek[..length as usize]).expect("an event");
                    self.buffer.advance(header + length as usize);
                    return event;
                }
            }
            let frame = tokio::time::timeout(Duration::from_secs(10), self.body.frame())
                .await
                .expect("an event in time")
                .expect("the stream goes on")
                .expect("a frame");
            if let Ok(data) = frame.into_data() {
                self.buffer.extend_from_slice(&data);
            }
        }
    }

    async fn next_not_heartbeat(&mut self) -> Event {
        loop {
            let event = self.next().await;
            if !matches!(event.event, Some(event::Event::Heartbeat(_))) {
                return event;
            }
        }
    }
}

/// Serves a fixture's agent on a port of the system's, and opens a stream to it.
async fn stream(fixture: &Fixture, query: &str) -> Events {
    let listener = bind("127.0.0.1:0".parse().expect("an address"))
        .await
        .expect("a port");
    let port = listener.local_addr().expect("its address").port();
    tokio::spawn(Server::new(fixture.agent(None), STALL, Duration::from_secs(10)).serve(listener));
    let socket = tokio::net::TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("it answers");
    let (mut sender, connection) =
        hyper::client::conn::http1::handshake::<_, Empty<bytes::Bytes>>(TokioIo::new(socket))
            .await
            .expect("HTTP");
    tokio::spawn(connection);
    let request = hyper::Request::get(format!("{}{query}", protocol::STREAM))
        .header("host", "127.0.0.1")
        .body(Empty::new())
        .expect("a request");
    let response = sender.send_request(request).await.expect("an answer");
    assert_eq!(response.status(), 200);
    Events {
        body: response.into_body(),
        buffer: BytesMut::new(),
    }
}

/// The board's clock moved on, its addresses changed: the next wake reads its description afresh.
#[tokio::test]
async fn a_new_description_is_sent_with_every_value_when_its_revision_changes() {
    let fixture = Fixture::new();
    let mut events = stream(&fixture, "?heartbeat=50ms").await;
    let Some(event::Event::Described(first)) = events.next().await.event else {
        panic!("the description first");
    };
    events.next().await;
    events.next().await;
    fixture.set_addresses(&[crate::testing::ADDRESS, "10.12.34.12"]);
    fixture
        .nanos
        .fetch_add(DESCRIPTION_NANOS, std::sync::atomic::Ordering::SeqCst);
    let Some(event::Event::Described(described)) = events.next_not_heartbeat().await.event else {
        panic!("a new description");
    };
    assert_ne!(described.revision, first.revision);
    let addresses = &described.identity.as_ref().expect("its identity").addresses;
    assert!(
        addresses.iter().any(|a| a == "10.12.34.12"),
        "{addresses:?}"
    );
    let Some(event::Event::Values(values)) = events.next_not_heartbeat().await.event else {
        panic!("every value");
    };
    assert!(values.complete);
    assert_eq!(values.revision, described.revision);
}
