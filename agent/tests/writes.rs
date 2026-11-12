//! Protocol 2's writes on the wire: taken from the controller alone, refused while the board's
//! settings can't be read, signed when it requires it; actions, runs, logs, pushes.
#![cfg(feature = "fixture")]

mod common;

use std::os::unix::fs::PermissionsExt;
use std::time::Duration;

use common::{Agent, Answer, Board, CONTROLLER, Signer, WAIT};
use frc_spotter::proto::{
    Description, LogPage, Outcome, PackBundle, PackFile, RunState, Started, field_value,
};
use prost::Message;

const TEAM: &str = r#"pack: team
collectors:
  - id: health
    run: [echo, "29.5"]
    every: 1h
    fields:
      fps: {type: number}
logs:
  - id: log
    run: [./log]
actions:
  - id: echo
    run: [cat]
    input: text
  - id: wait
    run: [sleep, "30"]
  - id: export
    run: [printf, "PK"]
    response:
      output: {type: file, name: settings.zip}
"#;

fn board() -> Board {
    let board = Board::new();
    board.pack("team", TEAM);
    board.script(
        "team",
        "log",
        "echo '{\"message\": \"hello\", \"cursor\": \"1\"}'",
    );
    board
}

fn started(answer: &Answer) -> String {
    assert_eq!(answer.status, 202, "{}", answer.text());
    answer.message::<Started>().run
}

