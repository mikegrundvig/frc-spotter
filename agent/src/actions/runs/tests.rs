//! Actions' runs, for real: their responses, logs, limits, and what's kept of them.

use std::collections::HashMap;
use std::time::Instant;

use super::*;
use crate::board::json;
use crate::packs::command::Command;
use crate::packs::load;
use crate::proto::LogLevel;
use crate::testing::Fixture;

const TEAM: &str = r#"pack: team
actions:
  - id: ok
    run: [./ok]
    input: text
    response:
      answer: {type: number}
      report: {type: json}
  - id: fail
    run: [sh, -c, "echo nope >&2; exit 3"]
    response:
      exit: {type: number, fail: {notEquals: 0}}
  - id: slow
    run: [sleep, "30"]
    timeout: 1s
  - id: stubborn
    run: [sh, -c, "trap 'echo stopping >&2; exit 143' TERM; echo waiting >&2; while true; do sleep 0.1; done"]
  - id: big
    run: [head, -c, "2000000", /dev/zero]
  - id: big2
    run: [head, -c, "2000000", /dev/zero]
  - id: export
    run: [printf, "PK zip bytes"]
    response:
      output: {type: file, name: settings.zip}
  - id: lingering
    run: [sh, -c, "sleep 30 &"]
    timeout: 300ms
  - id: deep
    run: [./deep]
    response:
      report: {type: json}
"#;

/// A board with the team pack, its programs, and its runs, every event they tell kept.
struct Board {
    fixture: Fixture,
    commands: Arc<Commands>,
    events: Arc<Mutex<Vec<RunEvent>>>,
}

impl Board {
    fn new() -> Board {
        let fixture = Fixture::new();
        let folder = fixture.pack("team", TEAM);
        fixture.script(
            &format!("{folder}/ok"),
            "read given\necho 'step one' >&2\necho '{\"level\": \"warning\", \"message\": \"careful\"}' >&2\necho \"{\\\"answer\\\": 42, \\\"report\\\": {\\\"given\\\": \\\"$given\\\"}}\"",
        );
        // JSON nested 100,000 deep: what overflowed the stack of a run's thread, in Java.
        fixture.script(
            &format!("{folder}/deep"),
            "printf '{\"report\": '; head -c 100000 /dev/zero | tr '\\0' '['; printf '}\\n'",
        );
        let commands = Arc::new(Commands::new(fixture.host.clone()));
        Board {
            fixture,
            commands,
            events: Arc::default(),
        }
    }

    /// The runs, as an agent starting makes them.
    fn runs(&self, max_kept: u64) -> Arc<Runs> {
        let events = self.events.clone();
        let packs = load::load(&self.fixture.host, "", false).packs;
        Arc::new(Runs::new(
            self.fixture.host.clone(),
            self.commands.clone(),
            Arc::new(move |event| events.lock().expect("the events").push(event)),
            declared(&packs),
            max_kept,
        ))
    }

    fn folder(&self, run: &str) -> PathBuf {
        self.fixture.path(&format!("{FOLDER}/{run}"))
    }

