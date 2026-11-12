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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::field_value::Value;

    fn of(value: Value) -> FieldValue {
        fill::value(value)
    }

    #[test]
    fn the_store_counts_changes_and_answers_what_changed_since() {
        let store = Store::new(3, Box::new(|| {}));
        store.set(0, vec![of(Value::Number(1.0)), of(Value::Text("a".into()))]);
        let seen = store.delta(None, 0, 0).changes;
        assert_eq!(seen, 2);
        // The same value again is no change.
        store.set(1, vec![of(Value::Text("a".into()))]);
        assert_eq!(store.delta(None, 0, 0).changes, seen);
        store.set(1, vec![of(Value::Text("b".into()))]);
        let delta = store.delta(Some(seen), 7, 99).values;
        assert!(!delta.complete);
        assert_eq!(delta.revision, 7);
        assert_eq!(delta.time_nanos, 99);
        assert_eq!(delta.values.len(), 1);
        assert_eq!(delta.values[0].index, 1);
        let all = store.delta(None, 7, 100).values;
        assert!(all.complete);
        assert_eq!(all.values.len(), 3);
        assert_eq!(store.size(), 3);
    }
}
