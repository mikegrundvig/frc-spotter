//! The agent as its own program: it starts, serves with nothing configured, and stops cleanly.
#![cfg(feature = "fixture")]

mod common;

use common::Board;
use frc_spotter::proto::Description;

#[tokio::test]
async fn it_serves_and_stops_cleanly() {
    let board = Board::new();
    let agent = board.start(&[]).await;
    let answer = agent.get("/v2/describe").await;
    assert_eq!(answer.status, 200);
    assert_eq!(answer.header("spotter-protocol"), Some("2.0"));
    let description: Description = answer.message();
    assert_eq!(description.identity.unwrap().hostname, "vision-front");
    assert_eq!(agent.stop().await, Some(0));
}

#[tokio::test]
async fn it_serves_its_description_with_nothing_configured_and_logs_what_it_ignored() {
    let board = Board::new();
    board.write("/etc/frc-spotter/packs/old.yaml", "pack: old\n");
    let agent = board.start(&["--bind=127.0.0.1"]).await;
    let described = agent
        .request(
            common::CONTROLLER,
            "GET",
            "/v2/describe",
            &[("accept", "application/json")],
            b"",
        )
        .await;
    assert_eq!(described.status, 200);
    assert_eq!(described.header("spotter-protocol"), Some("2.0"));
    let json = described.text();
    assert!(json.contains("\"hostname\": \"vision-front\""), "{json}");
    assert!(json.contains("\"agentVersion\": \"0.4.0-test\""), "{json}");
    agent
        .said("/etc/frc-spotter/packs/old.yaml: not a pack (a pack is a folder, <name>/pack.yaml), ignored")
        .await;
}