    /// Waits for what the agent logs to hold this.
    async fn logged(&self, text: &str) {
        let until = Instant::now() + Duration::from_secs(10);
        while !self.fixture.log().contains(text) {
            assert!(Instant::now() < until, "never logged {text}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
}

impl Drop for Board {
    fn drop(&mut self) {
        self.commands.stop_all();
    }
}

fn input(runs: &Runs, text: &str) -> PathBuf {
    let input = runs.input_file().expect("a file for its input");
    fs::write(&input, text).expect("its input");
    input
}

fn start(runs: &Arc<Runs>, action: &str) -> String {
    runs.start(action, None).expect("it starts").run
}

/// Waits for a run to finish: its state then.
async fn finished(runs: &Runs, run: &str) -> RunState {
    let until = Instant::now() + Duration::from_secs(20);
    loop {
        let state = runs.state(run).expect("the run is kept");
        if !state.running {
            return state;
        }
        assert!(Instant::now() < until, "run {run} didn't finish");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

/// Waits for a run to have logged something.
async fn logging(runs: &Runs, run: &str) {
    let until = Instant::now() + Duration::from_secs(10);
    while runs
        .log(run, &everything())
        .expect("its log")
        .entries
        .is_empty()
    {
        assert!(Instant::now() < until, "run {run} logged nothing");
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

fn everything() -> Paging {
    Paging::of(&HashMap::new()).expect("paging")
}

fn response(state: &RunState) -> Vec<FieldValue> {
    state.result.clone().expect("a result").response
}

/// A finished run's response field, by its name: what it holds.
fn part(state: &RunState, name: &str) -> field_value::Value {
    response(state)
        .into_iter()
        .find(|value| value.name == name)
        .unwrap_or_else(|| panic!("no {name}"))
        .value
        .expect("a value")
}

fn outcome(state: &RunState) -> (Outcome, String) {
    let result = state.result.clone().expect("a result");
    (
        Outcome::try_from(result.outcome).expect("an outcome"),
        result.outcome_message,
    )
}

fn gone(why: &str) -> field_value::Value {
    field_value::Value::Unavailable(why.into())
}

#[tokio::test]
async fn a_run_takes_its_input_and_fills_its_response_and_log() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let given = input(&runs, "front camera\n");
    let run = runs.start("team.ok", Some(&given)).expect("it starts").run;
    assert!(is_id(&run), "{run}");
    let state = finished(&runs, &run).await;
    assert_eq!(state.action, "team.ok");
    assert_eq!(outcome(&state).0, Outcome::Completed);
    let names: Vec<String> = response(&state).into_iter().map(|v| v.name).collect();
    assert_eq!(
        names,
        [
            "outcome",
            "outcomeMessage",
            "exit",
            "output",
            "answer",
            "report"
        ]
    );
    assert_eq!(
        part(&state, "outcome"),
        field_value::Value::Text("completed".into())
    );
    assert_eq!(part(&state, "exit"), field_value::Value::Number(0.0));
    assert_eq!(part(&state, "answer"), field_value::Value::Number(42.0));
    assert_eq!(
        part(&state, "report"),
        field_value::Value::Json("{\"given\":\"front camera\"}".into())
    );
    let log = runs.log(&run, &everything()).expect("its log");
    let lines: Vec<(String, i32, String)> = log
        .entries
        .iter()
        .map(|e| (e.cursor.clone(), e.level, e.message.clone()))
        .collect();
    assert_eq!(
        lines,
        [
            ("1".into(), LogLevel::Info as i32, "step one".into()),
            ("2".into(), LogLevel::Warning as i32, "careful".into()),
        ]
    );
    assert!(log.entries[0].time_micros > 0);
    // As it happened: started, each line, finished.
    let events = board.events.lock().expect("the events").clone();
    let told: Vec<String> = events
        .iter()
        .map(|e| match &e.change {
            Some(run_event::Change::Started(_)) => "started".to_string(),
            Some(run_event::Change::Log(entry)) => entry.message.clone(),
            _ => "finished".to_string(),
        })
        .collect();
    assert_eq!(told, ["started", "step one", "careful", "finished"]);
    assert!(events.iter().all(|e| e.run == run && e.action == "team.ok"));
    // Its input is gone with it.
    let mut files: Vec<String> = fs::read_dir(board.folder(&run))
        .expect("its folder")
        .flatten()
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .collect();
    files.sort();
    assert_eq!(files, ["log", "output", "state"]);
}

#[tokio::test]
async fn a_run_that_completes_or_overruns_says_so() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    // A failing exit completes, for its pack's limit to judge.
    let failed = finished(&runs, &start(&runs, "team.fail")).await;
    assert_eq!(outcome(&failed).0, Outcome::Completed);
    assert_eq!(part(&failed, "exit"), field_value::Value::Number(3.0));
    // Past its timeout, it's stopped.
    let slow = finished(&runs, &start(&runs, "team.slow")).await;
    assert_eq!(
        outcome(&slow),
        (Outcome::TimedOut, "timed out after 1s".into())
    );
    assert_eq!(
        part(&slow, "outcome"),
        field_value::Value::Text("timedOut".into())
    );
    assert_eq!(part(&slow, "exit"), gone("timed out after 1s"));
}

#[tokio::test]
async fn a_cancelled_run_is_asked_to_stop_and_does() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let run = start(&runs, "team.stubborn");
    logging(&runs, &run).await;
    runs.cancel(&run).expect("cancelled");
    let state = finished(&runs, &run).await;
    assert_eq!(outcome(&state), (Outcome::Cancelled, "cancelled".into()));
    // It was asked politely, and said goodbye (its shell may say its sleep was Terminated too).
    let said: Vec<String> = runs
        .log(&run, &everything())
        .expect("its log")
        .entries
        .into_iter()
        .map(|e| e.message)
        .collect();
    let waiting = said.iter().position(|m| m == "waiting").expect("waiting");
    let stopping = said.iter().position(|m| m == "stopping").expect("stopping");
    assert!(waiting < stopping, "{said:?}");
    assert_eq!(runs.cancel(&run).expect_err("finished").status, 409);
    assert_eq!(
        runs.cancel("0123456789abcdef")
            .expect_err("not kept")
            .status,
        404
    );
}

#[tokio::test]
async fn one_run_of_an_action_at_a_time_and_two_actions_at_once() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let stubborn = start(&runs, "team.stubborn");
    let again = runs.start("team.stubborn", None).expect_err("running");
    assert_eq!(again.status, 409);
    assert_eq!(
        again.reason,
        format!("team.stubborn is running already, as run {stubborn}")
    );
    let slow = start(&runs, "team.slow");
    assert_eq!(runs.start("team.fail", None).expect_err("busy").status, 503);
    runs.cancel(&stubborn).expect("cancelled");
    finished(&runs, &stubborn).await;
    finished(&runs, &start(&runs, "team.fail")).await;
    runs.cancel(&slow).expect("cancelled");
    assert_eq!(
        runs.start("team.none", None).expect_err("no such").status,
        404
    );
}

#[tokio::test]
async fn a_file_field_is_fetched_by_its_url_as_its_name() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let run = start(&runs, "team.export");
    let state = finished(&runs, &run).await;
    assert_eq!(
        part(&state, "output"),
        field_value::Value::FileUrl(format!("/v2/runs/{run}/files/output"))
    );
    let download = runs.file(&run, "output").expect("its file");
    assert_eq!(download.name, "settings.zip");
    assert_eq!(
        fs::read_to_string(&download.path).expect("its bytes"),
        "PK zip bytes"
    );
    assert_eq!(runs.file(&run, "exit").err().expect("none").status, 404);
}

#[tokio::test]
async fn output_past_the_most_kept_is_said_and_whats_kept_is_bounded() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let first = start(&runs, "team.big");
    let big = finished(&runs, &first).await;
    assert_eq!(
        part(&big, "output"),
        gone("it printed more than the 1024 KiB kept")
    );
    assert_eq!(part(&big, "exit"), field_value::Value::Number(0.0));
    let kept = fs::metadata(board.folder(&first).join("output")).expect("its output");
    assert_eq!(kept.len(), MAX_OUTPUT as u64);
    // A run is kept until its action runs again.
    let second = start(&runs, "team.big");
    finished(&runs, &second).await;
    assert!(runs.state(&first).is_none());
    assert!(!board.folder(&first).exists());
    runs.close();
    // And the oldest is dropped when the runs kept are full.
    let runs = board.runs(3 * MAX_OUTPUT as u64 / 2);
    runs.restore();
    finished(&runs, &start(&runs, "team.big2")).await;
    assert!(runs.state(&second).is_none());
    let actions: Vec<String> = runs.states().into_iter().map(|s| s.action).collect();
    assert_eq!(actions, ["team.big2"]);
}

#[tokio::test]
async fn a_run_going_as_the_agent_stops_is_taken_up_lost_by_the_next_start() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let going = start(&runs, "team.stubborn");
    logging(&runs, &going).await;
    // The stop ends its process, and it writes no outcome of its own: it was going.
    runs.close();
    board.commands.stop_all();
    board
        .logged(&format!(
            "Run {going} of team.stubborn ended as the agent stopped"
        ))
        .await;
    let bytes = fs::read(board.folder(&going).join("state")).expect("its state");
    assert!(RunState::decode(bytes.as_slice()).expect("a state").running);
    let runs = board.runs(MAX_KEPT);
    runs.restore();
    let lost = runs.state(&going).expect("taken up");
    assert_eq!(outcome(&lost), (Outcome::Lost, LOST.into()));
}

