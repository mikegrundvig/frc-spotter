//! The actions' runs: started on request, each a task of its own, and kept in [`FOLDER`] (its
//! state, its log, and its output for a file field) after they finish, until the same action runs
//! again or the most kept is reached, oldest first. [`FOLDER`] is in `/run`, so the runs survive an
//! agent restart (a push's included) but not a reboot; a run that was going when the agent stopped
//! comes back `lost`.
//!
//! Bounded: one run of each action at a time, at most [`AT_ONCE`] at once, each within its
//! timeout, [`MAX_OUTPUT`] kept of its output and of its standard error. Cancelling a run stops it
//! politely (SIGTERM), then forcibly after [`GRACE`].

use std::fs;
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use prost::Message;
use tokio::sync::Semaphore;

use crate::board::host::Host;
use crate::commands::{Cancellation, Commands, Lines, Options, Ran, fill};
use crate::logs::{self, Paging, Slice};
use crate::packs::command::{Kind, OUTCOME, OUTCOME_MESSAGE};
use crate::packs::field::Field;
use crate::packs::pack::{self, Action, Pack};
use crate::proto::{
    FieldType, FieldValue, Input, LogEntry, LogPage, Outcome, RunEvent, RunResult, RunState,
    Started, field_value, run_event,
};
use crate::protocol::{self, pack_hash};

/// Where runs are kept, each in a folder of its id's.
pub const FOLDER: &str = "/run/frc-spotter/runs";

/// How many actions run at once.
const AT_ONCE: usize = 2;

/// The most of an action's output kept, and of its standard error.
pub const MAX_OUTPUT: usize = 1024 * 1024;

/// The most the kept runs may take, together.
pub const MAX_KEPT: u64 = 64 * 1024 * 1024;

/// The largest input an action takes.
pub const MAX_INPUT: u64 = 64 * 1024 * 1024;

/// How long a cancelled run has, once asked to stop, before it's killed.
const GRACE: Duration = Duration::from_secs(5);

/// How many runs' logs are read at once; another asked meanwhile is refused as busy.
const LOGS_AT_ONCE: usize = 2;

/// Why a run that was going when the agent stopped has no result.
const LOST: &str = "the agent restarted while it ran";

