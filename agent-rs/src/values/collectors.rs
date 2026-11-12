//! Runs every pack's collectors, each on its own schedule, and keeps the values they fill.
//! Bounded so collectors never crowd out the software they watch: at most [`RUNNING`] run at once
//! (the rest wait their turn, in order), each within its timeout, and a collector still running
//! when it's due again skips that turn rather than queueing behind itself. A collector whose
//! command is stopped at its timeout and won't go frees its turn, and its values are unavailable
//! ("timed out after 5s; its command is still running"); it isn't run again until its command has
//! ended. Idle, the agent wakes only for each collector's turn.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::Semaphore;
use tokio::task::JoinHandle;
use tokio::time::{Instant, MissedTickBehavior};

use super::store::Store;
use crate::board::host::Host;
use crate::commands::{Runner, fill};
use crate::packs::field::Field;
use crate::packs::pack::{Collector, Pack};

/// How many collectors run at once.
pub const RUNNING: usize = 4;

/// The most of a collector's output kept: a value or a small JSON object.
pub const MAX_OUTPUT: usize = 64 * 1024;

/// A collector and where its values are.
#[derive(Clone)]
pub struct Slot {
    pub pack: String,
    pub folder: String,
    pub collector: Collector,
    /// The index of its first field's value: its fields' values follow in its order.
    pub first: usize,
}

impl Slot {
    /// Its name in the agent's log: `pack.collector`.
    pub fn name(&self) -> String {
        format!("{}.{}", self.pack, self.collector.id)
    }

    /// A value's id: `pack.collector.field`.
    pub fn id(&self, field: &Field) -> String {
        format!("{}.{}", self.name(), field.name)
    }
}

/// Every collector of every pack, in order, with the index of its first value: packs in their
/// order, each pack's collectors in its order, each collector's fields in its order. The
/// description lists the values in the same order, so a value's index is its place there.
pub fn slots(packs: &[Pack]) -> Vec<Slot> {
    let mut slots = Vec::new();
    let mut index = 0;
    for pack in packs {
        for collector in &pack.collectors {
            slots.push(Slot {
                pack: pack.name.clone(),
                folder: pack.folder.clone(),
                collector: collector.clone(),
                first: index,
            });
            index += collector.fields.len();
        }
    }
    slots
}

/// A slot's state as it runs.
#[derive(Default)]
struct State {
    busy: AtomicBool,
    failure: Mutex<String>,
}

pub struct Collectors {
    host: Arc<Host>,
    runner: Arc<dyn Runner>,
    store: Arc<Store>,
    slots: Vec<Slot>,
    states: Vec<State>,
    running: Arc<Semaphore>,
}

impl Collectors {
    pub fn new(
        host: Arc<Host>,
        packs: &[Pack],
        runner: Arc<dyn Runner>,
        store: Arc<Store>,
    ) -> Collectors {
        let slots = slots(packs);
        let states = slots.iter().map(|_| State::default()).collect();
        Collectors {
            host,
            runner,
            store,
            slots,
            states,
            running: Arc::new(Semaphore::new(RUNNING)),
        }
    }

    /// Starts each collector on its schedule, their first runs spread over the first second: a
    /// task each, which sleeps until its next turn.
    pub fn start(self: &Arc<Self>) -> Vec<JoinHandle<()>> {
        let count = self.slots.len().max(1) as u64;
        let now = Instant::now();
        (0..self.slots.len())
            .map(|at| {
                let collectors = self.clone();
                let first = now + Duration::from_millis(1000 * at as u64 / count);
                let every = self.slots[at].collector.every;
                tokio::spawn(async move {
                    let mut turns = tokio::time::interval_at(first, every);
                    turns.set_missed_tick_behavior(MissedTickBehavior::Skip);
                    loop {
                        turns.tick().await;
                        collectors.due(at);
                    }
                })
            })
            .collect()
    }

    /// A collector's turn: run it, unless it's still running from its last.
    fn due(self: &Arc<Self>, at: usize) {
        if self.states[at].busy.swap(true, Ordering::SeqCst) {
            return;
        }
        let collectors = self.clone();
        tokio::spawn(async move {
            let ended = {
                let _turn = collectors.running.acquire().await;
                collectors.run(at).await
            };
            // Busy until its command has ended, which may outlast its run.
            if let Some(ended) = ended {
                let _ = ended.await;
            }
            collectors.states[at].busy.store(false, Ordering::SeqCst);
        });
    }

    /// Runs every collector once, in order: as tests read a board.
    pub async fn run_all(&self) {
        for at in 0..self.slots.len() {
            self.run(at).await;
        }
    }

    /// Runs one collector once, and keeps what it filled: done once its command has ended, but
    /// for a command stopped that won't go, whose end is handed back.
    async fn run(&self, at: usize) -> Option<JoinHandle<()>> {
        let slot = &self.slots[at];
        let collector = &slot.collector;
        let ran = self
            .runner
            .collect(
                &slot.folder,
                &collector.command,
                collector.timeout,
                MAX_OUTPUT,
            )
            .await;
        let values = fill::fill(&collector.fields, &ran, MAX_OUTPUT, "");
        let failure = fill::reason(&ran, MAX_OUTPUT);
        self.store.set(slot.first, values);
        self.note(at, failure);
        ran.ended
    }

    /// Logs a collector whose command stops completing, once, and when it completes again.
    fn note(&self, at: usize, failure: String) {
        let was = std::mem::replace(
            &mut *self.states[at]
                .failure
                .lock()
                .expect("the collector's lock"),
            failure.clone(),
        );
        if failure != was {
            let name = self.slots[at].name();
            self.host.log(&if failure.is_empty() {
                format!("Collector {name} fills its fields again")
            } else {
                format!("Collector {name} fills nothing from its output: {failure}")
            });
        }
    }
}

#[cfg(test)]
mod tests;
