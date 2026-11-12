//! Reads a pack's `pack.yaml` into a [`Pack`], every problem with its file and line. Everything is
//! checked as it's read, so a mistake shows at its line, and a key nobody reads is a problem, so a
//! misspelling can't pass silently.
//!
//! Bounded, as a pushed pack is anyone's who can reach the controller's address: its file at most
//! 256 KiB (as it's read), nested at most 32 deep, at most [`MAX_COLLECTORS`] collectors,
//! [`MAX_LOGS`] logs and [`MAX_ACTIONS`] actions, [`MAX_FIELDS`] fields to a collector or a
//! response, and [`MAX_TEXT`] characters to any text a description carries.

use std::time::Duration;

use super::command::{Command, Http, Kind, Method, OUTCOME, OUTCOME_MESSAGE, Part};
use super::duration;
use super::field::Field;
use super::pack::{self, Action, Collector, Log, LogMap, Pack};
use super::yaml::{self, Kind as NodeKind, Node, Resolved};
use crate::proto::{FieldType, Input, Limit, Scalar, scalar};

/// The most collectors a pack may have.
pub const MAX_COLLECTORS: usize = 64;
/// The most logs a pack may have.
pub const MAX_LOGS: usize = 16;
/// The most actions a pack may have.
pub const MAX_ACTIONS: usize = 32;
/// The most fields a collector may fill, or an action's response may declare.
pub const MAX_FIELDS: usize = 64;
/// The most characters of any text a board's description carries.
pub const MAX_TEXT: usize = 1024;

/// The shortest a collector's interval may be, and the longest.
const MIN_EVERY: Duration = Duration::from_millis(100);
const MAX_EVERY: Duration = Duration::from_secs(24 * 3600);
/// The longest a collector's timeout may be.
const MAX_COLLECTOR_TIMEOUT: Duration = Duration::from_secs(3600);

const PACK_KEYS: &[&str] = &["actions", "collectors", "logs", "pack", "version"];
const COLLECTOR_KEYS: &[&str] = &["every", "fields", "file", "http", "id", "run", "timeout"];
const LOG_KEYS: &[&str] = &["http", "id", "label", "map", "run"];
const MAP_KEYS: &[&str] = &["cursor", "level", "message", "source", "time"];
const ACTION_KEYS: &[&str] = &[
    "confirm",
    "description",
    "http",
    "id",
    "input",
    "label",
    "response",
    "run",
    "timeout",
    "whileEnabled",
];
const FIELD_KEYS: &[&str] = &["fail", "label", "name", "type", "unit", "warn"];
const LIMIT_KEYS: &[&str] = &["above", "below", "equals", "missing", "notEquals"];

/// The types a value may have: `json` and `file` occur only in responses.
const VALUE_TYPES: &[&str] = &["boolean", "number", "status", "text"];

/// A field's type by the name a pack writes.
fn field_type(name: &str) -> Option<FieldType> {
    match name {
        "number" => Some(FieldType::Number),
        "text" => Some(FieldType::Text),
        "boolean" => Some(FieldType::Boolean),
        "status" => Some(FieldType::Status),
        "json" => Some(FieldType::Json),
        "file" => Some(FieldType::File),
        _ => None,
    }
}

/// Whether text is a number, as a limit, a comparison or a command's output writes one:
/// `[-+]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][-+]?[0-9]+)?`.
pub fn is_number(text: &str) -> bool {
    let body = text.strip_prefix(['-', '+']).unwrap_or(text);
    let (mantissa, exponent) = match body.find(['e', 'E']) {
        Some(at) => (&body[..at], Some(&body[at + 1..])),
        None => (body, None),
    };
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    let mantissa_ok = match mantissa.split_once('.') {
        None => digits(mantissa),
        Some((whole, fraction)) => {
            (digits(whole) && (fraction.is_empty() || digits(fraction)))
                || (whole.is_empty() && digits(fraction))
        }
    };
    let exponent_ok = exponent.is_none_or(|e| digits(e.strip_prefix(['-', '+']).unwrap_or(e)));
    mantissa_ok && exponent_ok
}

