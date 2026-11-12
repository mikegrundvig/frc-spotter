//! One manager's stream, on the wire: what it's sent on connect, then as things happen. Its clock
//! is the computer's own, so what's due when is checked by the times the agent stamps its events
//! with, never by how long a test waited (the pacing's own tests have the rules alone, exactly).
#![cfg(feature = "fixture")]

mod common;

use std::time::Duration;

use common::{Agent, Board, CONTROLLER, Stream, WAIT, number, text};
use frc_spotter::proto::{Description, Event, Heartbeat, Started, Values, event, field_value};

/// A board whose one collector, run once as the agent starts, fills neither of its values.
fn board() -> Board {
    let board = Board::new();
    board.pack(
        "vision",
        "pack: vision\ncollectors:\n  - id: health\n    run: [./health]\n    every: 1h\n    fields:\n      fps: {type: number}\n      mode: {type: text}\nactions:\n  - id: go\n    run: [./health]\n",
    );
    board.script("vision", "health", "true");
    board
}

/// Why the collector's one run filled neither value.
const UNFILLED: &str = "its output isn't a JSON object, which its 2 fields need: \"\"";

/// Waits for the collector's one run, so nothing but the test sets a value from then on.
async fn ran(agent: &Agent) {
    let until = tokio::time::Instant::now() + WAIT;
    loop {
        let values: Values = agent.get("/v2/values").await.message();
        if values.values[1].value == Some(field_value::Value::Unavailable(UNFILLED.into())) {
            return;
        }
        assert!(
            tokio::time::Instant::now() < until,
            "the collector never ran"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn described(event: Option<Event>) -> Description {
    match event.and_then(|e| e.event) {
        Some(event::Event::Described(description)) => description,
        other => panic!("a description, not {other:?}"),
    }
}

fn values(event: Option<Event>) -> Values {
    match event.and_then(|e| e.event) {
        Some(event::Event::Values(values)) => values,
        other => panic!("values, not {other:?}"),
    }
}

fn heartbeat(event: Option<Event>) -> Heartbeat {
    match event.and_then(|e| e.event) {
        Some(event::Event::Heartbeat(heartbeat)) => heartbeat,
        other => panic!("a heartbeat, not {other:?}"),
    }
}

/// The three events sent on connect: the description, every value, the runs.
async fn connected(stream: &mut Stream) -> (Description, Values) {
    let description = described(stream.next().await);
    let values = values(stream.next().await);
    let runs = stream.next().await.and_then(|e| e.event);
    assert!(matches!(runs, Some(event::Event::Runs(_))), "{runs:?}");
    (description, values)
}

#[tokio::test]
async fn on_connect_it_sends_the_description_every_value_and_the_runs() {
    let board = board();
    let agent = board.start(&[]).await;
    ran(&agent).await;
    agent.set(&[("vision.health.fps", number(30.0))]).await;
    let mut stream = agent.stream(CONTROLLER, "?heartbeat=5s").await;
    assert_eq!(stream.status, 200);
    assert_eq!(
        stream.headers.get("spotter-protocol").map(|v| v.as_bytes()),
        Some(&b"2.0"[..])
    );
    assert!(!stream.challenge().is_empty());
    let (description, values) = connected(&mut stream).await;
    assert_eq!(description.values.len(), 2);
    assert!(values.complete);
    assert_eq!(values.revision, description.revision);
    let held: Vec<_> = values.values.iter().map(|v| v.value.clone()).collect();
    assert_eq!(
        held,
        [
            Some(field_value::Value::Number(30.0)),
            Some(field_value::Value::Unavailable(UNFILLED.into())),
        ]
    );
}

#[tokio::test]
async fn when_nothing_happens_it_sends_a_heartbeat_each_interval_asked() {
    let board = board();
    let agent = board.start(&[]).await;
    ran(&agent).await;
    let mut stream = agent.stream(CONTROLLER, "?heartbeat=50ms").await;
    let (_, values) = connected(&mut stream).await;
    // Each at least the interval after what was sent before it, by the agent's own clock.
    let mut last = values.time_nanos;
    for _ in 0..3 {
        let beat = heartbeat(stream.next().await);
        assert!(
            beat.time_nanos >= last + 50_000_000,
            "{} after {last}",
            beat.time_nanos
        );
        last = beat.time_nanos;
    }
}

#[tokio::test]
async fn changes_together_are_sent_together_by_index_and_only_them() {
    let board = board();
    let agent = board.start(&[]).await;
    ran(&agent).await;
    let mut stream = agent.stream(CONTROLLER, "?heartbeat=1s").await;
    connected(&mut stream).await;
    agent
        .set(&[
            ("vision.health.fps", number(29.5)),
            ("vision.health.mode", text("tags")),
        ])
        .await;
    let delta = values(stream.next_not_heartbeat().await);
    assert!(!delta.complete);
    let indexes: Vec<u32> = delta.values.iter().map(|v| v.index).collect();
    assert_eq!(indexes, [0, 1]);
    // The same value again is no change.
    agent
        .set(&[
            ("vision.health.fps", number(29.5)),
            ("vision.health.fps", number(12.0)),
        ])
        .await;
    let next = values(stream.next_not_heartbeat().await);
    assert_eq!(next.values.len(), 1);
    assert_eq!(next.values[0].value, Some(field_value::Value::Number(12.0)));
}

#[tokio::test]
async fn every_value_is_sent_again_so_a_missed_change_cant_stick() {
    let board = board();
    let agent = board.start(&["--fixture-complete=200ms"]).await;
    ran(&agent).await;
    let mut stream = agent.stream(CONTROLLER, "?heartbeat=1s").await;
    let (_, first) = connected(&mut stream).await;
    let again = values(stream.next_not_heartbeat().await);
    assert!(again.complete);
    assert_eq!(again.values.len(), 2);
    assert!(
        again.time_nanos >= first.time_nanos + 200_000_000,
        "{} after {}",
        again.time_nanos,
        first.time_nanos
    );
}

#[tokio::test]
async fn run_events_are_sent_as_they_happen() {
    let board = board();
    let agent = board.start(&[]).await;
    ran(&agent).await;
    let mut stream = agent.stream(CONTROLLER, "?heartbeat=5s").await;
    connected(&mut stream).await;
    let started = agent.post("/v2/actions/vision.go", b"").await;
    assert_eq!(started.status, 202, "{}", started.text());
    let run = started.message::<Started>().run;
    let event = stream.next_not_heartbeat().await.and_then(|e| e.event);
    let Some(event::Event::Run(told)) = event else {
        panic!("a run's event, not {event:?}");
    };
    assert_eq!(told.run, run);
    assert_eq!(told.action, "vision.go");
    assert!(matches!(
        told.change,
        Some(frc_spotter::proto::run_event::Change::Started(true))
    ));
}

#[tokio::test]
async fn the_stream_on_the_wire_and_its_heartbeat_asked_for() {
    let board = board();
    let agent = board.start(&[]).await;
    let mut stream = agent.stream(CONTROLLER, "?heartbeat=50ms").await;
    assert_eq!(stream.status, 200);
    connected(&mut stream).await;
    // Values the collector's run changed may come first: then a heartbeat.
    let mut event = stream.next().await.and_then(|e| e.event);
    while matches!(event, Some(event::Event::Values(_))) {
        event = stream.next().await.and_then(|e| e.event);
    }
    assert!(
        matches!(event, Some(event::Event::Heartbeat(_))),
        "{event:?}"
    );
    let often = agent.get("/v2/stream?heartbeat=often").await;
    assert_eq!(often.status, 400);
}

#[tokio::test]
async fn the_stream_as_json_lines_for_people() {
    let board = board();
    let agent = board.start(&[]).await;
    let response = agent
        .send(
            CONTROLLER,
            "GET",
            "/v2/stream",
            &[("accept", "application/json")],
            b"",
        )
        .await;
    assert_eq!(
        response
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok()),
        Some("application/x-ndjson")
    );
    let mut stream = Stream::of(response);
    for start in ["{\"described\":", "{\"values\":", "{\"runs\":"] {
        let line = stream.line().await.expect("a line");
        assert!(line.starts_with(start), "{line}");
    }
}