/// Waits for a run to finish, as its state on the wire says: that state.
async fn finished(agent: &Agent, run: &str) -> RunState {
    let until = tokio::time::Instant::now() + WAIT;
    loop {
        let state: RunState = agent.get(&format!("/v2/runs/{run}")).await.message();
        if !state.running {
            return state;
        }
        assert!(
            tokio::time::Instant::now() < until,
            "run {run} didn't finish"
        );
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

async fn delete(agent: &Agent, path: &str, headers: &[(&str, &str)]) -> Answer {
    agent
        .request(CONTROLLER, "DELETE", path, headers, b"")
        .await
}

fn bundle() -> Vec<u8> {
    PackBundle {
        files: vec![PackFile {
            path: "vision/pack.yaml".into(),
            executable: false,
            content: b"pack: vision\n".to_vec(),
        }],
    }
    .encode_to_vec()
}

#[tokio::test]
async fn the_controller_runs_an_action_and_reads_its_run() {
    let board = board();
    let agent = board.start(&[]).await;
    let answer = agent.post("/v2/actions/team.echo", b"hello\n").await;
    assert_eq!(answer.header("spotter-protocol"), Some("2.0"));
    let run = started(&answer);
    let state = finished(&agent, &run).await;
    let response = state.result.expect("a result").response;
    assert_eq!(
        response[3].value,
        Some(field_value::Value::Text("hello\n".into()))
    );
    assert_eq!(agent.get(&format!("/v2/runs/{run}/log")).await.status, 200);
    let input = agent.post("/v2/actions/team.wait", b"x").await;
    assert_eq!(input.status, 400);
    assert_eq!(input.problem(), "team.wait takes no input");
    assert_eq!(agent.post("/v2/actions/team.none", b"").await.status, 404);
    assert_eq!(agent.get("/v2/actions/team.wait").await.status, 405);

    let waiting = started(&agent.post("/v2/actions/team.wait", b"").await);
    assert_eq!(agent.post("/v2/actions/team.wait", b"").await.status, 409);
    let path = format!("/v2/runs/{waiting}");
    assert_eq!(delete(&agent, &path, &[]).await.status, 204);
    let cancelled = finished(&agent, &waiting).await.result.expect("a result");
    assert_eq!(cancelled.outcome, Outcome::Cancelled as i32);
    assert_eq!(delete(&agent, &path, &[]).await.status, 409);
    assert_eq!(agent.get("/v2/runs/0123456789abcdef").await.status, 404);
    assert_eq!(agent.get("/v2/runs/nope").await.status, 404);
    let put = agent.request(CONTROLLER, "PUT", &path, &[], b"").await;
    assert_eq!(put.status, 405);

    let export = started(&agent.post("/v2/actions/team.export", b"").await);
    finished(&agent, &export).await;
    let file = agent.get(&format!("/v2/runs/{export}/files/output")).await;
    assert_eq!(file.status, 200);
    assert_eq!(file.text(), "PK");
    assert_eq!(
        file.header("content-disposition"),
        Some("attachment; filename=\"settings.zip\"")
    );
    assert_eq!(
        agent.get(&format!("/v2/runs/{export}/other")).await.status,
        404
    );
}

#[tokio::test]
async fn writes_are_taken_from_the_controller_alone() {
    let board = board();
    // The board is at 10.12.34.11, so its controller is 10.12.34.2: this test isn't it.
    let agent = board
        .start_unnamed(&["--fixture-addresses=10.12.34.11"])
        .await;
    let refused = agent.post("/v2/actions/core.reboot", b"").await;
    assert_eq!(refused.status, 403);
    assert_eq!(
        refused.problem(),
        "only the robot controller (10.12.34.2) may run an action"
    );
    assert_eq!(
        delete(&agent, "/v2/runs/0123456789abcdef", &[])
            .await
            .status,
        403
    );
    assert_eq!(agent.post("/v2/packs", b"bundle").await.status, 403);
    agent
        .said("Refused a write (run an action) from 127.0.0.1: not the robot controller")
        .await;
    // Reading is open to anyone.
    assert_eq!(agent.get("/v2/describe").await.status, 200);
    assert_eq!(agent.get("/v2/logs/team.log").await.status, 200);
}

#[tokio::test]
async fn a_board_on_no_robot_network_takes_no_writes() {
    let board = board();
    let agent = board.start_unnamed(&["--fixture-addresses="]).await;
    let problem = agent.post("/v2/actions/core.reboot", b"").await.problem();
    assert!(
        problem
            .starts_with("writes are taken from nobody: this computer has no 10.TE.AM.x address"),
        "{problem}"
    );
}

#[tokio::test]
async fn settings_that_cant_be_read_refuse_every_write_and_reading_works() {
    let board = board();
    board.config("{\"acceptPushes\": fals}");
    let agent = board.start(&[]).await;
    let refused = agent.post("/v2/actions/core.reboot", b"").await;
    assert_eq!(refused.status, 503);
    let problem = refused.problem();
    assert!(
        problem.starts_with(
            "every write is refused while /etc/frc-spotter/agent.json can't be read: "
        ),
        "{problem}"
    );
    assert_eq!(
        delete(&agent, "/v2/runs/0123456789abcdef", &[])
            .await
            .status,
        503
    );
    assert_eq!(agent.post("/v2/packs", b"bundle").await.status, 503);
    let description: Description = agent.get("/v2/describe").await.message();
    assert!(
        description.problems[0]
            .starts_with("/etc/frc-spotter/agent.json: can't be read, so every write is refused: "),
        "{:?}",
        description.problems
    );
    assert!(description.refuses_pushes);
}

#[tokio::test]
async fn a_paged_log_and_its_problems() {
    let board = board();
    let agent = board.start(&[]).await;
    let page: LogPage = agent.get("/v2/logs/team.log?limit=5").await.message();
    assert_eq!(page.entries[0].message, "hello");
    assert_eq!(agent.get("/v2/logs/team.log?from=after").await.status, 400);
    assert_eq!(agent.get("/v2/logs/team.none").await.status, 404);
}

#[tokio::test]
async fn a_board_that_refuses_pushes_refuses_them() {
    let board = board();
    board.config("{\"acceptPushes\": false}");
    let agent = board.start(&[]).await;
    let refused = agent.post("/v2/packs", b"bundle").await;
    assert_eq!(refused.status, 403);
    assert_eq!(
        refused.problem(),
        "this board refuses pushes: its agent.json says acceptPushes: false"
    );
}

/// What a test's agent logs where a board's would end, for systemd to start it again.
const WOULD_END: &str = "Would end now, for systemd to start it again";

#[tokio::test]
async fn a_push_puts_the_packs_in_place_and_ends_the_agent() {
    let board = board();
    let agent = board.start(&["--fixture-stays"]).await;
    let pushed = agent
        .request(
            CONTROLLER,
            "POST",
            "/v2/packs",
            &[("content-type", "application/x-protobuf")],
            &bundle(),
        )
        .await;
    assert_eq!(pushed.status, 202, "{}", pushed.text());
    agent.said(WOULD_END).await;
    let yaml = board.path("/var/lib/frc-spotter/packs/vision/pack.yaml");
    assert_eq!(
        std::fs::read_to_string(&yaml).expect("pushed"),
        "pack: vision\n"
    );
    let not_a_bundle = agent.post("/v2/packs", b"not a bundle").await;
    assert_eq!(not_a_bundle.status, 400);
    let mode = std::fs::metadata(&yaml)
        .expect("pushed")
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o644);
    let ends = agent
        .lines()
        .iter()
        .filter(|l| l.contains(WOULD_END))
        .count();
    assert_eq!(ends, 1);
}

#[tokio::test]
async fn a_push_whose_robot_left_before_its_answer_still_ends_the_agent() {
    use tokio::io::AsyncWriteExt;
    let board = board();
    let agent = board.start(&["--fixture-stays"]).await;
    // Old bundles to tidy away once the packs are swapped in: time to go before the answer.
    for i in 0..2000 {
        board.write(
            &format!("/var/lib/frc-spotter/pushed/old-{i}/pack.yaml"),
            "pack: old\n",
        );
    }
    let body = bundle();
    let mut robot = tokio::net::TcpStream::connect(agent.address())
        .await
        .expect("the agent answers");
    let head = format!(
        "POST /v2/packs HTTP/1.1\r\nHost: {}\r\nContent-Type: application/x-protobuf\r\nContent-Length: {}\r\n\r\n",
        agent.address(),
        body.len()
    );
    robot.write_all(head.as_bytes()).await.expect("its head");
    robot.write_all(&body).await.expect("its body");
    robot.flush().await.expect("sent");
    // Gone once the packs are swapped in, as a robot that's switched off goes: reset, so the
    // answer can't be sent.
    let pushed = board.path("/var/lib/frc-spotter/packs");
    let until = tokio::time::Instant::now() + WAIT;
    while !std::fs::symlink_metadata(&pushed).is_ok_and(|m| m.is_symlink()) {
        assert!(tokio::time::Instant::now() < until, "never swapped in");
        tokio::time::sleep(Duration::from_millis(1)).await;
    }
    robot.set_zero_linger().expect("a reset");
    drop(robot);
    agent.said(WOULD_END).await;
}

#[tokio::test]
async fn a_board_requiring_signatures_takes_signed_writes_only() {
    let team = Signer::new(1);
    let board = board();
    board.config(&format!("{{\"trustedKeys\": [\"{}\"]}}", team.public));
    let agent = board.start(&[]).await;
    let stream = agent.stream(CONTROLLER, "").await;
    let challenge = stream.challenge();
    let unsigned = agent.post("/v2/actions/team.echo", b"hi").await;
    assert_eq!(unsigned.status, 401);
    assert_eq!(
        unsigned.problem(),
        "this board requires signed writes, and this one isn't signed"
    );
    let signature = team.sign(&challenge, 1, "POST", "/v2/actions/team.echo", b"hi");
    let signed = |signature: String| {
        let agent = &agent;
        async move {
            agent
                .request(
                    CONTROLLER,
                    "POST",
                    "/v2/actions/team.echo",
                    &[("spotter-signature", &signature)],
                    b"hi",
                )
                .await
        }
    };
    let run = started(&signed(signature.clone()).await);
    finished(&agent, &run).await;
    // Replayed: refused.
    assert_eq!(signed(signature).await.status, 401);
    let path = format!("/v2/runs/{run}");
    let cancel = team.sign(&challenge, 2, "DELETE", &path, b"");
    let cancelled = delete(&agent, &path, &[("spotter-signature", &cancel)]).await;
    assert_eq!(cancelled.status, 409);
    drop(stream);
}
