//! Running a pack's commands for real: processes, the board's own web pages, and files.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

use super::*;
use crate::packs::command::{Http, Method};
use crate::testing::{Fixture, Page, unserved_port, web};

const SECOND: Duration = Duration::from_secs(1);

/// A board with one pack, vision, and what runs its commands.
struct Board {
    fixture: Fixture,
    commands: Arc<Commands>,
    folder: String,
}

impl Board {
    fn new() -> Board {
        Board::with_readers(MAX_READERS)
    }

    fn with_readers(readers: usize) -> Board {
        let fixture = Fixture::new();
        let commands = Arc::new(Commands::with_readers(fixture.host.clone(), readers));
        let folder = fixture.pack("vision", "pack: vision\n");
        Board {
            fixture,
            commands,
            folder,
        }
    }

    /// Runs a command as a collector does: no input, its output bounded.
    async fn command(&self, command: Command, timeout: Duration, max: usize) -> Ran {
        self.with(command, timeout, Options::collector(max)).await
    }

    async fn with(&self, command: Command, timeout: Duration, options: Options) -> Ran {
        self.commands
            .run(
                &self.folder,
                &command,
                timeout,
                options,
                &Cancellation::default(),
            )
            .await
    }

    async fn run(&self, argv: &[&str]) -> Ran {
        self.command(argv_of(argv), SECOND, 1024).await
    }
}

impl Drop for Board {
    fn drop(&mut self) {
        self.commands.stop_all();
    }
}

fn argv_of(argv: &[&str]) -> Command {
    Command::Run {
        argv: argv.iter().map(|a| a.to_string()).collect(),
    }
}

fn get(url: &str) -> Command {
    request(Method::Get, url, "", "")
}

fn request(method: Method, url: &str, form: &str, filename: &str) -> Command {
    Command::Http(Http {
        method,
        url: url.into(),
        form: form.into(),
        filename: filename.into(),
    })
}

/// Whether a process is still running: not a zombie, and there.
fn alive(pid: u32) -> bool {
    std::fs::read_to_string(format!("/proc/{pid}/stat"))
        .is_ok_and(|stat| !stat.rsplit(')').next().unwrap_or("").starts_with(" Z"))
}

/// Kills, by the pid a command wrote, what it may have left running: the test's own to stop.
fn kill_written(file: &std::path::Path, group: bool) {
    let written = std::fs::read_to_string(file).unwrap_or_default();
    let Ok(pid) = written.trim().parse::<i32>() else {
        return;
    };
    let pid = nix::unistd::Pid::from_raw(pid);
    let signal = nix::sys::signal::Signal::SIGKILL;
    let _ = if group {
        nix::sys::signal::killpg(pid, signal)
    } else {
        nix::sys::signal::kill(pid, signal)
    };
}

#[tokio::test]
async fn a_packs_own_program_runs_from_its_folder() {
    let board = Board::new();
    board
        .fixture
        .script(&format!("{}/where", board.folder), "pwd; echo \"$1\"");
    let ran = board.run(&["./where", "front"]).await;
    assert!(ran.completed(), "{}", ran.message);
    assert_eq!(ran.code, 0);
    assert_eq!(
        ran.text(),
        format!("{}\nfront\n", board.fixture.path(&board.folder).display())
    );
}

#[tokio::test]
async fn a_program_by_absolute_path_or_name_runs_with_its_arguments_and_no_shell() {
    let board = Board::new();
    board.fixture.script("/opt/team/health", "echo \"$#:$1\"");
    let ran = board.run(&["/opt/team/health", "a b; echo c"]).await;
    assert_eq!(ran.text(), "1:a b; echo c\n");
    let ran = board.run(&["sh", "-c", "echo 42; exit 3"]).await;
    assert_eq!(ran.code, 3);
    assert_eq!(ran.text(), "42\n");
}

#[tokio::test]
async fn standard_errors_first_line_says_why() {
    let board = Board::new();
    let ran = board
        .run(&[
            "sh",
            "-c",
            "echo '' >&2; echo 'no such unit' >&2; echo more >&2; exit 4",
        ])
        .await;
    assert_eq!(ran.errors, "no such unit");
    assert_eq!(ran.code, 4);
}

