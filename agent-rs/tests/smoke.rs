//! The agent as its own program: it starts, serves, and stops cleanly.
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
