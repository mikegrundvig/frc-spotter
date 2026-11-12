//! A pack: a folder, `<name>/pack.yaml` beside whatever its commands run, that declares
//! collectors, logs and actions. Spotter never installs one: it's in place or it isn't.

use std::time::Duration;

use prost::Message;

use super::command::{Command, Kind, OUTCOME, OUTCOME_MESSAGE};
use super::field::Field;
use crate::proto::{self, ActionDeclaration, Description, FieldType, Input, LogDeclaration};

/// The file in a pack's folder that declares it.
pub const FILE: &str = "pack.yaml";

/// The name the built-in actions go by, which no pack may take.
pub const CORE: &str = "core";

#[derive(Clone, Debug, PartialEq)]
pub struct Pack {
    /// Its name, its folder's.
    pub name: String,
    /// Its version, as it writes it; empty when it gives none.
    pub version: String,
    /// Its folder on the board, absolute.
    pub folder: String,
    /// Whether the robot pushed it, rather than the board's installer.
    pub pushed: bool,
    pub collectors: Vec<Collector>,
    pub logs: Vec<Log>,
    pub actions: Vec<Action>,
}

/// A collector: a command run on its own schedule, whose output fills its fields.
#[derive(Clone, Debug, PartialEq)]
pub struct Collector {
    pub id: String,
    pub command: Command,
    pub every: Duration,
    /// How long a run may take before its fields are unavailable.
    pub timeout: Duration,
    pub fields: Vec<Field>,
}

impl Collector {
    /// A collector's timeout unless its pack says.
    pub const TIMEOUT: Duration = Duration::from_secs(5);
}

/// A log: a command that prints a page of entries as JSON lines, asked what for by the
/// environment.
#[derive(Clone, Debug, PartialEq)]
pub struct Log {
    pub id: String,
    pub label: String,
    /// What it runs: always a `run` command.
    pub run: Command,
    pub map: LogMap,
}

/// Which JSON key of a log's line holds each part of an entry.
#[derive(Clone, Debug, PartialEq)]
pub struct LogMap {
    pub time: String,
    /// The unit of a numeric time: `ns`, `us`, `ms` or `s`; empty when the pack gives none.
    pub time_unit: String,
    pub level: String,
    pub source: String,
    pub message: String,
    pub cursor: String,
}

impl Default for LogMap {
    /// An entry's parts under their own names.
    fn default() -> LogMap {
        LogMap {
            time: "time".into(),
            time_unit: String::new(),
            level: "level".into(),
            source: "source".into(),
            message: "message".into(),
            cursor: "cursor".into(),
        }
    }
}

/// An action: a command run on request, optionally with input, whose output fills a response.
#[derive(Clone, Debug, PartialEq)]
pub struct Action {
    pub id: String,
    pub label: String,
    pub description: String,
    pub command: Command,
    pub input: Input,
    /// The question a display asks first; empty for none.
    pub confirm: String,
    pub timeout: Duration,
    /// Whether it may run while the robot is enabled: the manager's to hold it to.
    pub while_enabled: bool,
    /// The fields of its response: the built-in ones for its kind, then the pack's.
    pub response: Vec<Field>,
}

impl Action {
    /// An action's timeout unless its pack says.
    pub const TIMEOUT: Duration = Duration::from_secs(60);

    /// The longest an action's timeout may be.
    pub const MAX_TIMEOUT: Duration = Duration::from_secs(3600);

    /// As a description declares it, under its pack's name: `pack.action`.
    pub fn declaration(&self, pack: &str) -> ActionDeclaration {
        let seconds = (self.timeout.as_millis() as f64 / 1000.0).round().max(1.0);
        ActionDeclaration {
            id: format!("{pack}.{}", self.id),
            label: self.label.clone(),
            description: self.description.clone(),
            input: self.input as i32,
            confirm: self.confirm.clone(),
            timeout_seconds: seconds as u32,
            while_enabled: self.while_enabled,
            response: self
                .response
                .iter()
                .map(|field| field.declaration(&field.name))
                .collect(),
        }
    }
}

/// The fields every response of a kind has, as declared unless its pack says more: `outcome` and
/// `outcomeMessage`, then its kind's code, a number, and its whole output, text.
pub fn built_in_response(kind: Kind) -> Vec<Field> {
    let mut fields = vec![
        Field::of(OUTCOME, FieldType::Text),
        Field::of(OUTCOME_MESSAGE, FieldType::Text),
    ];
    if !kind.code().is_empty() {
        fields.push(Field::of(kind.code(), FieldType::Number));
    }
    if !kind.whole().is_empty() {
        fields.push(Field::of(kind.whole(), FieldType::Text));
    }
    fields
}

/// The two built-in actions, `core.power-off` and `core.reboot`: robot code can power off or
/// restart any board, whatever packs it has. Each runs systemctl, which a polkit rule of the
/// package's lets the agent's user do.
pub fn built_in() -> Vec<Action> {
    let action = |id: &str, label: &str, description: &str, confirm: &str, verb: &str| Action {
        id: id.into(),
        label: label.into(),
        description: description.into(),
        command: Command::Run {
            argv: vec!["systemctl".into(), verb.into()],
        },
        input: Input::None,
        confirm: confirm.into(),
        timeout: Action::TIMEOUT,
        while_enabled: false,
        response: built_in_response(Kind::Run),
    };
    vec![
        action(
            "power-off",
            "Power off",
            "Stops every service in order, then powers the board off",
            "Power this computer off?",
            "poweroff",
        ),
        action(
            "reboot",
            "Reboot",
            "Restarts the board",
            "Restart this computer?",
            "reboot",
        ),
    ]
}

impl Pack {
    /// The pack as the description lists it.
    pub fn description(&self) -> proto::Pack {
        proto::Pack {
            name: self.name.clone(),
            version: self.version.clone(),
            folder: self.folder.clone(),
            pushed: self.pushed,
        }
    }

    /// How many values its collectors fill.
    pub fn values(&self) -> usize {
        self.collectors
            .iter()
            .map(|collector| collector.fields.len())
            .sum()
    }

    /// How many bytes its declarations take in a description: its entry, values, logs, actions.
    pub fn declared_size(&self) -> usize {
        let mut part = Description {
            packs: vec![self.description()],
            ..Default::default()
        };
        for collector in &self.collectors {
            for field in &collector.fields {
                part.values.push(
                    field.declaration(&format!("{}.{}.{}", self.name, collector.id, field.name)),
                );
            }
        }
        for log in &self.logs {
            part.logs.push(LogDeclaration {
                id: format!("{}.{}", self.name, log.id),
                label: log.label.clone(),
            });
        }
        for action in &self.actions {
            part.actions.push(action.declaration(&self.name));
        }
        part.encoded_len()
    }
}

/// How many values these packs' collectors fill.
pub fn values(packs: &[Pack]) -> usize {
    packs.iter().map(Pack::values).sum()
}
