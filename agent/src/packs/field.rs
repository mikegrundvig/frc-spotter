//! A field as a pack declares it: a value a collector fills, or a part of an action's response.
//! The agent passes its limits through and never evaluates them.

use crate::proto::{FieldDeclaration, FieldType, Limit};

#[derive(Clone, Debug, PartialEq)]
pub struct Field {
    /// Its name in its collector or response: a value's id is `pack.collector.name`.
    pub name: String,
    /// What a display calls it; empty when the pack gives none.
    pub label: String,
    pub kind: FieldType,
    /// Its unit, for a number; empty when none.
    pub unit: String,
    /// Its warning limit; empty when none.
    pub warn: Limit,
    /// Its failing limit; empty when none.
    pub fail: Limit,
    /// The name a `file` field downloads as; empty when none.
    pub file_name: String,
}

impl Field {
    /// A field with nothing but its name and type.
    pub fn of(name: &str, kind: FieldType) -> Field {
        Field {
            name: name.to_string(),
            label: String::new(),
            kind,
            unit: String::new(),
            warn: Limit::default(),
            fail: Limit::default(),
            file_name: String::new(),
        }
    }

    /// Its declaration, under an id: a value's `pack.collector.field`, a response field's name.
    pub fn declaration(&self, id: &str) -> FieldDeclaration {
        FieldDeclaration {
            id: id.to_string(),
            label: self.label.clone(),
            r#type: self.kind as i32,
            unit: self.unit.clone(),
            warn: (self.warn != Limit::default()).then(|| self.warn.clone()),
            fail: (self.fail != Limit::default()).then(|| self.fail.clone()),
        }
    }
}
