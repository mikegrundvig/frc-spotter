//! JSON as the agent reads it: `agent.json`, and what a collector or an action prints, as
//! serde_json's values (objects keep their keys' order, as they're written back so). JSON nested
//! deeper than [`MAX_DEPTH`] isn't read as JSON at all: a count of brackets outside strings, before
//! the parser sees it.

use serde_json::{Map, Value};

/// How deep JSON may nest to be read: a script's output needs a few levels.
pub const MAX_DEPTH: usize = 64;

/// A JSON object's members, or none when the text, trimmed, isn't one.
pub fn as_object(json: &str) -> Option<Map<String, Value>> {
    let text = json.trim();
    if !text.starts_with('{') || !text.ends_with('}') || too_deep(text) {
        return None;
    }
    match serde_json::from_str::<Value>(text) {
        Ok(Value::Object(members)) => Some(members),
        _ => None,
    }
}

/// A JSON object's members, or why the text isn't one.
pub fn object(json: &str) -> Result<Map<String, Value>, String> {
    as_object(json).ok_or_else(|| "it isn't a JSON object".to_string())
}

/// Whether JSON nests deeper than [`MAX_DEPTH`]: its `[` and `{` outside strings, counted. Only
/// the structure is looked at, never what's in it.
pub fn too_deep(json: &str) -> bool {
    let mut depth = 0usize;
    let mut in_string = false;
    let mut bytes = json.bytes();
    while let Some(c) = bytes.next() {
        if in_string {
            if c == b'\\' {
                bytes.next();
            } else if c == b'"' {
                in_string = false;
            }
        } else if c == b'"' {
            in_string = true;
        } else if c == b'{' || c == b'[' {
            depth += 1;
            if depth > MAX_DEPTH {
                return true;
            }
        } else if c == b'}' || c == b']' {
            depth = depth.saturating_sub(1);
        }
    }
    false
}

/// A value as JSON text.
pub fn write(value: &Value) -> String {
    value.to_string()
}

/// A value as text: a string as it is, anything else as its JSON.
pub fn text(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        other => write(other),
    }
}

/// A whole number, as Java's JSON reads one (a `Long`): an integer with no fraction or exponent
/// in range; none for any other value.
pub fn whole(value: &Value) -> Option<i64> {
    match value {
        Value::Number(number) => number.as_i64(),
        _ => None,
    }
}