#[tokio::test]
async fn runs_survive_a_restart_and_ones_that_were_going_are_lost() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let done = start(&runs, "team.fail");
    finished(&runs, &done).await;
    // A run that was going as the agent stopped, as it left its state.
    let going = "0000000000100abc";
    let folder = board.folder(going);
    fs::create_dir_all(&folder).expect("its folder");
    let state = RunState {
        run: going.into(),
        action: "team.stubborn".into(),
        running: true,
        result: None,
    };
    fs::write(folder.join("state"), state.encode_to_vec()).expect("its state");
    fs::write(folder.join("input"), "x").expect("its input");
    // Its log's last entry cut short as the agent stopped.
    let whole = LogEntry {
        message: "ok".into(),
        cursor: "1".into(),
        ..LogEntry::default()
    };
    let mut log = whole.encode_length_delimited_to_vec();
    log.push(0x10);
    fs::write(folder.join("log"), log).expect("its log");
    fs::write(folder.join("rubbish"), "x").expect("rubbish");
    fs::create_dir_all(board.folder("not-a-run")).expect("not a run");
    runs.close();
    let runs = board.runs(MAX_KEPT);
    runs.restore();
    let order: Vec<String> = runs.states().into_iter().map(|s| s.run).collect();
    assert_eq!(order, [going.to_string(), done.clone()]);
    let lost = runs.state(going).expect("taken up");
    assert!(!lost.running);
    assert_eq!(outcome(&lost), (Outcome::Lost, LOST.into()));
    assert_eq!(
        part(&lost, "outcome"),
        field_value::Value::Text("lost".into())
    );
    assert!(!folder.join("input").exists());
    let said: Vec<String> = runs
        .log(going, &everything())
        .expect("its log")
        .entries
        .into_iter()
        .map(|e| e.message)
        .collect();
    assert_eq!(said, ["ok"]);
    let done = runs.state(&done).expect("kept");
    assert_eq!(outcome(&done).0, Outcome::Completed);
}

