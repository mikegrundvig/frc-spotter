//! When a stream sends, as a function of what's happened and the time, and nothing else: changed
//! values wait [`BUNDLE_NANOS`] for others to go with them; every value goes again each
//! `complete`; and a heartbeat goes when nothing else was sent for the `heartbeat` interval. Every
//! method takes the time, on the stream's clock, so it's tested without waiting.

/// How long a change waits for others to send with it.
pub const BUNDLE_NANOS: u64 = 20_000_000;

pub struct Pacing {
    heartbeat: u64,
    complete: u64,
    last_sent: u64,
    next_complete: u64,
    changed: bool,
    changed_since: u64,
}

impl Pacing {
    /// A stream that connected `now`, when every value was sent: its heartbeat and how often every
    /// value is sent again, in nanoseconds.
    pub fn new(heartbeat: u64, complete: u64, now: u64) -> Pacing {
        Pacing {
            heartbeat,
            complete,
            last_sent: now,
            next_complete: now + complete,
            changed: false,
            changed_since: 0,
        }
    }

    /// A value changed: it waits for others, from the first change since the last send of them.
    pub fn changed(&mut self, now: u64) {
        if !self.changed {
            self.changed = true;
            self.changed_since = now;
        }
    }

    /// Whether changed values are due: the first changed at least [`BUNDLE_NANOS`] ago.
    pub fn changes_due(&self, now: u64) -> bool {
        self.changed && now.saturating_sub(self.changed_since) >= BUNDLE_NANOS
    }

    /// The changed values were taken to send: none is waiting until the next change.
    pub fn changes_taken(&mut self) {
        self.changed = false;
    }

    /// Whether every value is due again.
    pub fn complete_due(&self, now: u64) -> bool {
        now >= self.next_complete
    }

    /// Every value was sent: the next time is `complete` from now.
    pub fn complete_sent(&mut self, now: u64) {
        self.next_complete = now + self.complete;
    }

    /// Whether a heartbeat is due: nothing was sent for the heartbeat interval.
    pub fn heartbeat_due(&self, now: u64) -> bool {
        now.saturating_sub(self.last_sent) >= self.heartbeat
    }

    /// Something was sent.
    pub fn sent(&mut self, now: u64) {
        self.last_sent = now;
    }

    /// When the next thing is due, if nothing happens before then.
    pub fn wake(&self) -> u64 {
        let wake = (self.last_sent + self.heartbeat).min(self.next_complete);
        if self.changed {
            wake.min(self.changed_since + BUNDLE_NANOS)
        } else {
            wake
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MS: u64 = 1_000_000;

    /// One stream's life, step by step: what's due at each time, and when it next wakes.
    #[test]
    fn what_a_stream_sends_follows_from_the_time_alone() {
        let mut pacing = Pacing::new(250 * MS, 10_000 * MS, 0);
        // Connected: nothing due, the heartbeat first to wake for.
        assert!(!pacing.heartbeat_due(249 * MS));
        assert!(pacing.heartbeat_due(250 * MS));
        assert_eq!(pacing.wake(), 250 * MS);
        // A change at 100 ms waits 20 ms for others; a second within them doesn't move it.
        pacing.changed(100 * MS);
        pacing.changed(110 * MS);
        assert_eq!(pacing.wake(), 120 * MS);
        assert!(!pacing.changes_due(119 * MS));
        assert!(pacing.changes_due(120 * MS));
        pacing.changes_taken();
        pacing.sent(120 * MS);
        assert!(!pacing.changes_due(500 * MS));
        // The heartbeat is counted from what was sent last.
        assert!(!pacing.heartbeat_due(369 * MS));
        assert!(pacing.heartbeat_due(370 * MS));
        assert_eq!(pacing.wake(), 370 * MS);
        // Every value again at 10 s, counted from the last time they all went.
        assert!(!pacing.complete_due(9_999 * MS));
        assert!(pacing.complete_due(10_000 * MS));
        pacing.complete_sent(10_000 * MS);
        assert!(!pacing.complete_due(19_999 * MS));
        assert!(pacing.complete_due(20_000 * MS));
    }

    #[test]
    fn a_long_heartbeat_wakes_for_every_value_first() {
        let pacing = Pacing::new(5_000 * MS, 1_000 * MS, 0);
        assert_eq!(pacing.wake(), 1_000 * MS);
    }
}
