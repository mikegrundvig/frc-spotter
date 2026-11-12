//! Runs a pack's commands: the only place the agent starts a process, asks a URL, or reads a
//! pack's file. Each is bounded: by its timeout (a process's whole group is stopped, a request is
//! cut off, a read is given up on), and by the most of its output kept. Nothing here comes from a
//! request: every program, URL and path is its pack's, fixed when the agent started. What a request
//! brings (an action's input, a log's paging) is data: on standard input, in a request's body, or
//! in the environment, never on a command line.

mod file;
pub mod fill;
mod http;
mod pipe_holders;
mod process;

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::{Semaphore, watch};
use tokio::task::JoinHandle;

use crate::board::host::Host;
use crate::packs::command::{Command, Kind};
use crate::proto::Outcome;

/// The most threads, or here tasks, reading commands' output at once: two for each command, and
/// at most eight commands run at once (four collectors, two actions, two logs), so twice what they
/// need. With that many busy (held, say, by what commands left running that couldn't be stopped),
/// a command isn't started.
pub const MAX_READERS: usize = 32;

/// How a command went.
pub struct Ran {
    pub kind: Kind,
    /// `Completed` when it ran to its end (a process exited, a response came back, a file was
    /// read); otherwise why not.
    pub outcome: Outcome,
    /// Why it didn't complete, such as "no such file: /opt/team/x.sh"; empty when it did.
    pub message: String,
    /// A process's exit code, or a response's HTTP status, when completed.
    pub code: i32,
    /// Its standard output, its body, or the file's bytes: at most the most kept.
    pub output: Vec<u8>,
    /// Whether it had more output than was kept.
    pub truncated: bool,
    /// A process's first line of standard error, why it failed in its own words.
    pub errors: String,
    /// Done once its process has exited: none when it has, and a task to wait on for a process
    /// stopped that won't go (stuck in the kernel, say), whose command is still running.
    pub ended: Option<JoinHandle<()>>,
}

impl Ran {
    pub fn completed(&self) -> bool {
        self.outcome == Outcome::Completed
    }

    /// Its output as text.
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.output).into_owned()
    }

    pub fn failed(kind: Kind, outcome: Outcome, message: impl Into<String>) -> Ran {
        Ran {
            kind,
            outcome,
            message: message.into(),
            code: 0,
            output: Vec::new(),
            truncated: false,
            errors: String::new(),
            ended: None,
        }
    }
}

/// What takes each line of a command's standard error as it's read: an error stops it being
/// handed any more (its run's log couldn't be written, say), and the command runs on.
pub type Lines = Box<dyn FnMut(&str) -> Result<(), ()> + Send>;

/// What a command run for an action or a log is given, and how it's bounded.
pub struct Options {
    /// Its input: a process's standard input, a request's body; none for none.
    pub input: Option<PathBuf>,
    /// Variables added to the agent's own: a log's paging.
    pub environment: Vec<(String, String)>,
    /// The most of its output kept, in bytes.
    pub max_output: usize,
    /// The most of its standard error handed over, in bytes.
    pub max_errors: usize,
    /// Each line of its standard error, as it's read; none to hand none over.
    pub errors: Option<Lines>,
    /// How long a process stopped politely (SIGTERM) has before it's killed: zero kills it at
    /// once.
    pub grace: Duration,
}

impl Options {
    /// A collector's: no input, its output bounded, its standard error's first line kept.
    pub fn collector(max_output: usize) -> Options {
        Options {
            input: None,
            environment: Vec::new(),
            max_output,
            max_errors: 0,
            errors: None,
            grace: Duration::ZERO,
        }
    }
}

/// Stops a command from elsewhere: a process politely, then forcibly after its grace; a request by
/// dropping it. Asked before the command starts, it stops it as it starts.
#[derive(Clone)]
pub struct Cancellation(Arc<watch::Sender<bool>>);

impl Default for Cancellation {
    fn default() -> Cancellation {
        Cancellation(Arc::new(watch::channel(false).0))
    }
}

impl Cancellation {
    /// Stops the command, once.
    pub fn cancel(&self) {
        self.0.send_replace(true);
    }

    /// Done once the command is to stop.
    pub async fn cancelled(&self) {
        let mut asked = self.0.subscribe();
        let _ = asked.wait_for(|cancelled| *cancelled).await;
    }
}

/// What runs commands: the board, and room for the readers of their output.
pub struct Commands {
    host: Arc<Host>,
    readers: Arc<Semaphore>,
    max_readers: usize,
    /// The process groups of the commands running now, to stop with the agent.
    live: Arc<Mutex<HashSet<u32>>>,
}

impl Commands {
    pub fn new(host: Arc<Host>) -> Commands {
        Commands::with_readers(host, MAX_READERS)
    }

    /// With room for only so many readers: for tests.
    pub fn with_readers(host: Arc<Host>, max_readers: usize) -> Commands {
        Commands {
            host,
            readers: Arc::new(Semaphore::new(max_readers)),
            max_readers,
            live: Arc::default(),
        }
    }

    /// How many readers are reading now: for tests.
    pub fn reading(&self) -> usize {
        self.max_readers - self.readers.available_permits()
    }

    /// Stops every command running, at once: the agent is stopping.
    pub fn stop_all(&self) {
        for group in self.live.lock().expect("the commands' lock").drain() {
            let _ = nix::sys::signal::killpg(
                nix::unistd::Pid::from_raw(group as i32),
                nix::sys::signal::Signal::SIGKILL,
            );
        }
    }

    /// Runs a command once.
    ///
    /// `folder` is its pack's folder on the board: where it runs, and where its `./` programs
    /// are.
    pub async fn run(
        &self,
        folder: &str,
        command: &Command,
        timeout: Duration,
        options: Options,
        cancellation: &Cancellation,
    ) -> Ran {
        match command {
            Command::Run { argv } => {
                process::run(self, folder, argv, timeout, options, cancellation).await
            }
            Command::Http(request) => http::run(request, timeout, options, cancellation).await,
            Command::Read { path } => {
                file::run(&self.host, path, timeout, options.max_output).await
            }
        }
    }
}

#[cfg(test)]
mod tests;
