//! `status` against a real agent, and one that isn't there: the program as a person runs it.
#![cfg(feature = "fixture")]

mod common;

use common::{Board, PROGRAM};

/// What the program printed, and its exit code.
async fn status(args: &[&str]) -> (Option<i32>, String) {
    let output = tokio::process::Command::new(PROGRAM)
        .arg("status")
        .args(args)
        .output()
        .await
        .expect("the program runs");
    (
        output.status.code(),
        String::from_utf8_lossy(&output.stdout).into_owned(),
    )
}

#[tokio::test]
async fn status_prints_a_boards_problems_values_identity_and_packs() {
    let board = Board::new();
    board
        .pack(
            "vision",
            &[
                "pack: vision",
                "version: 1.2.0",
                "collectors:",
                "  - id: health",
                "    run: [./health]",
                "    every: 1h",
                "    fields:",
                "      fps: {label: Frame rate, type: number, unit: fps, warn: {below: 30}}",
                "      mode: {type: text}",
                "      armed: {type: boolean}",
                "      camera: {type: text}",
                "",
            ]
            .join("\n"),
        )
        .script(
            "vision",
            "health",
            "echo '{\"fps\": 12.5, \"mode\": \"tracking\", \"armed\": true}'",
        )
        .pack("broken", "pack: broken\nversoin: 1\n");
    let agent = board.start(&[]).await;
    // Once its collector has run, as status reads the values it has.
    let until = tokio::time::Instant::now() + common::WAIT;
    let (code, out) = loop {
        let (code, out) = status(&[&agent.address()]).await;
        if !out.contains("not collected yet") {
            break (code, out);
        }
        assert!(
            tokio::time::Instant::now() < until,
            "never collected:\n{out}"
        );
    };
    assert_eq!(code, Some(0), "{out}");
    let lines: Vec<&str> = out.lines().collect();
    assert_eq!(
        lines[0],
        format!(
            "vision-front ({}): agent 0.4.0-test, protocol 2.0",
            agent.address()
        )
    );
    assert_eq!(lines[1], "Problems: 1");
    assert!(
        lines[2].contains("/broken/pack.yaml:2: unknown key \"versoin\""),
        "{}",
        lines[2]
    );
    for said in [
        "  vision.health.fps     12.5 fps  (Frame rate)  warning: below 30 fps\n",
        "  vision.health.mode    tracking\n",
        "  vision.health.armed   true\n",
        "  vision.health.camera  unavailable: its output has no \"camera\"\n",
        "  hostname   vision-front\n",
        "  vision 1.2.0  installed  /etc/frc-spotter/packs/vision\n",
    ] {
        assert!(out.contains(said), "{said}\n{out}");
    }
}

#[tokio::test]
async fn status_of_a_board_not_there_says_why_in_plain_words() {
    let (port, _kept) = common::unserved_port();
    let address = format!("127.0.0.1:{port}");
    let (code, out) = status(&[&address, "--wait=1s"]).await;
    assert_eq!(code, Some(1));
    assert!(
        out.starts_with(&format!(
            "{address}: not reached: connection refused: is frc-spotter running on it?"
        )),
        "{out}"
    );
}
