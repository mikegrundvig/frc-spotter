//! What happens on the board as it happens, for each stream: a value changed, or a run started,
//! logged a line, or finished. Each stream is told at once and does its own sending, so a slow
//! stream never holds up a collector or a run.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use tokio::sync::Notify;

use super::pacing::Pacing;
use crate::board::host::monotonic_nanos;
use crate::proto::RunEvent;

/// The most run events waiting to be sent: a stream that falls this far behind is closed.
pub const MAX_QUEUED: usize = 10_000;

/// One stream's side: when it sends, the run events waiting for it, and its wake-up.
pub struct Inbox {
    pub state: Mutex<InboxState>,
    pub wake: Notify,
}

pub struct InboxState {
    pub pacing: Pacing,
    pub queued: VecDeque<RunEvent>,
    /// It fell [`MAX_QUEUED`] run events behind.
    pub behind: bool,
}

impl Inbox {
    pub fn new(pacing: Pacing) -> Arc<Inbox> {
        Arc::new(Inbox {
            state: Mutex::new(InboxState {
                pacing,
                queued: VecDeque::new(),
                behind: false,
            }),
            wake: Notify::new(),
        })
    }
}

#[derive(Default)]
pub struct Events {
    streams: Mutex<Vec<Arc<Inbox>>>,
}

impl Events {
    pub fn subscribe(&self, inbox: Arc<Inbox>) {
        self.streams.lock().expect("the events' lock").push(inbox);
    }

    pub fn unsubscribe(&self, inbox: &Arc<Inbox>) {
        self.streams
            .lock()
            .expect("the events' lock")
            .retain(|each| !Arc::ptr_eq(each, inbox));
    }

    /// Some value changed: each stream asks the store what, when it next sends.
    pub fn values_changed(&self) {
        let now = monotonic_nanos();
        for inbox in self.streams.lock().expect("the events' lock").iter() {
            inbox
                .state
                .lock()
                .expect("the inbox's lock")
                .pacing
                .changed(now);
            inbox.wake.notify_one();
        }
    }

    /// A run started, logged a line, or finished.
    pub fn run(&self, event: RunEvent) {
        for inbox in self.streams.lock().expect("the events' lock").iter() {
            {
                let mut state = inbox.state.lock().expect("the inbox's lock");
                if state.queued.len() >= MAX_QUEUED {
                    state.behind = true;
                } else {
                    state.queued.push_back(event.clone());
                }
            }
            inbox.wake.notify_one();
        }
    }
}