/// What a pack's name may be: its folder's, `[a-z][a-z0-9-]{0,63}`.
pub fn is_pack_name(name: &str) -> bool {
    let mut chars = name.chars();
    name.len() <= 64
        && chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

/// What a collector's, field's, log's or action's name may be: `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`.
pub fn is_id(name: &str) -> bool {
    let mut chars = name.chars();
    name.len() <= 128
        && chars.next().is_some_and(|c| c.is_ascii_alphanumeric())
        && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
}

/// A form's field or file name: `[A-Za-z0-9._-]+`.
fn is_form_name(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
}

/// What a check off any board looks for, beyond what the agent refuses (the command line's
/// check): mistakes the agent takes but a pack shouldn't have, and notes. None on a board.
pub trait Checks {
    /// Why a program a pack names as its own (`./name`, relative to its folder) won't run once
    /// pushed; none when it will.
    fn program(&self, relative: &str) -> Option<String>;

    /// A note at its line: worth knowing, never a problem.
    fn note(&mut self, note: String);
}

/// Reads and checks a pack: its problems, each with its file and line, when it can't be read.
///
/// `folder` is its folder on the board, absolute: its last part is its name.
pub fn read(yaml: &str, folder: &str, pushed: bool) -> Result<Pack, Vec<String>> {
    read_checked(yaml, folder, pushed, None)
}

/// Reads and checks a pack, and, given checks, looks for what a check off any board looks for
/// besides.
pub fn read_checked(
    yaml: &str,
    folder: &str,
    pushed: bool,
    checks: Option<&mut dyn Checks>,
) -> Result<Pack, Vec<String>> {
    let source = format!("{folder}/{}", pack::FILE);
    let root = match yaml::load(yaml) {
        Ok(root) => root,
        Err(refused) => {
            let at = if refused.line > 0 {
                format!(":{}", refused.line)
            } else {
                String::new()
            };
            return Err(vec![format!(
                "{source}{at}: {}",
                one_line(&refused.message)
            )]);
        }
    };
    let Some(
        root @ Node {
            kind: NodeKind::Mapping(_),
            ..
        },
    ) = root
    else {
        return Err(vec![format!(
            "{source}: expected pack:, and its collectors:"
        )]);
    };
    let mut reader = Reader {
        source,
        problems: Vec::new(),
        checks,
    };
    let pack = reader.pack(&root, folder, pushed);
    if reader.problems.is_empty() {
        Ok(pack)
    } else {
        Err(reader.problems)
    }
}

/// A mapping's entries by key, as the reader looks them up.
type Keys<'a> = Vec<(&'a str, &'a Node, &'a Node)>;

fn get<'a>(keys: &Keys<'a>, name: &str) -> Option<(&'a Node, &'a Node)> {
    keys.iter()
        .find(|(key, _, _)| *key == name)
        .map(|(_, key, value)| (*key, *value))
}

fn value<'a>(keys: &Keys<'a>, name: &str) -> Option<&'a Node> {
    get(keys, name).map(|(_, value)| value)
}

struct Reader<'c> {
    source: String,
    problems: Vec<String>,
    checks: Option<&'c mut dyn Checks>,
}