/// Whether text is a run's id: its start on the monotonic clock, and some randomness, 16 hex
/// characters.
pub fn is_id(text: &str) -> bool {
    text.len() == 16
        && text
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// A refusal of a request about runs, with its status.
#[derive(Debug)]
pub struct Refused {
    pub status: u16,
    pub reason: String,
}

fn refused(status: u16, reason: impl Into<String>) -> Refused {
    Refused {
        status,
        reason: reason.into(),
    }
}

/// An action the board offers, by its id, and where its command runs.
#[derive(Clone)]
pub struct Declared {
    pub id: String,
    pub folder: String,
    pub action: Action,
}

/// Every action the board offers: the built-in ones (core's), then its packs', by id.
pub fn declared(packs: &[Pack]) -> Vec<Declared> {
    let mut all: Vec<Declared> = pack::built_in()
        .into_iter()
        .map(|action| Declared {
            id: format!("{}.{}", pack::CORE, action.id),
            folder: "/".into(),
            action,
        })
        .collect();
    for each in packs {
        for action in &each.actions {
            all.push(Declared {
                id: format!("{}.{}", each.name, action.id),
                folder: each.folder.clone(),
                action: action.clone(),
            });
        }
    }
    all
}

/// A run as it's kept.
struct KeptRun {
    run: String,
    action: String,
    state: RunState,
    cancellation: Cancellation,
    lines: u64,
}

/// A finished run's file field: its bytes, and the name it downloads as.
pub struct Download {
    pub path: PathBuf,
    pub name: String,
}

/// What's told of each run: it started, logged a line, or finished.
pub type Told = Arc<dyn Fn(RunEvent) + Send + Sync>;

pub struct Runs {
    host: Arc<Host>,
    commands: Arc<Commands>,
    told: Told,
    actions: Vec<Declared>,
    kept: Mutex<Kept>,
    log_reads: Semaphore,
    max_kept: u64,
}

/// The runs kept, in the order they started, and how many are going.
#[derive(Default)]
struct Kept {
    runs: Vec<KeptRun>,
    running: usize,
    /// Set as the agent stops: a run its stop ends keeps the state it had, going.
    closed: bool,
}

impl Runs {
    pub fn new(
        host: Arc<Host>,
        commands: Arc<Commands>,
        told: Told,
        actions: Vec<Declared>,
        max_kept: u64,
    ) -> Runs {
        Runs {
            host,
            commands,
            told,
            actions,
            kept: Mutex::new(Kept::default()),
            log_reads: Semaphore::new(LOGS_AT_ONCE),
            max_kept,
        }
    }

    /// An action the board offers, by its id.
    pub fn action(&self, id: &str) -> Option<&Declared> {
        self.actions.iter().find(|declared| declared.id == id)
    }

    fn folder(&self) -> PathBuf {
        self.host.path(FOLDER)
    }

    /// Takes up the runs a previous agent kept, in the order they started: one that was going
    /// comes back lost, as it ended with that agent.
    pub fn restore(&self) {
        let entries = match fs::read_dir(self.folder()) {
            Ok(entries) => entries,
            Err(e) if e.kind() == io::ErrorKind::NotFound => return,
            Err(e) => {
                self.host
                    .log(&format!("Couldn't read the runs kept in {FOLDER}: {e}"));
                return;
            }
        };
        let mut runs: Vec<String> = entries
            .flatten()
            .map(|entry| entry.file_name().to_string_lossy().into_owned())
            .filter(|name| is_id(name))
            .collect();
        runs.sort();
        for run in runs {
            let path = self.folder().join(&run);
            match self.restore_one(&run, &path) {
                Ok(state) => {
                    let action = state.action.clone();
                    self.kept
                        .lock()
                        .expect("the runs' lock")
                        .runs
                        .push(KeptRun {
                            run,
                            action,
                            state,
                            cancellation: Cancellation::default(),
                            lines: 0,
                        });
                }
                Err(e) => {
                    self.host
                        .log(&format!("Couldn't take up run {run} ({e}): it's dropped"));
                    delete(&self.host, &path);
                }
            }
        }
    }

    fn restore_one(&self, run: &str, path: &Path) -> io::Result<RunState> {
        let bytes = fs::read(path.join("state"))?;
        let mut state = RunState::decode(bytes.as_slice()).map_err(io::Error::other)?;
        if state.running {
            let declared = self.action(&state.action);
            let fields = match declared {
                Some(declared) => declared.action.response.clone(),
                None => vec![
                    Field::of(OUTCOME, FieldType::Text),
                    Field::of(OUTCOME_MESSAGE, FieldType::Text),
                ],
            };
            let kind = declared
                .map(|d| d.action.command.kind())
                .unwrap_or(Kind::Run);
            state = finished(
                &state,
                &Ran::failed(kind, Outcome::Lost, LOST),
                &fields,
                run,
            );
            write(&path.join("state"), &state)?;
            let _ = fs::remove_file(path.join("input"));
        }
        // Its log isn't read: a run taken up never logs again, so its count of lines is never
        // needed.
        Ok(state)
    }

    /// A file to take an action's input into, in [`FOLDER`]: the request's body, read to its end
    /// before the run starts.
    pub fn input_file(&self) -> io::Result<PathBuf> {
        fs::create_dir_all(self.folder())?;
        let file = self
            .folder()
            .join(format!(".input-{}", pack_hash::hex(&random::<8>())));
        fs::File::create(&file)?;
        Ok(file)
    }

    /// Starts an action: `409` if it's running, `503` if as many are running as may.
    pub fn start(self: &Arc<Self>, id: &str, input: Option<&Path>) -> Result<Started, Refused> {
        let Some(declared) = self.action(id).cloned() else {
            return Err(refused(404, format!("this board has no action {id}")));
        };
        let (run, cancellation) = {
            let mut kept = self.kept.lock().expect("the runs' lock");
            if let Some(each) = kept
                .runs
                .iter()
                .find(|each| each.action == id && each.state.running)
            {
                return Err(refused(
                    409,
                    format!("{id} is running already, as run {}", each.run),
                ));
            }
            if kept.running >= AT_ONCE {
                return Err(refused(
                    503,
                    format!("{AT_ONCE} actions are running, as many as may; ask again soon"),
                ));
            }
            // What's kept of a run lasts until its action runs again.
            let dropped: Vec<String> = kept
                .runs
                .iter()
                .filter(|each| each.action == id)
                .map(|each| each.run.clone())
                .collect();
            kept.runs.retain(|each| each.action != id);
            for run in dropped {
                delete(&self.host, &self.folder().join(run));
            }
            let run = run_id(self.host.nanos());
            let folder = self.folder().join(&run);
            let made = (|| -> io::Result<RunState> {
                fs::create_dir_all(&folder)?;
                if let Some(input) = input {
                    fs::rename(input, folder.join("input"))?;
                }
                let state = RunState {
                    run: run.clone(),
                    action: id.to_string(),
                    running: true,
                    result: None,
                };
                write(&folder.join("state"), &state)?;
                Ok(state)
            })();
            let state = made.map_err(|e| refused(500, format!("the run can't be kept: {e}")))?;
            let cancellation = Cancellation::default();
            kept.runs.push(KeptRun {
                run: run.clone(),
                action: id.to_string(),
                state,
                cancellation: cancellation.clone(),
                lines: 0,
            });
            kept.running += 1;
            (run, cancellation)
        };
        self.host.log(&format!("Action {id} started, as run {run}"));
        (self.told)(self.event(&run, id, run_event::Change::Started(true)));
        let given = input.map(|_| self.folder().join(&run).join("input"));
        let runs = self.clone();
        let started = run.clone();
        tokio::spawn(async move { runs.execute(declared, started, given, cancellation).await });
        Ok(Started { run })
    }

    async fn execute(
        self: Arc<Self>,
        declared: Declared,
        run: String,
        input: Option<PathBuf>,
        cancellation: Cancellation,
    ) {
        let folder = self.folder().join(&run);
        let action = &declared.action;
        let state = match fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(folder.join("log"))
        {
            Err(e) => {
                self.host
                    .log(&format!("Run {run} of {} failed: {e}", declared.id));
                Err(e)
            }
            Ok(log) => {
                let options = Options {
                    input: input.clone(),
                    environment: Vec::new(),
                    max_output: MAX_OUTPUT,
                    max_errors: MAX_OUTPUT,
                    errors: Some(self.logged(&run, &declared.id, log)),
                    grace: GRACE,
                };
                let ran = self
                    .commands
                    .run(
                        &declared.folder,
                        &action.command,
                        action.timeout,
                        options,
                        &cancellation,
                    )
                    .await;
                fs::write(folder.join("output"), &ran.output).map(|_| ran)
            }
        };
        let running_state = self.state(&run).unwrap_or_default();
        let state = match state {
            Ok(ran) => finished(&running_state, &ran, &action.response, &run),
            Err(e) => {
                self.host
                    .log(&format!("Run {run} of {} failed: {e}", declared.id));
                finished(
                    &running_state,
                    &Ran::failed(
                        action.command.kind(),
                        Outcome::CouldNotStart,
                        format!("the agent couldn't run it: {e}"),
                    ),
                    &action.response,
                    &run,
                )
            }
        };
        if self.kept.lock().expect("the runs' lock").closed {
            // The agent stopped while it ran, and its process with it: its state is left as it
            // was, going, so the next start takes it up as lost, as after any restart.
            self.host.log(&format!(
                "Run {run} of {} ended as the agent stopped",
                declared.id
            ));
            return;
        }
        if let Err(e) = write(&folder.join("state"), &state) {
            self.host.log(&format!("Couldn't keep run {run}: {e}"));
        }
        if let Some(input) = input {
            let _ = fs::remove_file(input);
        }
        {
            let mut kept = self.kept.lock().expect("the runs' lock");
            if let Some(each) = kept.runs.iter_mut().find(|each| each.run == run) {
                each.state = state.clone();
            }
            kept.running -= 1;
        }
        let result = state.result.clone().unwrap_or_default();
        let outcome = Outcome::try_from(result.outcome).unwrap_or(Outcome::Unspecified);
        let why = if result.outcome_message.is_empty() {
            String::new()
        } else {
            format!(" ({})", result.outcome_message)
        };
        self.host.log(&format!(
            "Action {} finished, run {run}: {}{why}",
            declared.id,
            fill::name(outcome)
        ));
        (self.told)(self.event(&run, &declared.id, run_event::Change::Finished(result)));
        self.cap();
    }

    /// What takes a run's standard error: each line an entry of its log, kept and sent as it's
    /// read.
    fn logged(self: &Arc<Self>, run: &str, action: &str, log: fs::File) -> Lines {
        let runs = self.clone();
        let run = run.to_string();
        let action = action.to_string();
        let mut log = BufWriter::new(log);
        Box::new(move |line: &str| {
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_micros() as i64)
                .unwrap_or(0);
            let Some(mut entry) = logs::lines::entry(line, &Default::default(), now) else {
                return Ok(());
            };
            {
                let mut kept = runs.kept.lock().expect("the runs' lock");
                let Some(each) = kept.runs.iter_mut().find(|each| each.run == run) else {
                    return Err(());
                };
                each.lines += 1;
                entry.cursor = each.lines.to_string();
            }
            log.write_all(&entry.encode_length_delimited_to_vec())
                .map_err(|_| ())?;
            log.flush().map_err(|_| ())?;
            (runs.told)(runs.event(&run, &action, run_event::Change::Log(entry)));
            Ok(())
        })
    }

    fn event(&self, run: &str, action: &str, change: run_event::Change) -> RunEvent {
        RunEvent {
            run: run.to_string(),
            action: action.to_string(),
            time_nanos: self.host.nanos(),
            change: Some(change),
        }
    }

    /// Cancels a run: stopped politely, then forcibly after [`GRACE`]. `404` if it isn't kept,
    /// `409` if it has finished.
    pub fn cancel(&self, run: &str) -> Result<(), Refused> {
        let (action, cancellation) = {
            let kept = self.kept.lock().expect("the runs' lock");
            let Some(each) = kept.runs.iter().find(|each| each.run == run) else {
                return Err(refused(404, format!("no run {run} is kept")));
            };
            if !each.state.running {
                return Err(refused(409, format!("run {run} has finished")));
            }
            (each.action.clone(), each.cancellation.clone())
        };
        self.host.log(&format!("Run {run} of {action} cancelled"));
        cancellation.cancel();
        Ok(())
    }

    /// Every run kept, in the order they started.
    pub fn states(&self) -> Vec<RunState> {
        self.kept
            .lock()
            .expect("the runs' lock")
            .runs
            .iter()
            .map(|each| each.state.clone())
            .collect()
    }

    /// A kept run's state.
    pub fn state(&self, run: &str) -> Option<RunState> {
        self.kept
            .lock()
            .expect("the runs' lock")
            .runs
            .iter()
            .find(|each| each.run == run)
            .map(|each| each.state.clone())
    }

    /// A finished run's file field, by its name: `404` unless it has one.
    pub fn file(&self, run: &str, field: &str) -> Result<Download, Refused> {
        let state = self
            .state(run)
            .ok_or_else(|| refused(404, format!("no run {run} is kept")))?;
        let response = state
            .result
            .map(|result| result.response)
            .unwrap_or_default();
        let has = response.iter().any(|value| {
            value.name == field && matches!(value.value, Some(field_value::Value::FileUrl(_)))
        });
        if !has {
            return Err(refused(404, format!("run {run} has no file {field}")));
        }
        let mut name = field.to_string();
        if let Some(declared) = self.action(&state.action) {
            for each in &declared.action.response {
                if each.name == field && !each.file_name.is_empty() {
                    name = each.file_name.clone();
                }
            }
        }
        Ok(Download {
            path: self.folder().join(run).join("output"),
            name,
        })
    }

    /// A page of a kept run's log: read as a stream, a page's worth held, at most
    /// [`LOGS_AT_ONCE`] at once (`503` past that).
    pub fn log(&self, run: &str, paging: &Paging) -> Result<LogPage, Refused> {
        if self.state(run).is_none() {
            return Err(refused(404, format!("no run {run} is kept")));
        }
        let Ok(_turn) = self.log_reads.try_acquire() else {
            return Err(refused(
                503,
                "as many runs' logs are being read as may; ask again shortly",
            ));
        };
        let mut slice = Slice::new(paging);
        if let Ok(file) = fs::File::open(self.folder().join(run).join("log")) {
            let mut reader = io::BufReader::new(file);
            // The last entry may be cut short as the agent stopped: the rest stand.
            while let Some(entry) = read_delimited(&mut reader) {
                if !slice.take(entry) {
                    break;
                }
            }
        }
        Ok(slice.page())
    }

    /// Drops the oldest finished runs while the kept ones take more than the most kept.
    fn cap(&self) {
        let mut kept = self.kept.lock().expect("the runs' lock");
        let mut total: u64 = kept
            .runs
            .iter()
            .map(|each| size(&self.folder().join(&each.run)))
            .sum();
        let mut index = 0;
        while index < kept.runs.len() && total > self.max_kept {
            if kept.runs[index].state.running {
                index += 1;
                continue;
            }
            let each = kept.runs.remove(index);
            let folder = self.folder().join(&each.run);
            total -= size(&folder);
            delete(&self.host, &folder);
            self.host.log(&format!(
                "Run {} of {} dropped: the runs kept were full",
                each.run, each.action
            ));
        }
    }

    /// The agent is stopping: a run that ends from here on keeps the state it had, going, so the
    /// next start takes it up as lost.
    pub fn close(&self) {
        self.kept.lock().expect("the runs' lock").closed = true;
    }
}

