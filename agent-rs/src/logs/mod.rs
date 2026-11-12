//! Logs: a pack's logs, paged by their commands, and a run's own log, paged by the agent.

pub mod lines;
pub mod paging;

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::Semaphore;

use crate::commands::{Cancellation, Commands, Options};
use crate::packs::command::Command;
use crate::packs::pack::{LogMap, Pack};
use crate::proto::{LogEntry, LogPage, Outcome};
pub use paging::{Paging, Refused};

/// How many logs are paged at once; another asked meanwhile is refused as busy.
const AT_ONCE: usize = 2;

/// How long a log's command may take.
pub const TIMEOUT: Duration = Duration::from_secs(10);

/// The most of a log command's output read: a thousand long entries.
const MAX_OUTPUT: usize = 4 * 1024 * 1024;

/// A log and its pack's folder, where its command runs.
struct Source {
    id: String,
    folder: String,
    run: Command,
    map: LogMap,
}

/// Pages a pack's logs. What's asked for reaches the log's command as data, in its environment,
/// never on its command line; it prints its entries as JSON lines, oldest first, and the agent
/// hands them back, at most the limit, with cursors to page further on either side.
pub struct Logs {
    commands: Arc<Commands>,
    timeout: Duration,
    logs: Vec<Source>,
    turns: Semaphore,
}

impl Logs {
    pub fn new(commands: Arc<Commands>, packs: &[Pack], timeout: Duration) -> Logs {
        let logs = packs
            .iter()
            .flat_map(|pack| {
                pack.logs.iter().map(|log| Source {
                    id: format!("{}.{}", pack.name, log.id),
                    folder: pack.folder.clone(),
                    run: log.run.clone(),
                    map: log.map.clone(),
                })
            })
            .collect();
        Logs {
            commands,
            timeout,
            logs,
            turns: Semaphore::new(AT_ONCE),
        }
    }

    /// A page of a log, by its id (`pack.log`).
    pub async fn page(&self, id: &str, paging: &Paging) -> Result<LogPage, Refused> {
        let Some(source) = self.logs.iter().find(|log| log.id == id) else {
            return Err(Refused::new(404, format!("this board has no log {id}")));
        };
        let Ok(_turn) = self.turns.try_acquire() else {
            return Err(Refused::new(
                503,
                "as many logs are being paged as may; ask again shortly",
            ));
        };
        let options = Options {
            environment: paging.environment(),
            ..Options::collector(MAX_OUTPUT)
        };
        let ran = self
            .commands
            .run(
                &source.folder,
                &source.run,
                self.timeout,
                options,
                &Cancellation::default(),
            )
            .await;
        drop(_turn);
        if !ran.completed() {
            let status = if ran.outcome == Outcome::TimedOut {
                504
            } else {
                502
            };
            return Err(Refused::new(
                status,
                format!("log {id}'s command: {}", ran.message),
            ));
        }
        let mut entries: Vec<LogEntry> = needed(&ran.output, ran.truncated, paging)
            .iter()
            .filter_map(|line| lines::entry(line, &source.map, 0))
            .collect();
        if entries.is_empty() && ran.code != 0 {
            let errors = if ran.errors.is_empty() {
                String::new()
            } else {
                format!(": {}", ran.errors)
            };
            return Err(Refused::new(
                502,
                format!("log {id}'s command failed: exit {}{errors}", ran.code),
            ));
        }
        if entries.len() > paging.limit {
            entries = if paging.from == paging::From::After {
                entries[..paging.limit].to_vec()
            } else {
                entries[entries.len() - paging.limit..].to_vec()
            };
        }
        Ok(page(entries, paging))
    }
}

/// The lines of a log command's output a page needs, before any is read as an entry: the last
/// `limit` that aren't blank, or for `after` the first; a last line cut short by the most kept is
/// left out. So a command that prints far more than asked for costs its output's bytes, no more.
pub fn needed(output: &[u8], truncated: bool, paging: &Paging) -> Vec<String> {
    let after = paging.from == paging::From::After;
    let mut kept: VecDeque<(usize, usize)> = VecDeque::new();
    let mut start = 0;
    for i in 0..=output.len() {
        let last = i == output.len();
        if !last && output[i] != b'\n' {
            continue;
        }
        if last && truncated {
            break;
        }
        if !blank(&output[start..i]) {
            if after && kept.len() == paging.limit {
                break;
            }
            kept.push_back((start, i));
            if kept.len() > paging.limit {
                kept.pop_front();
            }
        }
        start = i + 1;
    }
    kept.into_iter()
        .map(|(from, to)| String::from_utf8_lossy(&output[from..to]).into_owned())
        .collect()
}

fn blank(bytes: &[u8]) -> bool {
    bytes.iter().all(|b| matches!(b, b' ' | b'\t' | b'\r'))
}

/// A page of entries as asked: with the cursor to page back from (empty at the start: fewer than
/// asked for, looking back), and the cursor to page on from.
pub fn page(entries: Vec<LogEntry>, paging: &Paging) -> LogPage {
    let back = paging.from != paging::From::After;
    if entries.is_empty() {
        return LogPage {
            entries,
            before: if back {
                String::new()
            } else {
                paging.cursor.clone()
            },
            after: paging.cursor.clone(),
        };
    }
    let start = back && entries.len() < paging.limit;
    let before = if start {
        String::new()
    } else {
        entries[0].cursor.clone()
    };
    let after = entries[entries.len() - 1].cursor.clone();
    LogPage {
        entries,
        before,
        after,
    }
}

/// A page of a run's log, its entries taken one at a time as they're read, at most the limit held:
/// the newest wanted, or for `after` the first, so a long log costs no more than a page. A run's
/// entries' cursors are their places, from 1, and the agent filters them by level itself.
pub struct Slice<'a> {
    paging: &'a Paging,
    cursor: u64,
    wanted: VecDeque<LogEntry>,
}

impl<'a> Slice<'a> {
    pub fn new(paging: &'a Paging) -> Slice<'a> {
        let cursor = if paging.cursor.is_empty() {
            0
        } else {
            paging
                .cursor
                .parse()
                .unwrap_or(if paging.from == paging::From::Before {
                    0
                } else {
                    u64::MAX
                })
        };
        Slice {
            paging,
            cursor,
            wanted: VecDeque::new(),
        }
    }

    /// Takes an entry, in the log's order: whether any more are wanted.
    pub fn take(&mut self, entry: LogEntry) -> bool {
        let Ok(place) = entry.cursor.parse::<u64>() else {
            return true;
        };
        let before = self.paging.from == paging::From::Before;
        let after = self.paging.from == paging::From::After;
        if before && place >= self.cursor {
            return false;
        }
        if (after && place <= self.cursor) || !lines::important(entry.level, self.paging.level) {
            return true;
        }
        self.wanted.push_back(entry);
        if self.wanted.len() > self.paging.limit {
            self.wanted.pop_front();
        }
        !(after && self.wanted.len() == self.paging.limit)
    }

    /// The page of what was taken.
    pub fn page(self) -> LogPage {
        page(self.wanted.into_iter().collect(), self.paging)
    }
}

#[cfg(test)]
mod tests;
