//! A pack's logs: entries read from JSON lines by its map, and pages asked for by SPOTTER_*.

use std::collections::HashMap;
use std::time::Instant;

use super::*;
use crate::packs::load;
use crate::proto::LogLevel;
use crate::testing::Fixture;

/// A log script paging a file of numbered lines by SPOTTER_*: its cursor is the line's number. It
/// prints what it was asked in each entry's source, so the test sees the environment.
const PAGER: &str = r#"from=${SPOTTER_FROM}; cursor=${SPOTTER_CURSOR:-0}; limit=${SPOTTER_LIMIT}
total=20
case $from in
  latest) first=$((total - limit + 1)) last=$total ;;
  before) first=$((cursor - limit)) last=$((cursor - 1)) ;;
  after) first=$((cursor + 1)) last=$((cursor + limit)) ;;
esac
[ $first -lt 1 ] && first=1
[ $last -gt $total ] && last=$total
n=$first
while [ $n -le $last ]; do
  level=info; [ $((n % 5)) -eq 0 ] && level=error
  echo "{\"time\": \"2026-10-02T12:00:$(printf %02d $n)Z\", \"level\": \"$level\", \"source\": \"$from/$SPOTTER_LEVEL\", \"message\": \"line $n\", \"cursor\": \"$n\"}"
  n=$((n + 1))
done"#;

/// A request's query, its pairs in order.
type Query<'a> = &'a [(&'a str, &'a str)];

fn paging(pairs: Query) -> Result<Paging, Refused> {
    let query: HashMap<String, String> = pairs
        .iter()
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect();
    Paging::of(&query)
}

fn asked(pairs: Query) -> Paging {
    paging(pairs).expect("paging")
}

/// A board whose pack team has one log, its command a script of this body.
struct Board {
    fixture: Fixture,
    commands: Arc<Commands>,
}

impl Board {
    fn new() -> Board {
        let fixture = Fixture::new();
        let commands = Arc::new(Commands::new(fixture.host.clone()));
        Board { fixture, commands }
    }

    fn logs(&self, timeout: Duration) -> Logs {
        let packs = load::load(&self.fixture.host, "", false).packs;
        Logs::new(self.commands.clone(), &packs, timeout)
    }

    fn team(&self, body: &str) -> Logs {
        let folder = self.fixture.pack(
            "team",
            "pack: team\nlogs:\n  - id: log\n    label: Its log\n    run: [./log]\n",
        );
        self.fixture.script(&format!("{folder}/log"), body);
        self.logs(TIMEOUT)
    }
}

impl Drop for Board {
    fn drop(&mut self) {
        self.commands.stop_all();
    }
}

fn messages(page: &LogPage) -> Vec<&str> {
    page.entries.iter().map(|e| e.message.as_str()).collect()
}

fn cursors(page: &LogPage) -> Vec<&str> {
    page.entries.iter().map(|e| e.cursor.as_str()).collect()
}

#[test]
fn paging_is_checked_and_passed_as_the_environment() {
    assert_eq!(
        asked(&[]),
        Paging {
            from: paging::From::Latest,
            cursor: String::new(),
            limit: 100,
            level: LogLevel::Debug,
        }
    );
    let environment = asked(&[
        ("from", "after"),
        ("cursor", "c1"),
        ("limit", "1000"),
        ("level", "3"),
    ])
    .environment();
    let wanted: Vec<(String, String)> = [
        ("SPOTTER_FROM", "after"),
        ("SPOTTER_CURSOR", "c1"),
        ("SPOTTER_LIMIT", "1000"),
        ("SPOTTER_LEVEL", "error"),
    ]
    .iter()
    .map(|(k, v)| (k.to_string(), v.to_string()))
    .collect();
    assert_eq!(environment, wanted);
    for bad in [
        ("from", "first"),
        ("from", "before"),
        ("limit", "0"),
        ("limit", "1001"),
        ("limit", "x"),
        ("level", "loud"),
    ] {
        let refused = paging(&[bad]).expect_err("refused");
        assert_eq!(refused.status, 400, "{bad:?}");
    }
}

#[tokio::test]
async fn a_page_is_the_commands_entries_with_cursors_either_side() {
    let board = Board::new();
    let logs = board.team(PAGER);
    let latest = logs
        .page("team.log", &asked(&[("limit", "5")]))
        .await
        .expect("a page");
    assert_eq!(
        messages(&latest),
        ["line 16", "line 17", "line 18", "line 19", "line 20"]
    );
    assert_eq!(latest.entries[0].source, "latest/debug");
    assert_eq!(latest.entries[4].level, LogLevel::Error as i32);
    assert_eq!(latest.entries[0].time_micros, 1_790_942_416_000_000);
    assert_eq!(latest.before, "16");
    assert_eq!(latest.after, "20");
    let before = logs
        .page(
            "team.log",
            &asked(&[
                ("from", "before"),
                ("cursor", "4"),
                ("limit", "5"),
                ("level", "warning"),
            ]),
        )
        .await
        .expect("a page");
    assert_eq!(messages(&before), ["line 1", "line 2", "line 3"]);
    assert_eq!(before.entries[0].source, "before/warning");
    // Fewer than asked for, looking back: the start.
    assert_eq!(before.before, "");
    let after = logs
        .page("team.log", &asked(&[("from", "after"), ("cursor", "20")]))
        .await
        .expect("a page");
    assert!(after.entries.is_empty());
    assert_eq!(after.after, "20");
    assert_eq!(after.before, "20");
}

