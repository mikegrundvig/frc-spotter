//! A pack's YAML as nodes, each with its line, read with saphyr-parser (YAML 1.2) and resolved by
//! YAML 1.2's core schema: what the reader needs to check a pack and say where each mistake is.
//! Bounded as a pushed pack is anyone's: nested at most [`MAX_DEPTH`] deep (checked on the parser's
//! events before anything is built), at most [`MAX_ALIASES`] aliases of collections, and a key
//! twice in one mapping is refused.

use std::collections::HashMap;

use saphyr_parser::{Event, Parser, ScalarStyle, Span};

/// How deep a pack's YAML may nest: a pack needs five or six.
pub const MAX_DEPTH: usize = 32;

/// The most aliases of a mapping or a list a pack may use: each copies what it names.
pub const MAX_ALIASES: usize = 16;

/// What a scalar is, by the core schema: its tag, as YAML resolves it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Resolved {
    Null,
    Bool,
    Int,
    Float,
    Str,
}

#[derive(Clone, Debug)]
pub enum Kind {
    /// A single value: its text as written (unquoted), whether it was plain (no quotes, not a
    /// block), and what it resolves to.
    Scalar {
        value: String,
        plain: bool,
        resolved: Resolved,
    },
    Sequence(Vec<Node>),
    Mapping(Vec<(Node, Node)>),
}

#[derive(Clone, Debug)]
pub struct Node {
    pub kind: Kind,
    /// Its first line, from 1.
    pub line: usize,
}

impl Node {
    /// A scalar's text as written, whatever its tag.
    pub fn scalar(&self) -> Option<&str> {
        match &self.kind {
            Kind::Scalar { value, .. } => Some(value),
            _ => None,
        }
    }

    pub fn is_null(&self) -> bool {
        matches!(
            self.kind,
            Kind::Scalar {
                resolved: Resolved::Null,
                ..
            }
        )
    }
}

/// Why a document can't be read: what, and the line it's at (0 when none is known).
#[derive(Debug, PartialEq)]
pub struct Refused {
    pub line: usize,
    pub message: String,
}

impl Refused {
    fn at(line: usize, message: impl Into<String>) -> Refused {
        Refused {
            line,
            message: message.into(),
        }
    }
}

/// The document's root node; none for an empty document.
pub fn load(text: &str) -> Result<Option<Node>, Refused> {
    depth(text)?;
    let mut builder = Builder::default();
    for event in Parser::new_from_str(text) {
        let (event, span) = event.map_err(|e| Refused::at(e.marker().line(), e.info()))?;
        if builder.take(event, span)? {
            break;
        }
    }
    Ok(builder.root)
}

/// What saphyr's scanner says of flow collections nested more than 255 deep.
const SCANNER_NESTING: &str = "recursion limit exceeded";

/// Refuses YAML nested deeper than [`MAX_DEPTH`], from the parser's own events, before anything is
/// built.
fn depth(text: &str) -> Result<(), Refused> {
    let nested = |line: usize| {
        Refused::at(
            line,
            format!("nested deeper than {MAX_DEPTH} (a pack needs five or six)"),
        )
    };
    let mut depth = 0usize;
    for event in Parser::new_from_str(text) {
        // The scanner reads a flow collection's brackets ahead of its events, and refuses more
        // than 255 of them itself: deeper than any pack, so said as the depth it is.
        let (event, span) = event.map_err(|e| {
            if e.info() == SCANNER_NESTING {
                nested(e.marker().line())
            } else {
                Refused::at(e.marker().line(), e.info())
            }
        })?;
        match event {
            Event::SequenceStart(..) | Event::MappingStart(..) => {
                depth += 1;
                if depth > MAX_DEPTH {
                    return Err(nested(span.start.line()));
                }
            }
            Event::SequenceEnd | Event::MappingEnd => depth -= 1,
            Event::DocumentEnd => break,
            _ => {}
        }
    }
    Ok(())
}

/// A collection being built, and the anchor it's to be kept under.
struct Open {
    node: Node,
    anchor: usize,
    /// A mapping's key, read, waiting for its value.
    key: Option<Node>,
}

#[derive(Default)]
struct Builder {
    open: Vec<Open>,
    anchors: HashMap<usize, Node>,
    aliases: usize,
    root: Option<Node>,
    /// Whether the first document has started: only it is read.
    started: bool,
}