/// Programs that can't be started, and why each says it couldn't.
#[tokio::test]
async fn a_program_that_isnt_there_or_cant_run_could_not_start() {
    let board = Board::new();
    let folder = &board.folder;
    board
        .fixture
        .write(&format!("{folder}/plain"), "echo not executable\n");
    let rows = [
        ("./nothing", format!("no such file: {folder}/nothing")),
        (
            "./plain",
            format!(
                "not executable ({folder}/plain): chmod +x, or name its interpreter: run: [sh, ./plain]"
            ),
        ),
        (
            "no-such-program-anywhere",
            "no such file: no-such-program-anywhere".to_string(),
        ),
    ];
    for (program, message) in rows {
        let ran = board.run(&[program]).await;
        assert_eq!(ran.outcome, Outcome::CouldNotStart, "{program}");
        assert_eq!(ran.message, message);
    }
}

#[tokio::test]
async fn a_program_that_overruns_is_killed_with_what_it_started() {
    let board = Board::new();
    let started = Instant::now();
    let ran = board
        .command(
            argv_of(&["sh", "-c", "echo started; sleep 30 & sleep 30"]),
            Duration::from_millis(300),
            1024,
        )
        .await;
    assert!(started.elapsed() < Duration::from_secs(5));
    assert_eq!(ran.outcome, Outcome::TimedOut);
    assert_eq!(ran.message, "timed out after 300ms");
    assert_eq!(ran.text(), "started\n");
}

#[tokio::test]
async fn a_command_ends_when_it_exits_and_what_it_left_holding_its_output_is_stopped() {
    let board = Board::new();
    let pid = board.fixture.folder().join("child");
    let started = Instant::now();
    // sh exits at once; the sleep it left in the background holds its output open.
    let ran = board
        .command(
            argv_of(&[
                "sh",
                "-c",
                &format!("echo $$ > {}; sleep 30 & echo left", pid.display()),
            ]),
            Duration::from_secs(30),
            1024,
        )
        .await;
    kill_written(&pid, true);
    assert!(started.elapsed() < Duration::from_secs(5));
    assert_eq!(ran.outcome, Outcome::Completed);
    assert_eq!(ran.code, 0);
    assert_eq!(ran.text(), "left\n");
    assert!(ran.ended.is_none());
}