impl Reader<'_> {
    fn checking(&self) -> bool {
        self.checks.is_some()
    }

    fn pack(&mut self, node: &Node, folder: &str, pushed: bool) -> Pack {
        let keys = self.mapping(node, PACK_KEYS, "a pack");
        let folder_name = folder.rsplit('/').next().unwrap_or(folder).to_string();
        let mut name = folder_name.clone();
        match get(&keys, "pack") {
            None => self.problem(node, "pack: (its name) is missing"),
            Some((key, named)) => {
                let given = self.text(named, "pack").unwrap_or_default();
                if !is_pack_name(&given) {
                    self.problem(
                        key,
                        format!(
                            "pack {given} isn't a pack's name: lowercase letters, digits, and -"
                        ),
                    );
                } else if given == pack::CORE {
                    self.problem(
                        key,
                        "pack core is the agent's own: its built-in actions are core's",
                    );
                } else if given != folder_name {
                    self.problem(
                        key,
                        format!(
                            "pack {given} is in a folder named {folder_name}: a pack's folder is its name"
                        ),
                    );
                }
                name = given;
            }
        }
        let version = value(&keys, "version")
            .and_then(|n| self.described(n, "version"))
            .unwrap_or_default();
        self.most(get(&keys, "collectors"), "collectors", MAX_COLLECTORS);
        self.most(get(&keys, "logs"), "logs", MAX_LOGS);
        self.most(get(&keys, "actions"), "actions", MAX_ACTIONS);
        // Names are unique where they're declared: collectors', logs' and actions' in the pack,
        // fields' in their collector or response (a mapping's keys, which loading checks).
        let mut collectors: Vec<Collector> = Vec::new();
        for item in self.sequence(get(&keys, "collectors"), "collectors") {
            if let Some(collector) = self.collector(item) {
                if collectors.iter().any(|c| c.id == collector.id) {
                    self.problem(
                        item,
                        format!("collector {} is declared twice", collector.id),
                    );
                }
                collectors.push(collector);
            }
        }
        let mut logs: Vec<Log> = Vec::new();
        for item in self.sequence(get(&keys, "logs"), "logs") {
            if let Some(log) = self.log(item) {
                if logs.iter().any(|l| l.id == log.id) {
                    self.problem(item, format!("log {} is declared twice", log.id));
                }
                logs.push(log);
            }
        }
        let mut actions: Vec<Action> = Vec::new();
        for item in self.sequence(get(&keys, "actions"), "actions") {
            if let Some(action) = self.action(item) {
                if actions.iter().any(|a| a.id == action.id) {
                    self.problem(item, format!("action {} is declared twice", action.id));
                }
                actions.push(action);
            }
        }
        Pack {
            name,
            version,
            folder: folder.to_string(),
            pushed,
            collectors,
            logs,
            actions,
        }
    }

    // ---- collectors ----

    fn collector(&mut self, node: &Node) -> Option<Collector> {
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(node, "each collector is a mapping: - id: ...");
            return None;
        }
        let before = self.problems.len();
        let keys = self.mapping(node, COLLECTOR_KEYS, "a collector");
        let id = self.id(&keys, node, "collector");
        let command = self.command(&keys, node, true);
        let mut every = Duration::ZERO;
        match get(&keys, "every") {
            None => self.problem(
                node,
                format!("collector {id} needs every: how often it runs, such as 2s"),
            ),
            Some(entry) => every = self.duration(entry, MIN_EVERY, MAX_EVERY),
        }
        let timeout = match get(&keys, "timeout") {
            Some(entry) => self.duration(entry, Duration::from_millis(1), MAX_COLLECTOR_TIMEOUT),
            None => Collector::TIMEOUT,
        };
        let mut fields = Vec::new();
        match get(&keys, "fields") {
            Some((
                key,
                declared @ Node {
                    kind: NodeKind::Mapping(_),
                    ..
                },
            )) => {
                self.fields_bounded(key, declared, &format!("collector {id}"));
                for (entry_key, entry_value) in self.entries(declared) {
                    let name = entry_key.scalar().unwrap_or("").to_string();
                    let part = command.as_ref().and_then(|c| c.kind().part(&name));
                    if let Some(part) = part {
                        let what = match part {
                            Part::Code => "exit code or HTTP status",
                            Part::Whole => "whole output",
                            Part::Outcome | Part::OutcomeMessage => "outcome",
                        };
                        let at = self.at(entry_key);
                        if let Some(checks) = self.checks.as_deref_mut() {
                            checks.note(format!(
                                "{at}collector {id}'s field {name} is its command's own {what}, not a key of its output"
                            ));
                        }
                    }
                    // A named part is the command's, as an action's: a value's types are text and
                    // number.
                    let field = match part {
                        None => self.field(entry_key, entry_value, VALUE_TYPES, "a value's type"),
                        Some(part) => {
                            let only: &[&str] = if part == Part::Code {
                                &["number"]
                            } else {
                                &["text"]
                            };
                            self.field(entry_key, entry_value, only, &format!("{name}'s type"))
                        }
                    };
                    if let Some(field) = field {
                        fields.push(field);
                    }
                }
                if fields.is_empty() && self.problems.len() == before {
                    self.problem(key, format!("collector {id} needs at least one field"));
                }
            }
            Some((_, declared)) => self.problem(
                declared,
                format!("collector {id} needs fields:, the values it fills"),
            ),
            None => self.problem(
                node,
                format!("collector {id} needs fields:, the values it fills"),
            ),
        }
        if self.problems.len() > before {
            return None;
        }
        Some(Collector {
            id,
            command: command?,
            every,
            timeout,
            fields,
        })
    }

    // ---- logs ----

    fn log(&mut self, node: &Node) -> Option<Log> {
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(node, "each log is a mapping: - id: ...");
            return None;
        }
        let before = self.problems.len();
        let keys = self.mapping(node, LOG_KEYS, "a log");
        let id = self.id(&keys, node, "log");
        let label = value(&keys, "label")
            .and_then(|n| self.described(n, "label"))
            .unwrap_or_default();
        if let Some((key, _)) = get(&keys, "http") {
            self.problem(
                key,
                "a log runs a command (run:), which pages by the SPOTTER_* environment variables",
            );
        }
        let run = match get(&keys, "run") {
            None => {
                self.problem(
                    node,
                    format!("log {id} needs run: the command that prints its entries"),
                );
                None
            }
            Some(entry) => self.run(entry),
        };
        let map = match get(&keys, "map") {
            Some(entry) => self.log_map(entry),
            None => LogMap::default(),
        };
        if self.problems.len() > before {
            return None;
        }
        Some(Log {
            id,
            label,
            run: run?,
            map,
        })
    }

    fn log_map(&mut self, (key, node): (&Node, &Node)) -> LogMap {
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(key, "map is a mapping: {message: MESSAGE, ...}");
            return LogMap::default();
        }
        let keys = self.mapping(node, MAP_KEYS, "a log's map");
        let d = LogMap::default();
        let mut time = d.time.clone();
        let mut unit = String::new();
        match value(&keys, "time") {
            Some(
                time_node @ Node {
                    kind: NodeKind::Mapping(_),
                    ..
                },
            ) => {
                let parts = self.mapping(time_node, &["from", "unit"], "a log's time");
                if let Some(from) = value(&parts, "from").and_then(|n| self.text(n, "from")) {
                    time = from;
                }
                unit = value(&parts, "unit")
                    .and_then(|n| self.text(n, "unit"))
                    .unwrap_or_default();
                if !unit.is_empty() && !["ns", "us", "ms", "s"].contains(&unit.as_str()) {
                    if let Some((unit_key, _)) = get(&parts, "unit") {
                        self.problem(
                            unit_key,
                            format!("a time's unit is ns, us, ms or s, not {unit}"),
                        );
                    }
                }
            }
            Some(time_node) => {
                if let Some(from) = self.text(time_node, "time") {
                    time = from;
                }
            }
            None => {}
        }
        let mut part = |name: &str, default: &str| {
            value(&keys, name)
                .and_then(|n| self.text(n, name))
                .unwrap_or_else(|| default.to_string())
        };
        LogMap {
            time,
            time_unit: unit,
            level: part("level", &d.level),
            source: part("source", &d.source),
            message: part("message", &d.message),
            cursor: part("cursor", &d.cursor),
        }
    }

    // ---- actions ----

    fn action(&mut self, node: &Node) -> Option<Action> {
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(node, "each action is a mapping: - id: ...");
            return None;
        }
        let before = self.problems.len();
        let keys = self.mapping(node, ACTION_KEYS, "an action");
        let id = self.id(&keys, node, "action");
        let command = self.command(&keys, node, false);
        let mut input = Input::None;
        if let Some((key, given)) = get(&keys, "input") {
            let given = self.text(given, "input").unwrap_or_default();
            match given.as_str() {
                "none" => input = Input::None,
                "text" => input = Input::Text,
                "file" => input = Input::File,
                _ => self.problem(key, format!("input is none, text or file, not {given}")),
            }
        }
        if let Some(Command::Http(http)) = &command {
            if http.method == Method::Get && input != Input::None {
                self.problem(
                    node,
                    format!("action {id} sends a GET, which takes no input"),
                );
            }
        }
        let timeout = match get(&keys, "timeout") {
            Some(entry) => self.duration(entry, Duration::from_millis(1), Action::MAX_TIMEOUT),
            None => Action::TIMEOUT,
        };
        let while_enabled = match value(&keys, "whileEnabled") {
            Some(given) => self.boolean(given, "whileEnabled"),
            None => false,
        };
        let response = match &command {
            Some(command) => self.response(get(&keys, "response"), command.kind()),
            None => Vec::new(),
        };
        if self.problems.len() > before {
            return None;
        }
        let mut text = |name: &str| {
            value(&keys, name)
                .and_then(|n| self.described(n, name))
                .unwrap_or_default()
        };
        Some(Action {
            id,
            label: text("label"),
            description: text("description"),
            command: command?,
            input,
            confirm: text("confirm"),
            timeout,
            while_enabled,
            response,
        })
    }

    /// An action's response: the fields every response has (`outcome`, `outcomeMessage`, and its
    /// kind's code and whole output), then the pack's, each a key of a JSON output. A pack may
    /// declare its kind's own fields, to give their limits, or the whole output's type.
    fn response(&mut self, declared: Option<(&Node, &Node)>, kind: Kind) -> Vec<Field> {
        let mut built_in = pack::built_in_response(kind);
        let mut own = Vec::new();
        if let Some((key, node)) = declared {
            if !matches!(node.kind, NodeKind::Mapping(_)) {
                self.problem(key, "response is a mapping of fields: name: {type: ...}");
            } else {
                self.fields_bounded(key, node, "a response");
                for (entry_key, entry_value) in self.entries(node) {
                    let name = entry_key.scalar().unwrap_or("").to_string();
                    let (types, what): (&[&str], String) =
                        if name == OUTCOME || name == OUTCOME_MESSAGE {
                            self.problem(
                                entry_key,
                                format!("{name} is every response's own, and can't be declared"),
                            );
                            continue;
                        } else if name == kind.code() {
                            (&["number"], format!("{name}'s type"))
                        } else if name == kind.whole() {
                            (
                                &["file", "json", "text"],
                                format!("{name}'s type, the whole output's"),
                            )
                        } else {
                            (
                                &["boolean", "json", "number", "status", "text"],
                                "a response field's type".to_string(),
                            )
                        };
                    let Some(field) = self.field(entry_key, entry_value, types, &what) else {
                        continue;
                    };
                    match built_in.iter_mut().find(|each| each.name == name) {
                        Some(each) => *each = field,
                        None => own.push(field),
                    }
                }
            }
        }
        built_in.extend(own);
        built_in
    }

    // ---- commands ----

    /// The one command a collector, log or action names: `run`, `http` or `file`.
    fn command(&mut self, keys: &Keys<'_>, node: &Node, collector: bool) -> Option<Command> {
        let kinds: Vec<Kind> = Kind::ALL
            .into_iter()
            .filter(|kind| get(keys, kind.key()).is_some())
            .collect();
        if kinds.len() != 1 {
            let named: Vec<&str> = kinds.iter().map(|kind| kind.key()).collect();
            self.problem(
                node,
                format!(
                    "{} names one of {}{}",
                    if collector {
                        "a collector"
                    } else {
                        "an action"
                    },
                    if collector {
                        "run:, http: or file:"
                    } else {
                        "run: or http:"
                    },
                    if named.is_empty() {
                        String::new()
                    } else {
                        format!(", not {}", named.join(" and "))
                    }
                ),
            );
            return None;
        }
        let entry = get(keys, kinds[0].key())?;
        match kinds[0] {
            Kind::Run => self.run(entry),
            Kind::Http => self.http(entry, collector),
            Kind::Read => {
                if !collector {
                    self.problem(entry.0, "only a collector reads a file");
                    return None;
                }
                let path = self.text(entry.1, "file").unwrap_or_default();
                if !path.starts_with('/') {
                    self.problem(entry.0, format!("file is an absolute path, not {path}"));
                    return None;
                }
                Some(Command::Read { path })
            }
        }
    }

    fn run(&mut self, (key, node): (&Node, &Node)) -> Option<Command> {
        let NodeKind::Sequence(items) = &node.kind else {
            self.problem(key, "run is a list: [program, its arguments...]");
            return None;
        };
        let mut argv = Vec::new();
        for item in items {
            argv.push(self.text(item, "run")?);
        }
        if argv.is_empty() || argv[0].is_empty() {
            self.problem(key, "run names a program first");
            return None;
        }
        if let Some(own) = argv[0].strip_prefix("./") {
            let why = self
                .checks
                .as_deref()
                .and_then(|checks| checks.program(own));
            if let Some(why) = why {
                self.problem(key, why);
            }
        }
        Some(Command::Run { argv })
    }

    fn http(&mut self, (key, node): (&Node, &Node), collector: bool) -> Option<Command> {
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(
                key,
                "http is a mapping: {get: \"http://localhost:5800/...\"}",
            );
            return None;
        }
        let known: &[&str] = if collector {
            &["get", "post"]
        } else {
            &["form", "get", "post"]
        };
        let keys = self.mapping(node, known, "http");
        let get_given = get(&keys, "get").is_some();
        if get_given == get(&keys, "post").is_some() {
            self.problem(key, "http names one of get: or post:, with its URL");
            return None;
        }
        let at = get(&keys, if get_given { "get" } else { "post" });
        let url = match at {
            Some((_, url)) => self.text(url, "a URL").unwrap_or_default(),
            None => String::new(),
        };
        if !local(&url) {
            let where_ = at.map(|(k, _)| k).unwrap_or(key);
            self.problem(
                where_,
                format!("http asks the board itself only: http://localhost..., not {url}"),
            );
            return None;
        }
        let mut form = String::new();
        let mut filename = String::new();
        if let Some((form_key, form_node)) = get(&keys, "form") {
            if matches!(form_node.kind, NodeKind::Mapping(_)) {
                let parts = self.mapping(form_node, &["field", "filename"], "form");
                match value(&parts, "field") {
                    None => self.problem(
                        form_key,
                        "a form names its field: {field: data, filename: layout.json}",
                    ),
                    Some(field) => form = self.form_name(field, "field"),
                }
                if let Some(file) = value(&parts, "filename") {
                    filename = self.form_name(file, "filename");
                }
            } else {
                form = self.form_name(form_node, "field");
            }
            if get_given {
                self.problem(form_key, "a GET sends no form");
            }
        }
        Some(Command::Http(Http {
            method: if get_given { Method::Get } else { Method::Post },
            url,
            form,
            filename,
        }))
    }

    /// A form's field or file name: a plain name, as it goes into a header as it is.
    fn form_name(&mut self, node: &Node, what: &str) -> String {
        let name = self
            .text(node, &format!("a form's {what}"))
            .unwrap_or_default();
        if !is_form_name(&name) {
            self.problem(
                node,
                format!(
                    "a form's {what} is letters, digits, \".\", \"_\" and \"-\", such as data or layout.json, not \"{name}\""
                ),
            );
            return String::new();
        }
        name
    }

    // ---- fields and limits ----

    fn field(&mut self, key: &Node, node: &Node, types: &[&str], what: &str) -> Option<Field> {
        let name = key.scalar().unwrap_or("").to_string();
        if !is_id(&name) {
            self.problem(
                key,
                format!("field \"{name}\" isn't a name: letters, digits, and . _ -"),
            );
            return None;
        }
        if self.checking() && name.contains('.') {
            self.problem(key, dotted("field", &name));
        }
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(
                key,
                format!("field {name} is a mapping: {{type: ..., label: ...}}"),
            );
            return None;
        }
        let keys = self.mapping(node, FIELD_KEYS, "a field");
        let before = self.problems.len();
        let mut kind = FieldType::Text;
        let mut type_name = String::new();
        match get(&keys, "type") {
            None => self.problem(
                key,
                format!("field {name} needs a type: {}", types.join(", ")),
            ),
            Some((type_key, given)) => {
                type_name = self.text(given, "type").unwrap_or_default();
                match field_type(&type_name) {
                    Some(named) if types.contains(&type_name.as_str()) => kind = named,
                    _ => self.problem(
                        type_key,
                        format!("{what} is {}, not {type_name}", types.join(", ")),
                    ),
                }
            }
        }
        let file_name = value(&keys, "name")
            .and_then(|n| self.described(n, "name"))
            .unwrap_or_default();
        if let Some((name_key, _)) = get(&keys, "name") {
            if type_name != "file" {
                self.problem(name_key, "name is the file a file field downloads as");
            }
        }
        let warn = self.limit(get(&keys, "warn"), kind);
        let fail = self.limit(get(&keys, "fail"), kind);
        if self.problems.len() > before {
            return None;
        }
        let label = value(&keys, "label")
            .and_then(|n| self.described(n, "label"))
            .unwrap_or_default();
        let unit = value(&keys, "unit")
            .and_then(|n| self.described(n, "unit"))
            .unwrap_or_default();
        Some(Field {
            name,
            label,
            kind,
            unit,
            warn,
            fail,
            file_name,
        })
    }

    fn limit(&mut self, entry: Option<(&Node, &Node)>, kind: FieldType) -> Limit {
        let mut limit = Limit::default();
        let Some((key, node)) = entry else {
            return limit;
        };
        if !matches!(node.kind, NodeKind::Mapping(_)) {
            self.problem(key, "a limit is a mapping: {below: 5, missing: true}");
            return limit;
        }
        let keys = self.mapping(node, LIMIT_KEYS, "a limit");
        let number = kind == FieldType::Number;
        for bound in ["above", "below"] {
            let Some((bound_key, given)) = get(&keys, bound) else {
                continue;
            };
            if !number {
                self.problem(bound_key, format!("{bound} is for numbers"));
                continue;
            }
            if let Some(value) = self.number(given, bound) {
                if bound == "above" {
                    limit.above = Some(value);
                } else {
                    limit.below = Some(value);
                }
            }
        }
        if self.checking() && kind == FieldType::Status {
            for compared in ["equals", "notEquals"] {
                if let Some((compared_key, _)) = get(&keys, compared) {
                    self.problem(
                        compared_key,
                        format!(
                            "{compared} on a status is never applied: a status's level is its script's, and only missing applies to one"
                        ),
                    );
                }
            }
        }
        if let Some(given) = value(&keys, "equals") {
            limit.equals = self.scalar(given, kind, "equals");
        }
        if let Some(given) = value(&keys, "notEquals") {
            limit.not_equals = self.scalar(given, kind, "notEquals");
        }
        if let Some(given) = value(&keys, "missing") {
            limit.missing = self.boolean(given, "missing");
        }
        limit
    }

    /// A comparison's value, as its field's type has it: a number, a flag, or text.
    fn scalar(&mut self, node: &Node, kind: FieldType, what: &str) -> Option<Scalar> {
        let value = match kind {
            FieldType::Number => scalar::Value::Number(self.number(node, what)?),
            FieldType::Boolean => scalar::Value::Flag(self.boolean(node, what)),
            _ => scalar::Value::Text(self.described(node, what)?),
        };
        Some(Scalar { value: Some(value) })
    }

    // ---- scalars ----

    fn id(&mut self, keys: &Keys<'_>, node: &Node, what: &str) -> String {
        let Some((key, given)) = get(keys, "id") else {
            self.problem(node, format!("a {what} needs an id"));
            return String::new();
        };
        let id = self.text(given, "id").unwrap_or_default();
        if !is_id(&id) {
            self.problem(
                key,
                format!("{what} id \"{id}\" isn't a name: letters, digits, and . _ -"),
            );
        } else if self.checking() && id.contains('.') {
            self.problem(key, dotted(&format!("{what} id"), &id));
        }
        id
    }

    fn duration(&mut self, (key, node): (&Node, &Node), min: Duration, max: Duration) -> Duration {
        let text = self.text(node, "a duration").unwrap_or_default();
        let Some(read) = duration::parse(&text) else {
            self.problem(
                key,
                format!(
                    "a duration is a number and its unit (ms, s, m, h), such as 2s, not {text}"
                ),
            );
            return min;
        };
        if read < min || read > max {
            self.problem(
                key,
                format!(
                    "{text} is out of range: {} to {}",
                    duration::show(min),
                    duration::show(max)
                ),
            );
            return min;
        }
        read
    }

    fn number(&mut self, node: &Node, what: &str) -> Option<f64> {
        let text = self.text(node, what)?;
        let plain = matches!(node.kind, NodeKind::Scalar { plain: true, .. });
        if !plain || !is_number(&text) {
            self.problem(node, format!("{what} is a number, not {text}"));
            return None;
        }
        text.parse().ok()
    }

    fn boolean(&mut self, node: &Node, what: &str) -> bool {
        if let NodeKind::Scalar {
            value,
            resolved: Resolved::Bool,
            ..
        } = &node.kind
        {
            return value.eq_ignore_ascii_case("true");
        }
        self.problem(node, format!("{what} is true or false"));
        false
    }

    /// A scalar's text, as written (unquoted); a problem when the node isn't one, or is null.
    fn text(&mut self, node: &Node, what: &str) -> Option<String> {
        match &node.kind {
            NodeKind::Scalar {
                value, resolved, ..
            } if *resolved != Resolved::Null => Some(value.clone()),
            _ => {
                self.problem(node, format!("{what} needs a single value"));
                None
            }
        }
    }

    /// Text a description carries: as [`Reader::text`], and at most [`MAX_TEXT`] characters.
    fn described(&mut self, node: &Node, what: &str) -> Option<String> {
        let text = self.text(node, what)?;
        let length = text.encode_utf16().count();
        if length > MAX_TEXT {
            self.problem(
                node,
                format!("{what} is {length} characters, more than {MAX_TEXT}"),
            );
            return None;
        }
        Some(text)
    }

    /// A problem when a list has more than its most.
    fn most(&mut self, entry: Option<(&Node, &Node)>, what: &str, most: usize) {
        if let Some((
            key,
            Node {
                kind: NodeKind::Sequence(items),
                ..
            },
        )) = entry
        {
            if items.len() > most {
                self.problem(
                    key,
                    format!("a pack has at most {most} {what}, not {}", items.len()),
                );
            }
        }
    }

    /// A problem when a collector or a response declares more than the most fields.
    fn fields_bounded(&mut self, key: &Node, node: &Node, what: &str) {
        if let NodeKind::Mapping(entries) = &node.kind {
            if entries.len() > MAX_FIELDS {
                self.problem(
                    key,
                    format!(
                        "{what} has at most {MAX_FIELDS} fields, not {}",
                        entries.len()
                    ),
                );
            }
        }
    }

    // ---- mappings and sequences ----

    /// A mapping's entries by key, each key known (a problem for each that isn't).
    fn mapping<'a>(&mut self, node: &'a Node, known: &[&str], what: &str) -> Keys<'a> {
        let mut keys = Vec::new();
        for (key, value) in self.entries(node) {
            let text = key.scalar().unwrap_or("");
            if !known.contains(&text) {
                self.problem(
                    key,
                    format!(
                        "unknown key \"{text}\" in {what}; known: {}",
                        known.join(", ")
                    ),
                );
            } else {
                keys.push((text, key, value));
            }
        }
        keys
    }

    /// A mapping's entries, in its order; a problem for a key that isn't text.
    fn entries<'a>(&mut self, node: &'a Node) -> Vec<(&'a Node, &'a Node)> {
        let NodeKind::Mapping(entries) = &node.kind else {
            return Vec::new();
        };
        let mut kept = Vec::new();
        for (key, value) in entries {
            if key.scalar().is_none() {
                self.problem(key, "a key is a single value");
                continue;
            }
            kept.push((key, value));
        }
        kept
    }

    fn sequence<'a>(&mut self, entry: Option<(&'a Node, &'a Node)>, what: &str) -> &'a [Node] {
        let Some((key, value)) = entry else {
            return &[];
        };
        if value.is_null() {
            return &[];
        }
        let NodeKind::Sequence(items) = &value.kind else {
            self.problem(key, format!("{what} is a list: - id: ..."));
            return &[];
        };
        items
    }

    // ---- problems ----

    /// Where a node is, as a problem starts: `file:line: `.
    fn at(&self, node: &Node) -> String {
        if node.line > 0 {
            format!("{}:{}: ", self.source, node.line)
        } else {
            format!("{}: ", self.source)
        }
    }

    fn problem(&mut self, node: &Node, message: impl AsRef<str>) {
        let at = self.at(node);
        self.problems.push(format!("{at}{}", message.as_ref()));
    }
}

/// Why a name with a dot is a mistake: a value's id joins names with dots.
fn dotted(what: &str, name: &str) -> String {
    format!(
        "{what} \"{name}\" has a dot, so its values' ids (pack.collector.field) can read two ways: use - or _"
    )
}

fn one_line(message: &str) -> String {
    message.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Whether a URL is the board's own: `http` to localhost, `127.0.0.1` or `[::1]`, with no user.
pub fn local(url: &str) -> bool {
    let Ok(uri) = url.parse::<http::Uri>() else {
        return false;
    };
    let Some(authority) = uri.authority() else {
        return false;
    };
    uri.scheme_str() == Some("http")
        && !authority.as_str().contains('@')
        && ["localhost", "127.0.0.1", "[::1]"]
            .contains(&authority.host().to_ascii_lowercase().as_str())
}
