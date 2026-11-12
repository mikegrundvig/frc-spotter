//! A `run` command: a process in a group of its own (its own process group, as `setsid` gives),
//! so stopping it signals the whole group: what it started, and what those left running in the
//! background once their parent had exited. Its output and standard error are read on tasks of
//! their own, and the process is waited on with its deadline, never the end of its pipes: a
//! command is done when its process exits. Anything it left running that still holds its output a
//! moment later is stopped, its group with it, and so is a program that left the group but still
//! holds that output.

use std::env;
use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::ExitStatusExt;
use std::path::{Path, PathBuf};
use std::process::{ExitStatus, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use nix::sys::signal::{Signal, kill, killpg};
use nix::unistd::Pid;
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::process::{Child, Command};
use tokio::sync::OwnedSemaphorePermit;
use tokio::task::JoinHandle;
use tokio::time::{Instant, timeout_at};

use super::{Cancellation, Commands, Lines, Options, Ran, pipe_holders};
use crate::packs::command::{self, Kind};
use crate::packs::duration;
use crate::proto::Outcome;

/// The most of a command's standard error kept to say why it failed: its first line.
const MAX_ERRORS: usize = 200;

/// The longest line of standard error handed over whole; a longer one is cut.
const MAX_LINE: usize = 8 * 1024;

/// How long a command's output may stay open once it has exited, held by something it left
/// running, before that's stopped.
const LEFT: Duration = Duration::from_millis(500);

pub async fn run(
    commands: &Commands,
    folder: &str,
    argv: &[String],
    timeout: Duration,
    options: Options,
    cancellation: &Cancellation,
) -> Ran {
    let written = argv[0].as_str();
    let program = command::program_path(written, folder);
    let failed = |message: String| Ran::failed(Kind::Run, Outcome::CouldNotStart, message);
    // Found and checked first, so a program that isn't there, or can't be run, is said plainly.
    let found = if command::by_path(written) {
        commands.host.path(&program)
    } else if written.contains('/') {
        commands.host.path(folder).join(written)
    } else {
        match on_path(written) {
            Some(found) => found,
            None => return failed(format!("no such file: {written}")),
        }
    };
    if !found.exists() {
        return failed(format!("no such file: {program}"));
    }
    if found.is_dir() || !may_run(&found) {
        return failed(not_executable(&program, written));
    }
    let Some(permits) = room(commands) else {
        return failed(format!(
            "no room to read its output: {} readers are busy, held by what commands left running",
            commands.max_readers
        ));
    };
    let stdin = match &options.input {
        None => Stdio::null(),
        Some(input) => match fs::File::open(input) {
            Ok(file) => Stdio::from(file),
            Err(e) => return failed(format!("its input can't be read: {e}")),
        },
    };
    let mut builder = Command::new(&found);
    builder
        .args(&argv[1..])
        .current_dir(commands.host.path(folder))
        .envs(options.environment.iter().map(|(k, v)| (k, v)))
        .stdin(stdin)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        // Its own process group, of which it's the leader: a timeout or a cancel signals all of
        // it, what it left running in the background included.
        .process_group(0);
    let mut child = match builder.spawn() {
        Ok(child) => child,
        Err(e) if e.kind() == io::ErrorKind::InvalidInput => {
            // A variable a process can't be given (a NUL in it, say): never quoted, as a log's
            // paging is anyone's.
            return failed(
                "its environment can't be given to a process: a value has a character none takes"
                    .to_string(),
            );
        }
        Err(e) => return failed(why(&e, &program, written)),
    };
    let pid = child.id().unwrap_or(0);
    commands
        .live
        .lock()
        .expect("the commands' lock")
        .insert(pid);
    // Its pipes, by name, while it's running: what holds them once it has exited is found by them.
    let pipes = pipe_holders::of(pid);
    let [out_permit, err_permit] = permits;
    let output = Arc::new(Mutex::new(Output {
        max: options.max_output,
        ..Output::default()
    }));
    let out_read = tokio::spawn(read_output(child.stdout.take(), output.clone(), out_permit));
    let errors = Arc::new(Errors::new(options.max_errors, options.errors));
    let err_read = tokio::spawn(read_errors(child.stderr.take(), errors.clone(), err_permit));
    let mut readers = Readers {
        out: Some(out_read),
        err: Some(err_read),
    };

    let deadline = Instant::now() + timeout.max(Duration::from_millis(1));
    let mut stopped: Option<Outcome> = None;
    let mut status: Option<ExitStatus> = None;
    tokio::select! {
        exited = child.wait() => status = exited.ok(),
        _ = sleep_until(deadline) => stopped = Some(Outcome::TimedOut),
        _ = cancellation.cancelled() => stopped = Some(Outcome::Cancelled),
    }
    if stopped.is_some() {
        // Politely, then forcibly after its grace; at once with no grace.
        status = stop(&mut child, pid, options.grace, &mut readers).await;
        if status.is_none() {
            // Its grace and a second more, all it gets.
            status = timeout_at(Instant::now() + Duration::from_secs(1), child.wait())
                .await
                .ok()
                .and_then(Result::ok);
        }
    }
    // Its output, once it has exited: closed at once, unless something it left running holds it
    // open, which is stopped, its whole group, so neither it nor a reader is left behind.
    if status.is_some() && !readers.done_within(LEFT).await {
        group(pid, Signal::SIGTERM);
        if !readers.done_within(LEFT).await {
            group(pid, Signal::SIGKILL);
        }
        if !readers.done_within(LEFT).await {
            // Still held, by something outside its group (a program that started a session of
            // its own): it's found by its pipes and killed, so no reader is left blocked on them.
            pipe_holders::kill_holders(&pipes);
            readers.done_within(2 * LEFT).await;
        }
    }
    // Whatever its readers read from here on is dropped.
    errors.done();
    let (kept, truncated) = {
        let output = output.lock().expect("the output's lock");
        (output.kept.clone(), output.truncated)
    };
    let first = errors.first();
    let running = status.is_none();
    if !running {
        commands
            .live
            .lock()
            .expect("the commands' lock")
            .remove(&pid);
    }
    if stopped.is_some() || running {
        let outcome = stopped.unwrap_or(Outcome::TimedOut);
        let message = if outcome == Outcome::TimedOut {
            format!("timed out after {}", duration::show(timeout))
        } else {
            "cancelled".to_string()
        };
        let ended = running.then(|| ended(child, commands.live.clone(), pid));
        return Ran {
            kind: Kind::Run,
            outcome,
            message: if running {
                format!("{message}; its command is still running")
            } else {
                message
            },
            code: 0,
            output: kept,
            truncated,
            errors: first,
            ended,
        };
    }
    commands
        .live
        .lock()
        .expect("the commands' lock")
        .remove(&pid);
    Ran {
        kind: Kind::Run,
        outcome: Outcome::Completed,
        message: String::new(),
        code: status.map(exit_code).unwrap_or(0),
        output: kept,
        truncated,
        errors: first,
        ended: None,
    }
}

async fn sleep_until(deadline: Instant) {
    tokio::time::sleep_until(deadline).await;
}

/// A task done once a process stopped that won't go has exited at last.
fn ended(
    mut child: Child,
    live: Arc<Mutex<std::collections::HashSet<u32>>>,
    pid: u32,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        let _ = child.wait().await;
        live.lock().expect("the commands' lock").remove(&pid);
    })
}