#[tokio::test]
async fn a_command_past_its_deadline_is_stopped_with_its_whole_group_orphans_included() {
    let board = Board::new();
    let pid = board.fixture.folder().join("orphan");
    let started = Instant::now();
    // The inner sh exits at once, leaving its sleep an orphan: no descendant of the command, but
    // in its process group.
    let script = format!(
        "sh -c 'sleep 30 > /dev/null 2>&1 & echo $! > {}'; sleep 30",
        pid.display()
    );
    let ran = board
        .command(
            argv_of(&["sh", "-c", &script]),
            Duration::from_millis(300),
            1024,
        )
        .await;
    assert!(started.elapsed() < Duration::from_secs(3));
    assert_eq!(ran.outcome, Outcome::TimedOut);
    assert_eq!(ran.message, "timed out after 300ms");
    let orphan: u32 = std::fs::read_to_string(&pid)
        .expect("its pid")
        .trim()
        .parse()
        .expect("a pid");
    // Killed, it's gone once its parent (init, or a subreaper) has reaped it.
    let until = Instant::now() + Duration::from_secs(5);
    while alive(orphan) && Instant::now() < until {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert!(!alive(orphan));
}

#[tokio::test]
async fn what_takes_its_standard_error_failing_neither_stops_it_nor_is_asked_again() {
    let board = Board::new();
    let asked = Arc::new(AtomicUsize::new(0));
    let counted = asked.clone();
    // As a run's log that can't be written fails: every line it's handed.
    let failing = Options {
        input: None,
        environment: Vec::new(),
        max_output: 1024,
        max_errors: 64 * 1024,
        errors: Some(Box::new(move |_: &str| {
            counted.fetch_add(1, Ordering::SeqCst);
            Err(())
        })),
        grace: Duration::ZERO,
    };
    // A line, a pause for it to be taken, then more: written to a pipe nobody reads, they'd kill
    // it.
    let script = "echo first >&2; sleep 0.2; i=0; while [ $i -lt 100 ]; do echo more >&2; i=$((i+1)); done; echo done";
    let ran = board
        .with(
            argv_of(&["sh", "-c", script]),
            Duration::from_secs(5),
            failing,
        )
        .await;
    assert!(ran.completed(), "{}", ran.message);
    assert_eq!(ran.code, 0);
    assert_eq!(ran.text(), "done\n");
    assert_eq!(ran.errors, "first");
    assert_eq!(asked.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn what_left_its_group_holding_its_output_leaves_no_reader_behind() {
    let board = Board::new();
    let pid = board.fixture.folder().join("session");
    // The sleep starts a session of its own: outside the command's group, and no descendant of it
    // once sh has exited, it holds the command's output open.
    let script = format!(
        "setsid sh -c 'echo $$ > {}; exec sleep 30' & echo left; sleep 0.2",
        pid.display()
    );
    let ran = board
        .command(
            argv_of(&["sh", "-c", &script]),
            Duration::from_secs(30),
            1024,
        )
        .await;
    kill_written(&pid, false);
    assert_eq!(ran.outcome, Outcome::Completed);
    assert_eq!(ran.text(), "left\n");
    assert_eq!(board.commands.reading(), 0);
}

#[tokio::test]
async fn a_command_with_no_room_to_read_its_output_could_not_start() {
    let board = Board::with_readers(2);
    let first = Cancellation::default();
    let running = {
        let commands = board.commands.clone();
        let folder = board.folder.clone();
        let first = first.clone();
        tokio::spawn(async move {
            commands
                .run(
                    &folder,
                    &argv_of(&["sleep", "30"]),
                    Duration::from_secs(30),
                    Options::collector(1024),
                    &first,
                )
                .await
                .outcome
        })
    };
    let until = Instant::now() + Duration::from_secs(5);
    while board.commands.reading() < 2 && Instant::now() < until {
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(board.commands.reading(), 2);
    let refused = board.run(&["true"]).await;
    assert_eq!(refused.outcome, Outcome::CouldNotStart);
    assert_eq!(
        refused.message,
        "no room to read its output: 2 readers are busy, held by what commands left running"
    );
    first.cancel();
    let ended = tokio::time::timeout(Duration::from_secs(10), running).await;
    assert_eq!(
        ended.expect("it ends").expect("its task"),
        Outcome::Cancelled
    );
    // Once the first has ended, its room is given back.
    assert_eq!(board.commands.reading(), 0);
}

#[tokio::test]
async fn an_environment_no_process_takes_could_not_start_and_isnt_quoted() {
    let board = Board::new();
    let options = Options {
        environment: vec![("SPOTTER_CURSOR".into(), "a\u{0}b".into())],
        ..Options::collector(1024)
    };
    let ran = board.with(argv_of(&["true"]), SECOND, options).await;
    assert_eq!(ran.outcome, Outcome::CouldNotStart);
    assert_eq!(
        ran.message,
        "its environment can't be given to a process: a value has a character none takes"
    );
}

#[tokio::test]
async fn output_past_the_most_kept_is_drained_and_said() {
    let board = Board::new();
    let ran = board
        .command(
            argv_of(&["sh", "-c", "head -c 100000 /dev/zero; echo done >&2"]),
            SECOND,
            1000,
        )
        .await;
    assert!(ran.completed(), "{}", ran.message);
    assert_eq!(ran.output.len(), 1000);
    assert!(ran.truncated);
    assert_eq!(ran.errors, "done");
}

#[tokio::test]
async fn the_boards_own_pages_are_asked() {
    let board = Board::new();
    let port = web(|asked| match asked.path.as_str() {
        "/api/status" => Page::new(200, &format!("{} {{\"up\": true}}", asked.method)),
        "/missing" => Page::new(404, "no!"),
        _ => Page {
            after: Duration::from_secs(3),
            ..Page::new(200, "")
        },
    })
    .await;
    let at = format!("http://localhost:{port}");
    let got = board
        .command(get(&format!("{at}/api/status")), SECOND, 1024)
        .await;
    assert_eq!(got.code, 200);
    assert_eq!(got.text(), "GET {\"up\": true}");
    let posted = board
        .command(
            request(Method::Post, &format!("{at}/api/status"), "", ""),
            SECOND,
            1024,
        )
        .await;
    assert!(posted.text().starts_with("POST"), "{}", posted.text());
    let missing = board
        .command(get(&format!("{at}/missing")), SECOND, 1024)
        .await;
    assert!(missing.completed());
    assert_eq!(missing.code, 404);
    assert_eq!(missing.text(), "no!");
    let slow = board
        .command(get(&format!("{at}/slow")), Duration::from_millis(200), 1024)
        .await;
    assert_eq!(slow.outcome, Outcome::TimedOut);
    assert!(
        slow.message
            .starts_with("no answer within 200ms (localhost:"),
        "{}",
        slow.message
    );
    let cut = board
        .command(get(&format!("{at}/api/status")), SECOND, 4)
        .await;
    assert!(cut.truncated);
    assert_eq!(cut.text(), "GET ");
}

#[tokio::test]
async fn an_actions_input_goes_as_a_forms_file_field_named_as_the_pack_says() {
    let board = Board::new();
    let got = Arc::new(Mutex::new(Vec::new()));
    let taken = got.clone();
    let port = web(move |asked| {
        taken.lock().expect("what it got").push(asked);
        Page::new(200, "")
    })
    .await;
    let at = format!("http://localhost:{port}/upload");
    let input = board.fixture.folder().join("input");
    std::fs::write(&input, "{\"tags\": []}").expect("the input");
    for (filename, file) in [("layout.json", "layout.json"), ("", "data")] {
        let options = Options {
            input: Some(input.clone()),
            ..Options::collector(1024)
        };
        let ran = board
            .with(
                request(Method::Post, &at, "data", filename),
                SECOND,
                options,
            )
            .await;
        assert_eq!(ran.code, 200, "{}", ran.message);
        let asked = got.lock().expect("what it got").pop().expect("a request");
        assert!(
            asked
                .content_type
                .starts_with("multipart/form-data; boundary=spotter-"),
            "{}",
            asked.content_type
        );
        let body = String::from_utf8_lossy(&asked.body);
        let disposition =
            format!("Content-Disposition: form-data; name=\"data\"; filename=\"{file}\"");
        assert!(body.contains(&disposition), "{body}");
        assert!(
            body.contains("\r\n\r\n{\"tags\": []}\r\n--spotter-"),
            "{body}"
        );
    }
}

#[tokio::test]
async fn a_page_nobody_serves_is_unreachable() {
    let board = Board::new();
    let (port, _kept) = unserved_port();
    let ran = board
        .command(get(&format!("http://localhost:{port}/")), SECOND, 1024)
        .await;
    assert_eq!(ran.outcome, Outcome::Unreachable);
    assert_eq!(
        ran.message,
        format!("connection refused (localhost:{port})")
    );
}

#[tokio::test]
async fn a_file_is_read_under_the_root() {
    let board = Board::new();
    board.fixture.write("/proc/uptime", "4123.55 15991.04\n");
    let uptime = || Command::Read {
        path: "/proc/uptime".into(),
    };
    let read = board.command(uptime(), SECOND, 1024).await;
    assert_eq!(read.text(), "4123.55 15991.04\n");
    assert!(!read.truncated);
    let cut = board.command(uptime(), SECOND, 4).await;
    assert_eq!(cut.text(), "4123");
    assert!(cut.truncated);
    let gone = board
        .command(
            Command::Read {
                path: "/sys/bus/usb/devices/7-1/product".into(),
            },
            SECOND,
            1024,
        )
        .await;
    assert_eq!(gone.outcome, Outcome::CouldNotStart);
    assert_eq!(
        gone.message,
        "no such file: /sys/bus/usb/devices/7-1/product"
    );
    let folder = board
        .command(
            Command::Read {
                path: "/proc".into(),
            },
            SECOND,
            1024,
        )
        .await;
    assert!(!folder.completed());
    assert!(folder.message.starts_with("/proc: "), "{}", folder.message);
}