#[test]
fn the_built_in_actions_are_cores() {
    let ids: Vec<String> = declared(&[]).into_iter().map(|d| d.id).collect();
    assert_eq!(ids, ["core.power-off", "core.reboot"]);
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    assert_eq!(
        runs.action("core.reboot").expect("reboot").action.command,
        Command::Run {
            argv: vec!["systemctl".into(), "reboot".into()]
        }
    );
}

#[test]
fn a_response_past_what_it_may_be_has_its_largest_fields_unavailable_saying_why() {
    let named = |name: &str, value: field_value::Value| FieldValue {
        index: 0,
        name: name.into(),
        value: Some(value),
    };
    let result = RunResult {
        outcome: Outcome::Completed as i32,
        outcome_message: String::new(),
        response: vec![
            named("exit", field_value::Value::Number(0.0)),
            named("output", field_value::Value::Text("z".repeat(900 * 1024))),
            named("summary", field_value::Value::Text("fine".into())),
        ],
    };
    let bounded = bounded(result);
    assert!(bounded.encoded_len() <= protocol::MAX_RESPONSE);
    assert_eq!(
        bounded.response[0].value,
        Some(field_value::Value::Number(0.0))
    );
    assert_eq!(bounded.response[1].name, "output");
    assert_eq!(
        bounded.response[1].value,
        Some(gone(
            "too large to send: 900 KiB, more than the 256 KiB a run's response may be (declare the whole output type: file to download it)"
        ))
    );
    assert_eq!(
        bounded.response[2].value,
        Some(field_value::Value::Text("fine".into()))
    );
}

/// An action whose output can't be read as JSON, or that leaves a child holding its output,
/// finishes, and may run again: its slot is free.
#[tokio::test]
async fn an_awkward_actions_run_finishes_and_frees_its_slot() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let deep = finished(&runs, &start(&runs, "team.deep")).await;
    assert_eq!(outcome(&deep).0, Outcome::Completed);
    let report = &response(&deep)[4];
    assert_eq!(report.name, "report");
    // Nested deeper than JSON is read: the output is its one field's value, as it is.
    let Some(field_value::Value::Json(text)) = &report.value else {
        panic!("JSON text, not {report:?}");
    };
    assert!(text.starts_with("{\"report\": [[["));
    assert!(!finished(&runs, &start(&runs, "team.deep")).await.running);

    let started = Instant::now();
    let lingering = finished(&runs, &start(&runs, "team.lingering")).await;
    // It exits at once; what it left holding its output is stopped, politely, and goes.
    assert!(started.elapsed() < Duration::from_secs(3));
    assert_eq!(outcome(&lingering).0, Outcome::Completed);
    assert!(
        !finished(&runs, &start(&runs, "team.lingering"))
            .await
            .running
    );
}

#[test]
fn jsons_depth_is_counted_outside_its_strings() {
    let nested = |depth: usize| format!("{{\"a\": {}{}}}", "[".repeat(depth), "]".repeat(depth));
    assert!(json::too_deep(&nested(64)));
    assert!(!json::too_deep(&nested(63)));
    let quoted = format!("{{\"a\": \"{}\\\"[\"}}", "[".repeat(100));
    assert!(!json::too_deep(&quoted));
    assert!(json::as_object(&nested(65)).is_none());
}

#[tokio::test]
async fn a_runs_log_is_read_by_at_most_two_at_once() {
    let board = Board::new();
    let runs = board.runs(MAX_KEPT);
    let given = input(&runs, "Ada");
    let run = runs.start("team.ok", Some(&given)).expect("it starts").run;
    finished(&runs, &run).await;
    {
        let _busy = runs
            .log_reads
            .try_acquire_many(LOGS_AT_ONCE as u32)
            .expect("every turn");
        let refused = runs.log(&run, &everything()).expect_err("busy");
        assert_eq!(refused.status, 503);
    }
    let log = runs.log(&run, &everything()).expect("its log");
    assert_eq!(log.entries.len(), 2);
}