/// An exit code as Java gives it: the process's own, or 128 and the signal that ended it.
fn exit_code(status: ExitStatus) -> i32 {
    status
        .code()
        .unwrap_or_else(|| 128 + status.signal().unwrap_or(0))
}

/// Two readers' room, or none when as many are reading as may.
fn room(commands: &Commands) -> Option<[OwnedSemaphorePermit; 2]> {
    let first = commands.readers.clone().try_acquire_owned().ok()?;
    let second = commands.readers.clone().try_acquire_owned().ok()?;
    Some([first, second])
}

/// A command's two readers, waited on.
struct Readers {
    out: Option<JoinHandle<()>>,
    err: Option<JoinHandle<()>>,
}

impl Readers {
    /// Whether both have read to their ends within so long.
    async fn done_within(&mut self, wait: Duration) -> bool {
        let until = Instant::now() + wait;
        for reader in [&mut self.out, &mut self.err] {
            if let Some(handle) = reader {
                if timeout_at(until, handle).await.is_err() {
                    return false;
                }
                *reader = None;
            }
        }
        true
    }
}

/// Stops a command, and everything it started: politely (SIGTERM), then, after its grace,
/// forcibly (SIGKILL), unless by then it has exited and nothing holds its output; at once with no
/// grace. Its whole process group is signalled, and its descendants, any that left the group. Its
/// exit, if it came within its grace.
async fn stop(
    child: &mut Child,
    pid: u32,
    grace: Duration,
    readers: &mut Readers,
) -> Option<ExitStatus> {
    let deadline = Instant::now() + grace;
    let mut status = None;
    if !grace.is_zero() {
        signal_all(pid, Signal::SIGTERM);
        status = timeout_at(deadline, child.wait())
            .await
            .ok()
            .and_then(Result::ok);
    }
    let gone = status.is_some() && readers.done_within(deadline - Instant::now()).await;
    if !gone {
        signal_all(pid, Signal::SIGKILL);
    }
    status
}

/// Signals a command's process group, and its descendants that left it.
fn signal_all(pid: u32, signal: Signal) {
    for descendant in pipe_holders::descendants(pid) {
        let _ = kill(Pid::from_raw(descendant as i32), signal);
    }
    group(pid, signal);
    let _ = kill(Pid::from_raw(pid as i32), signal);
}

/// Signals a command's process group: it's the group's leader, so its number is the group's.
fn group(pid: u32, signal: Signal) {
    if pid > 0 {
        let _ = killpg(Pid::from_raw(pid as i32), signal);
    }
}

