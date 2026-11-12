//! Every value's latest, by its index (its place in the description), and when each last
//! changed: a running count of changes, so a reader asks for what changed since the count it last
//! saw. Until a collector first runs, its values are unavailable, never made up.

use std::sync::Mutex;

use crate::commands::fill;
use crate::proto::{FieldValue, Values};

/// Why a value has none yet.
pub const NOT_YET: &str = "not collected yet";

/// What's told, outside the store's lock, whenever a set changed a value.
pub type OnChange = Box<dyn Fn() + Send + Sync>;

pub struct Store {
    state: Mutex<State>,
    on_change: OnChange,
}

struct State {
    values: Vec<FieldValue>,
    changed: Vec<u64>,
    changes: u64,
}

/// What changed after a count, and the count it brings the reader to, read together.
pub struct Delta {
    pub values: Values,
    pub changes: u64,
}

impl Store {
    pub fn new(size: usize, on_change: OnChange) -> Store {
        let values = (0..size)
            .map(|index| FieldValue {
                index: index as u32,
                ..fill::unavailable(NOT_YET)
            })
            .collect();
        Store {
            state: Mutex::new(State {
                values,
                changed: vec![0; size],
                changes: 0,
            }),
            on_change,
        }
    }

    /// How many values there are.
    pub fn size(&self) -> usize {
        self.state.lock().expect("the store's lock").values.len()
    }

    /// Sets values from `first` on, in order, counting those that changed.
    pub fn set(&self, first: usize, filled: Vec<FieldValue>) {
        let mut any = false;
        {
            let mut state = self.state.lock().expect("the store's lock");
            for (offset, value) in filled.into_iter().enumerate() {
                let index = first + offset;
                if index >= state.values.len() {
                    break;
                }
                let value = FieldValue {
                    index: index as u32,
                    ..value
                };
                if value != state.values[index] {
                    state.values[index] = value;
                    state.changes += 1;
                    state.changed[index] = state.changes;
                    any = true;
                }
            }
        }
        if any {
            (self.on_change)();
        }
    }

    /// One value as it is now.
    pub fn get(&self, index: usize) -> FieldValue {
        self.state.lock().expect("the store's lock").values[index].clone()
    }

    /// What changed after the count `after` (every value when none), and the count now.
    pub fn delta(&self, after: Option<u64>, revision: u32, nanos: u64) -> Delta {
        let state = self.state.lock().expect("the store's lock");
        let values = state
            .values
            .iter()
            .zip(&state.changed)
            .filter(|(_, changed)| after.is_none_or(|after| **changed > after))
            .map(|(value, _)| value.clone())
            .collect();
        Delta {
            values: Values {
                revision,
                time_nanos: nanos,
                complete: after.is_none(),
                values,
            },
            changes: state.changes,
        }
    }
}