impl Builder {
    /// Takes one event: whether the document is done.
    fn take(&mut self, event: Event<'_>, span: Span) -> Result<bool, Refused> {
        let line = span.start.line();
        match event {
            Event::DocumentStart(_) => {
                if self.started {
                    return Ok(true);
                }
                self.started = true;
            }
            Event::DocumentEnd | Event::StreamEnd => return Ok(self.started),
            Event::Scalar(value, style, anchor, tag) => {
                let resolved = resolve(&value, style, tag.as_ref().map(|t| t.suffix.as_str()));
                let node = Node {
                    kind: Kind::Scalar {
                        value: value.into_owned(),
                        plain: style == ScalarStyle::Plain,
                        resolved,
                    },
                    line,
                };
                self.finish(node, anchor)?;
            }
            Event::SequenceStart(anchor, _) => self.open.push(Open {
                node: Node {
                    kind: Kind::Sequence(Vec::new()),
                    line,
                },
                anchor,
                key: None,
            }),
            Event::MappingStart(anchor, _) => self.open.push(Open {
                node: Node {
                    kind: Kind::Mapping(Vec::new()),
                    line,
                },
                anchor,
                key: None,
            }),
            Event::SequenceEnd | Event::MappingEnd => {
                if let Some(done) = self.open.pop() {
                    self.finish(done.node, done.anchor)?;
                }
            }
            Event::Alias(anchor) => {
                let Some(named) = self.anchors.get(&anchor).cloned() else {
                    return Err(Refused::at(line, "found undefined alias"));
                };
                if !matches!(named.kind, Kind::Scalar { .. }) {
                    self.aliases += 1;
                    if self.aliases > MAX_ALIASES {
                        return Err(Refused::at(
                            line,
                            format!(
                                "Number of aliases for non-scalar nodes exceeds the specified max={MAX_ALIASES}"
                            ),
                        ));
                    }
                }
                self.finish(Node { line, ..named }, 0)?;
            }
            Event::Nothing | Event::StreamStart => {}
        }
        Ok(false)
    }

    /// A node complete: kept under its anchor, and put in the collection it's in.
    fn finish(&mut self, node: Node, anchor: usize) -> Result<(), Refused> {
        if anchor != 0 {
            self.anchors.insert(anchor, node.clone());
        }
        let Some(parent) = self.open.last_mut() else {
            if self.root.is_none() {
                self.root = Some(node);
            }
            return Ok(());
        };
        match &mut parent.node.kind {
            Kind::Sequence(items) => items.push(node),
            Kind::Mapping(entries) => match parent.key.take() {
                None => parent.key = Some(node),
                Some(key) => {
                    if entries.iter().any(|(other, _)| same_key(other, &key)) {
                        let shown = key.scalar().unwrap_or("").to_string();
                        return Err(Refused::at(
                            key.line,
                            format!("found duplicate key {shown}"),
                        ));
                    }
                    entries.push((key, node));
                }
            },
            Kind::Scalar { .. } => {}
        }
        Ok(())
    }
}

/// Whether two keys are the same key, as YAML compares them: scalars by their resolved value.
fn same_key(a: &Node, b: &Node) -> bool {
    match (&a.kind, &b.kind) {
        (
            Kind::Scalar {
                value: x,
                resolved: rx,
                ..
            },
            Kind::Scalar {
                value: y,
                resolved: ry,
                ..
            },
        ) => rx == ry && canonical(x, *rx) == canonical(y, *ry),
        _ => false,
    }
}

/// A scalar's value in one form, so `true` and `True` are one key, as are `null` and `~`.
fn canonical(value: &str, resolved: Resolved) -> String {
    match resolved {
        Resolved::Null => String::new(),
        Resolved::Bool => value.to_lowercase(),
        Resolved::Int => value
            .parse::<i64>()
            .map(|n| n.to_string())
            .unwrap_or(value.to_string()),
        Resolved::Float | Resolved::Str => value.to_string(),
    }
}

/// What a scalar resolves to by YAML 1.2's core schema: a plain one by its text, any other by its
/// explicit tag, or else as text.
fn resolve(value: &str, style: ScalarStyle, tag: Option<&str>) -> Resolved {
    if let Some(tag) = tag {
        return match tag {
            "null" => Resolved::Null,
            "bool" => Resolved::Bool,
            "int" => Resolved::Int,
            "float" => Resolved::Float,
            _ => Resolved::Str,
        };
    }
    if style != ScalarStyle::Plain {
        return Resolved::Str;
    }
    match value {
        "" | "~" | "null" | "Null" | "NULL" => Resolved::Null,
        "true" | "True" | "TRUE" | "false" | "False" | "FALSE" => Resolved::Bool,
        _ if is_int(value) => Resolved::Int,
        _ if is_float(value) => Resolved::Float,
        _ => Resolved::Str,
    }
}

fn is_int(value: &str) -> bool {
    let digits = value.strip_prefix(['-', '+']).unwrap_or(value);
    if let Some(octal) = value.strip_prefix("0o") {
        return !octal.is_empty() && octal.bytes().all(|b| (b'0'..=b'7').contains(&b));
    }
    if let Some(hex) = value.strip_prefix("0x") {
        return !hex.is_empty() && hex.bytes().all(|b| b.is_ascii_hexdigit());
    }
    !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit())
}

fn is_float(value: &str) -> bool {
    matches!(
        value,
        ".inf"
            | ".Inf"
            | ".INF"
            | "+.inf"
            | "+.Inf"
            | "+.INF"
            | "-.inf"
            | "-.Inf"
            | "-.INF"
            | ".nan"
            | ".NaN"
            | ".NAN"
    ) || super::reader::is_number(value)
}