/// Why a program couldn't start, as a person would say it.
fn why(e: &io::Error, program: &str, written: &str) -> String {
    match e.kind() {
        io::ErrorKind::NotFound => format!("no such file: {program}"),
        io::ErrorKind::PermissionDenied => not_executable(program, written),
        _ => e.to_string(),
    }
}

/// Why a program can't be run: nearly always a script without its execute bit, as a copy that
/// drops modes leaves it. The fix is said.
fn not_executable(program: &str, written: &str) -> String {
    format!("not executable ({program}): chmod +x, or name its interpreter: run: [sh, {written}]")
}

/// Whether the agent may run a file: an execute bit it has, as `access(X_OK)` checks it.
fn may_run(file: &Path) -> bool {
    nix::unistd::access(file, nix::unistd::AccessFlags::X_OK).is_ok()
}

/// Where a program named without a path is: the first that may be run of its name on the PATH.
fn on_path(name: &str) -> Option<PathBuf> {
    let path = env::var("PATH").unwrap_or_else(|_| "/usr/bin:/bin".to_string());
    path.split(':')
        .filter(|folder| !folder.is_empty())
        .map(|folder| Path::new(folder).join(name))
        .find(|candidate| {
            candidate.is_file()
                && fs::metadata(candidate).is_ok_and(|m| m.permissions().mode() & 0o111 != 0)
                && may_run(candidate)
        })
}

/// What a command's standard output keeps: at most so much, the rest read and dropped.
#[derive(Default)]
struct Output {
    max: usize,
    kept: Vec<u8>,
    truncated: bool,
}

/// Reads to its end, keeping at most the most kept: a process never blocks writing it.
async fn read_output(
    pipe: Option<impl AsyncRead + Unpin>,
    output: Arc<Mutex<Output>>,
    _room: OwnedSemaphorePermit,
) {
    let Some(mut pipe) = pipe else {
        return;
    };
    let mut chunk = vec![0u8; 8192];
    loop {
        let read = match pipe.read(&mut chunk).await {
            Ok(0) | Err(_) => return,
            Ok(read) => read,
        };
        let mut output = output.lock().expect("the output's lock");
        let room = output.max.saturating_sub(output.kept.len());
        if read > room {
            output.kept.extend_from_slice(&chunk[..room]);
            output.truncated = true;
        } else {
            output.kept.extend_from_slice(&chunk[..read]);
        }
    }
}

/// A command's standard error, read until it closes, so the process never blocks writing it: each
/// line handed over (at most `max` bytes of them, each cut to [`MAX_LINE`]) until the command's run
/// is done or what takes them fails, and the first that isn't blank kept, cut to [`MAX_ERRORS`]
/// characters, to say why it failed.
struct Errors {
    max: usize,
    handing: AtomicBool,
    state: Mutex<ErrorState>,
}

struct ErrorState {
    lines: Option<Lines>,
    first: String,
    handed: usize,
}

impl Errors {
    fn new(max: usize, lines: Option<Lines>) -> Errors {
        Errors {
            max,
            handing: AtomicBool::new(true),
            state: Mutex::new(ErrorState {
                lines,
                first: String::new(),
                handed: 0,
            }),
        }
    }

    /// Its run is over: no more lines are handed over.
    fn done(&self) {
        self.handing.store(false, Ordering::SeqCst);
    }

    fn first(&self) -> String {
        self.state.lock().expect("the errors' lock").first.clone()
    }

    fn take(&self, line: &[u8], newline: usize) {
        let text = String::from_utf8_lossy(line);
        let mut state = self.state.lock().expect("the errors' lock");
        if state.first.is_empty() && !text.trim().is_empty() {
            state.first = text.trim().chars().take(MAX_ERRORS).collect();
        }
        let length = text.encode_utf16().count();
        if !self.handing.load(Ordering::SeqCst) || state.handed + length + newline > self.max {
            return;
        }
        state.handed += length + newline;
        let failed = match state.lines.as_mut() {
            Some(lines) => lines(&text).is_err(),
            None => false,
        };
        if failed {
            // What takes the lines failed: no more are handed over, and the pipe is still read to
            // its end, so the command neither blocks nor dies writing to it.
            self.handing.store(false, Ordering::SeqCst);
        }
    }
}

async fn read_errors(
    pipe: Option<impl AsyncRead + Unpin>,
    errors: Arc<Errors>,
    _room: OwnedSemaphorePermit,
) {
    let Some(mut pipe) = pipe else {
        return;
    };
    let mut line = Vec::new();
    let mut chunk = vec![0u8; 8192];
    loop {
        let read = match pipe.read(&mut chunk).await {
            Ok(0) | Err(_) => break,
            Ok(read) => read,
        };
        for &byte in &chunk[..read] {
            if byte != b'\n' {
                if line.len() < MAX_LINE {
                    line.push(byte);
                }
                continue;
            }
            errors.take(&line, 1);
            line.clear();
        }
    }
    if !line.is_empty() {
        errors.take(&line, 0);
    }
}