/// One length-delimited entry of a run's log; none at its end, or at an entry cut short.
fn read_delimited(reader: &mut impl io::BufRead) -> Option<LogEntry> {
    let mut length = 0u64;
    let mut shift = 0;
    loop {
        let mut byte = [0u8; 1];
        if io::Read::read(reader, &mut byte).ok()? == 0 {
            return None;
        }
        length |= u64::from(byte[0] & 0x7f) << shift;
        if byte[0] & 0x80 == 0 {
            break;
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
    if length > protocol::MAX_EVENT as u64 {
        return None;
    }
    let mut bytes = vec![0u8; length as usize];
    io::Read::read_exact(reader, &mut bytes).ok()?;
    LogEntry::decode(bytes.as_slice()).ok()
}

/// A run's state once it's finished, with its response filled from its result.
fn finished(state: &RunState, ran: &Ran, fields: &[Field], run: &str) -> RunState {
    let values = fill::fill(fields, ran, MAX_OUTPUT, &files(run));
    let response = values
        .into_iter()
        .zip(fields)
        .map(|(value, field)| FieldValue {
            name: field.name.clone(),
            ..value
        })
        .collect();
    let result = RunResult {
        outcome: ran.outcome as i32,
        outcome_message: ran.message.clone(),
        response,
    };
    RunState {
        running: false,
        result: Some(bounded(result)),
        ..state.clone()
    }
}

/// A result within the protocol's most a run's response may be, as a stream's event carries it:
/// its largest fields made unavailable, saying why, until it fits. Its output stays whole in the
/// run's folder, where a `file` field downloads it.
pub fn bounded(mut result: RunResult) -> RunResult {
    while result.encoded_len() > protocol::MAX_RESPONSE {
        let Some(largest) =
            (0..result.response.len()).max_by_key(|at| result.response[*at].encoded_len())
        else {
            break;
        };
        let size = result.response[largest].encoded_len();
        if size < 1024 {
            break;
        }
        let name = result.response[largest].name.clone();
        result.response[largest] = FieldValue {
            index: 0,
            name,
            value: Some(field_value::Value::Unavailable(format!(
                "too large to send: {} KiB, more than the {} KiB a run's response may be (declare the whole output type: file to download it)",
                size / 1024,
                protocol::MAX_RESPONSE / 1024
            ))),
        };
    }
    result
}

/// Where a run's file fields are fetched.
pub fn files(run: &str) -> String {
    format!("/v2/runs/{run}/files/")
}

/// A run's id: its start on the monotonic clock in microseconds (11 hex digits), so ids sort as
/// runs started, and 5 random ones.
fn run_id(nanos: u64) -> String {
    let micros = (nanos / 1000) & 0xfff_ffff_ffff;
    let some = pack_hash::hex(&random::<3>());
    format!("{micros:011x}{}", &some[..5])
}

fn random<const N: usize>() -> [u8; N] {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).expect("the system's random numbers");
    bytes
}

fn size(folder: &Path) -> u64 {
    fn walk(path: &Path) -> u64 {
        let Ok(entries) = fs::read_dir(path) else {
            return 0;
        };
        entries
            .flatten()
            .map(|entry| match entry.file_type() {
                Ok(kind) if kind.is_dir() => walk(&entry.path()),
                Ok(kind) if kind.is_file() => entry.metadata().map(|m| m.len()).unwrap_or(0),
                _ => 0,
            })
            .sum()
    }
    walk(folder)
}

fn delete(host: &Host, folder: &Path) {
    if let Err(e) = fs::remove_dir_all(folder)
        && e.kind() != io::ErrorKind::NotFound
    {
        host.log(&format!("Couldn't remove {}: {e}", folder.display()));
    }
}

/// Writes a state whole, then renames it into place, so it's never read half written.
fn write(file: &Path, state: &RunState) -> io::Result<()> {
    let part = file.with_file_name(format!(
        "{}.part",
        file.file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    ));
    fs::write(&part, state.encode_to_vec())?;
    fs::rename(part, file)
}

/// Whether an action takes no input: its body must be empty.
pub fn takes_none(action: &Action) -> bool {
    action.input == Input::None
}

#[cfg(test)]
mod tests;