#[tokio::test]
async fn a_command_that_prints_more_than_asked_is_cut_to_the_limit() {
    let board = Board::new();
    let logs = board.team(
        "for n in 1 2 3 4 5; do echo \"{\\\"message\\\": \\\"$n\\\", \\\"cursor\\\": \\\"$n\\\"}\"; done",
    );
    let rows: [(Query, [&str; 2]); 2] = [
        (&[("limit", "2")], ["4", "5"]),
        (
            &[("from", "after"), ("cursor", "0"), ("limit", "2")],
            ["1", "2"],
        ),
    ];
    for (query, wanted) in rows {
        let page = logs.page("team.log", &asked(query)).await.expect("a page");
        assert_eq!(messages(&page), wanted, "{query:?}");
    }
}

#[tokio::test]
async fn a_command_that_fails_is_a_problem() {
    let board = Board::new();
    let failed = board
        .team("echo broken >&2; exit 2")
        .page("team.log", &asked(&[]))
        .await
        .expect_err("refused");
    assert_eq!(failed.status, 502);
    assert_eq!(
        failed.reason,
        "log team.log's command failed: exit 2: broken"
    );
    let none = board
        .team("true")
        .page("team.nothing", &asked(&[]))
        .await
        .expect_err("refused");
    assert_eq!(none.status, 404);
    // A pushed pack, which isn't checked as it's loaded: its program missing as it's run.
    let folder = board.fixture.pushed(
        "gone",
        "pack: gone\nlogs:\n  - id: log\n    run: [./missing]\n",
    );
    let gone = board
        .logs(TIMEOUT)
        .page("gone.log", &asked(&[]))
        .await
        .expect_err("refused");
    assert_eq!(
        gone.reason,
        format!("log gone.log's command: no such file: {folder}/missing")
    );
}

#[tokio::test]
async fn a_command_that_leaves_a_child_holding_its_output_isnt_waited_for() {
    let board = Board::new();
    let folder = board.fixture.pack(
        "lingering",
        "pack: lingering\nlogs:\n  - id: log\n    run: [./log]\n",
    );
    board.fixture.script(&format!("{folder}/log"), "sleep 30 &");
    let logs = board.logs(Duration::from_millis(300));
    let started = Instant::now();
    // It exits at once; what it left holding its output is stopped, and never holds the page up.
    let page = logs
        .page("lingering.log", &asked(&[]))
        .await
        .expect("a page");
    assert!(started.elapsed() < Duration::from_secs(3));
    assert!(page.entries.is_empty());
}

#[test]
fn a_runs_log_is_sliced_by_its_places_and_level() {
    let all: Vec<LogEntry> = (1..=10)
        .map(|n| LogEntry {
            cursor: n.to_string(),
            message: format!("step {n}"),
            level: if n == 5 {
                LogLevel::Error
            } else {
                LogLevel::Info
            } as i32,
            ..LogEntry::default()
        })
        .collect();
    let slice = |query: Query| {
        let paging = asked(query);
        let mut slice = Slice::new(&paging);
        for entry in &all {
            if !slice.take(entry.clone()) {
                break;
            }
        }
        slice.page()
    };
    assert_eq!(cursors(&slice(&[("limit", "3")])), ["8", "9", "10"]);
    assert_eq!(
        cursors(&slice(&[("from", "after"), ("cursor", "8")])),
        ["9", "10"]
    );
    let back = slice(&[("from", "before"), ("cursor", "3")]);
    assert_eq!(cursors(&back), ["1", "2"]);
    assert_eq!(back.before, "");
    assert_eq!(cursors(&slice(&[("level", "error")])), ["5"]);
}

#[test]
fn a_commands_output_is_cut_to_the_lines_a_page_needs_before_any_is_read() {
    let output = b"1\n\n  \n2\n3\n4\npartial";
    // The last line was cut short by the most kept: left out. Blank lines aren't entries.
    let rows: [(&[u8], bool, Query, &[&str]); 4] = [
        (output, true, &[("limit", "2")], &["3", "4"]),
        (output, false, &[("limit", "2")], &["4", "partial"]),
        (
            output,
            true,
            &[("from", "after"), ("cursor", "0"), ("limit", "2")],
            &["1", "2"],
        ),
        (b"", false, &[], &[]),
    ];
    for (output, truncated, query, lines) in rows {
        assert_eq!(needed(output, truncated, &asked(query)), lines, "{query:?}");
    }
}
