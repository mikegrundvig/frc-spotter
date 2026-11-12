//! Protocol 2 on the wire: protobuf unless JSON is asked for, its version on every answer, only
//! requests addressed to it answered, and its streams bounded.
#![cfg(feature = "fixture")]

mod common;

use std::time::Duration;

use common::{Agent, Board, CONTROLLER, SOMEONE, WAIT, text};
use frc_spotter::proto::{Description, Values, field_value};

/// A board with one collector, whose program prints 29.5.
fn board() -> Board {
    let board = Board::new();
    board.pack(
        "vision",
        "pack: vision\ncollectors:\n  - id: health\n    run: [./health]\n    every: 1h\n    fields:\n      fps: {type: number}\n      mode: {type: text}\n",
    );
    board.script(
        "vision",
        "health",
        "echo '{\"fps\": 29.5, \"mode\": \"tags\"}'",
    );
    board
}

/// Waits for the collector's first run to have filled its value.
async fn collected(agent: &Agent) -> Values {
    let until = tokio::time::Instant::now() + WAIT;
    loop {
        let values: Values = agent.get("/v2/values").await.message();
        if values.values[0].value == Some(field_value::Value::Number(29.5)) {
            return values;
        }
        assert!(tokio::time::Instant::now() < until, "never collected");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

#[tokio::test]
async fn it_describes_itself_in_protobuf_by_default() {
    let board = board();
    let agent = board.start(&[]).await;
    let answer = agent.get("/v2/describe").await;
    assert_eq!(answer.status, 200);
    assert_eq!(answer.header("spotter-protocol"), Some("2.0"));
    assert_eq!(
        answer.header("content-type"),
        Some("application/x-protobuf")
    );
    let description: Description = answer.message();
    assert_eq!(
        description
            .identity
            .as_ref()
            .expect("its identity")
            .hostname,
        "vision-front"
    );
    assert_eq!(description.values[0].id, "vision.health.fps");
    let asked = agent
        .request(
            CONTROLLER,
            "GET",
            "/v2/describe",
            &[("accept", "application/x-protobuf")],
            b"",
        )
        .await;
    assert_eq!(asked.message::<Description>(), description);
}

#[tokio::test]
async fn it_answers_in_quickbuffers_json_when_asked() {
    let board = board();
    let agent = board.start(&[]).await;
    let protobuf = collected(&agent).await;
    let answer = agent
        .request(
            CONTROLLER,
            "GET",
            "/v2/values",
            &[("accept", "application/json, */*")],
            b"",
        )
        .await;
    assert_eq!(answer.status, 200);
    assert_eq!(
        answer.header("content-type"),
        Some("application/json; charset=utf-8")
    );
    let json = answer.text();
    assert!(json.contains("\"complete\": true"), "{json}");
    assert!(json.contains("\"number\": 29.5"), "{json}");
    let parsed: serde_json::Value = serde_json::from_str(&json).expect("JSON");
    assert_eq!(parsed["revision"], protobuf.revision);
    assert_eq!(parsed["values"][0]["number"], 29.5);
    assert_eq!(parsed["values"][1]["text"], "tags");
}

#[tokio::test]
async fn refusals_are_problems_with_the_version_too() {
    let board = board();
    let agent = board.start(&[]).await;
    let missing = agent.get("/v1/health").await;
    assert_eq!(missing.status, 404);
    assert_eq!(missing.header("spotter-protocol"), Some("2.0"));
    assert_eq!(missing.problem(), "no such resource: /v1/health");
    let json = agent
        .request(
            CONTROLLER,
            "GET",
            "/v2/nothing",
            &[("accept", "application/json")],
            b"",
        )
        .await;
    assert!(
        json.text()
            .contains("\"message\": \"no such resource: /v2/nothing\""),
        "{}",
        json.text()
    );
    let posted = agent
        .request(
            CONTROLLER,
            "POST",
            "/v2/describe",
            &[("accept", "application/json")],
            b"",
        )
        .await;
    assert_eq!(posted.status, 405);
    assert_eq!(posted.header("allow"), Some("GET"));
    assert_eq!(posted.header("spotter-protocol"), Some("2.0"));
}

#[tokio::test]
async fn it_answers_only_requests_addressed_to_it() {
    let board = board();
    let agent = board.start(&[]).await;
    let rows = [
        ("10.12.34.11:5808", 200),
        ("localhost", 200),
        ("vision-front", 200),
        ("VISION-FRONT.local", 200),
        ("[::1]:5808", 200),
        ("169.254.3.4", 200),
        ("attacker.example.com", 421),
        ("[fe80::1]", 421),
    ];
    for (host, status) in rows {
        let answer = agent
            .raw(&format!(
                "GET /v2/describe HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n"
            ))
            .await;
        let line = answer.lines().next().unwrap_or("");
        assert_eq!(
            line.split(' ').nth(1),
            Some(status.to_string().as_str()),
            "{host}: {line}"
        );
    }
}

#[tokio::test]
async fn a_cursor_that_isnt_one_is_refused_and_forges_no_line_in_the_journal() {
    let board = board();
    let agent = board.start(&[]).await;
    // A NUL and a newline, URL-encoded: before, the JDK refused it as a process's environment,
    // the agent answered 500, and its journal took the decoded value, newline and all.
    let long = "x".repeat(1025);
    for cursor in ["%00x", "a%0A%3C3%3Eforged", "with%20space", long.as_str()] {
        let answer = agent
            .request(
                CONTROLLER,
                "GET",
                &format!("/v2/logs/vision.log?from=after&cursor={cursor}"),
                &[("accept", "application/json")],
                b"",
            )
            .await;
        assert_eq!(answer.status, 400, "{cursor}");
        assert!(
            answer.text().contains("a cursor is printable ASCII"),
            "{}",
            answer.text()
        );
    }
    let lines = agent.lines();
    assert!(
        !lines.iter().any(|line| line.contains("forged")),
        "{lines:?}"
    );
}

#[tokio::test]
async fn others_have_two_streams_at_most_and_the_controllers_are_reserved() {
    let board = board();
    let agent = board.start(&[]).await;
    // The controller is 127.0.0.1: these streams, from 127.0.0.2, are others'.
    let one = agent.stream(SOMEONE, "?heartbeat=50ms").await;
    assert_eq!(one.status, 200);
    let two = agent.stream(SOMEONE, "?heartbeat=50ms").await;
    assert_eq!(two.status, 200);
    let three = agent.stream(SOMEONE, "?heartbeat=50ms").await;
    assert_eq!(three.status, 503);
    // The controller's own are another two.
    let controllers = agent.stream(CONTROLLER, "?heartbeat=50ms").await;
    assert_eq!(controllers.status, 200);
}

#[tokio::test]
async fn the_controllers_new_stream_closes_its_oldest_past_two() {
    let board = board();
    let agent = board.start(&[]).await;
    let mut one = agent.stream(CONTROLLER, "?heartbeat=50ms").await;
    assert_eq!(one.status, 200);
    agent.streams_open(1).await;
    let two = agent.stream(CONTROLLER, "?heartbeat=50ms").await;
    assert_eq!(two.status, 200);
    agent.streams_open(2).await;
    let three = agent.stream(CONTROLLER, "?heartbeat=50ms").await;
    assert_eq!(three.status, 200);
    // The oldest is closed: read to its end, what it had sent before it closed.
    let ended = tokio::time::timeout(WAIT, async { while one.next().await.is_some() {} }).await;
    assert!(ended.is_ok(), "the oldest stream never ended");
    agent.streams_open(2).await;
    drop((two, three));
}

#[tokio::test]
async fn a_stream_whose_reader_stops_reading_is_closed_once_its_writes_stall() {
    use tokio::io::AsyncWriteExt;
    let board = board();
    let agent = board.start(&["--fixture-stall=300ms"]).await;
    // A stream asked for by hand, its socket's receive buffer small: nothing read.
    let socket = tokio::net::TcpSocket::new_v4().expect("a socket");
    socket.set_recv_buffer_size(4096).expect("a small buffer");
    let mut reader = socket
        .connect(agent.address().parse().expect("its address"))
        .await
        .expect("the agent answers");
    reader
        .write_all(b"GET /v2/stream?heartbeat=50ms HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: application/json\r\n\r\n")
        .await
        .expect("the request");
    agent.streams_open(1).await;
    // Changes of a 60 KiB text, each sent (a change waits 20 ms for others), faster than anyone
    // reads them: the socket's buffers fill.
    let long = "x".repeat(60 * 1024);
    for i in 0..400 {
        if agent.streams().await == 0 {
            break;
        }
        agent
            .set(&[("vision.health.mode", text(&format!("{long}{i}")))])
            .await;
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
    agent.streams_open(0).await;
    drop(reader);
}
