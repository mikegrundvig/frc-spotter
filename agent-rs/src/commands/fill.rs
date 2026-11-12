//! How a command's output fills fields: one rule, the same for collectors and actions.
//!
//! - The **named parts** are filled by the agent, by name, and never from a JSON key: `outcome`
//!   and `outcomeMessage`, then a `run` command's `exit` and `output`, or an `http` request's
//!   `status` and `body`. A `file` read has only the first two.
//! - Every other field: if the output is a JSON object, each takes the key with its name (keys
//!   nobody declared are ignored), whatever the exit code or status; otherwise the output is one
//!   value, trimmed and converted to the type of the one field that holds it.
//! - When the command didn't complete, `outcome` and `outcomeMessage` say so, and every other
//!   field is unavailable, with that as its reason.
//! - When it completed but printed more than is kept, `outcome`, `outcomeMessage`, and `exit` or
//!   `status` stand, and every field read from its output is unavailable, with that as its reason.

use serde_json::Value;

use super::Ran;
use crate::board::json;
use crate::packs::command::Part;
use crate::packs::field::Field;
use crate::packs::reader::is_number;
use crate::proto::{FieldType, FieldValue, Level, Outcome, Status, field_value};

/// The most of an output a reason quotes.
const QUOTED: usize = 60;

/// An outcome's name, as the `outcome` part has it: `completed`, `timedOut`.
pub fn name(outcome: Outcome) -> &'static str {
    match outcome {
        Outcome::Completed => "completed",
        Outcome::Unreachable => "unreachable",
        Outcome::CouldNotStart => "couldNotStart",
        Outcome::TimedOut => "timedOut",
        Outcome::Cancelled => "cancelled",
        Outcome::Lost => "lost",
        Outcome::Unspecified => "",
    }
}

/// Why a command's output fills none of its fields: it didn't complete, or printed more than was
/// kept; empty when it fills them.
pub fn reason(ran: &Ran, max_output: usize) -> String {
    if !ran.completed() {
        return ran.message.clone();
    }
    if ran.truncated {
        return format!("it printed more than the {} KiB kept", max_output / 1024);
    }
    String::new()
}

/// A value of these contents.
pub fn value(value: field_value::Value) -> FieldValue {
    FieldValue {
        index: 0,
        name: String::new(),
        value: Some(value),
    }
}

/// A value that's unavailable, and why.
pub fn unavailable(why: impl Into<String>) -> FieldValue {
    value(field_value::Value::Unavailable(why.into()))
}

fn text(text: impl Into<String>) -> FieldValue {
    value(field_value::Value::Text(text.into()))
}

/// Every field unavailable, for one reason.
pub fn all_unavailable(fields: &[Field], why: &str) -> Vec<FieldValue> {
    fields.iter().map(|_| unavailable(why)).collect()
}

/// The fields, in their order, from one run of a command, by the rule; a `file` field's value is
/// where to fetch it, `files` and its name (`/v2/runs/<run>/files/`).
pub fn fill(fields: &[Field], ran: &Ran, max_output: usize, files: &str) -> Vec<FieldValue> {
    let reason = reason(ran, max_output);
    let own: Vec<Field> = fields
        .iter()
        .filter(|field| ran.kind.part(&field.name).is_none())
        .cloned()
        .collect();
    let output = ran.text();
    let mut filled = if reason.is_empty() {
        from_output(&own, &output)
    } else {
        all_unavailable(&own, &reason)
    }
    .into_iter();
    let mut values = Vec::with_capacity(fields.len());
    for field in fields {
        let filled_value = match ran.kind.part(&field.name) {
            None => filled.next().unwrap_or_else(|| unavailable("")),
            Some(Part::Outcome) => text(name(ran.outcome)),
            Some(Part::OutcomeMessage) => text(ran.message.clone()),
            // The call completed, whatever became of its output: its status or exit code stands.
            Some(Part::Code) if ran.completed() => {
                value(field_value::Value::Number(f64::from(ran.code)))
            }
            Some(Part::Code) => unavailable(reason.clone()),
            Some(Part::Whole) if !reason.is_empty() => unavailable(reason.clone()),
            Some(Part::Whole) if field.kind == FieldType::File => value(
                field_value::Value::FileUrl(format!("{files}{}", field.name)),
            ),
            Some(Part::Whole) if field.kind == FieldType::Json => {
                value(field_value::Value::Json(output.clone()))
            }
            Some(Part::Whole) => text(output.clone()),
        };
        values.push(filled_value);
    }
    values
}

/// Fields from an output: a JSON object's keys, or else the one value.
pub fn from_output(fields: &[Field], output: &str) -> Vec<FieldValue> {
    if let Some(keys) = json::as_object(output) {
        return fields
            .iter()
            .map(|field| match keys.get(&field.name) {
                Some(found) => from_json(field, found),
                None => unavailable(format!("its output has no \"{}\"", field.name)),
            })
            .collect();
    }
    if fields.len() != 1 {
        return all_unavailable(
            fields,
            &format!(
                "its output isn't a JSON object, which its {} fields need: {}",
                fields.len(),
                quote(output.trim())
            ),
        );
    }
    vec![from_text(&fields[0], output.trim())]
}

/// A field from text: trimmed, and converted to its type.
pub fn from_text(field: &Field, text: &str) -> FieldValue {
    match field.kind {
        FieldType::Number => {
            let first = text.split_whitespace().next().unwrap_or("");
            if is_number(first) {
                if let Ok(number) = first.parse::<f64>() {
                    if number.is_finite() {
                        return value(field_value::Value::Number(number));
                    }
                }
            }
            unavailable(format!("not a number: {}", quote(text)))
        }
        FieldType::Boolean => match text.to_lowercase().as_str() {
            "true" => value(field_value::Value::Flag(true)),
            "false" => value(field_value::Value::Flag(false)),
            _ => unavailable(format!("not true or false: {}", quote(text))),
        },
        FieldType::Status => unavailable(format!(
            "a status is a JSON object, {{\"level\": \"ok\", \"message\": ...}}, not {}",
            quote(text)
        )),
        FieldType::Json => value(field_value::Value::Json(text.to_string())),
        _ => self::text(text),
    }
}

/// A field from a JSON value: converted to its type.
pub fn from_json(field: &Field, found: &Value) -> FieldValue {
    if found.is_null() {
        return unavailable(format!("\"{}\" is null", field.name));
    }
    match field.kind {
        FieldType::Number => match found {
            Value::Number(number) => value(field_value::Value::Number(
                number.as_f64().unwrap_or(f64::NAN),
            )),
            Value::String(text) => from_text(field, text.trim()),
            other => unavailable(format!("not a number: {}", quote(&json::write(other)))),
        },
        FieldType::Boolean => match found {
            Value::Bool(flag) => value(field_value::Value::Flag(*flag)),
            Value::String(text) => from_text(field, text.trim()),
            other => unavailable(format!("not true or false: {}", quote(&json::write(other)))),
        },
        FieldType::Status => status(found),
        FieldType::Json => value(field_value::Value::Json(json::write(found))),
        _ => text(json::text(found)),
    }
}

/// A status from its JSON: `{"level": "ok", "message": "..."}`.
fn status(found: &Value) -> FieldValue {
    if let Value::Object(members) = found {
        let level = match members.get("level") {
            Some(Value::String(level)) => match level.to_lowercase().as_str() {
                "ok" => Some(Level::Ok),
                "warning" => Some(Level::Warning),
                "failing" => Some(Level::Failing),
                _ => None,
            },
            _ => None,
        };
        if let Some(level) = level {
            let message = match members.get("message") {
                None | Some(Value::Null) => String::new(),
                Some(given) => json::text(given),
            };
            return value(field_value::Value::Status(Status {
                level: level as i32,
                message,
            }));
        }
    }
    unavailable(format!(
        "a status's level is ok, warning or failing: {}",
        quote(&json::write(found))
    ))
}

/// Text quoted in a reason, cut short.
pub fn quote(text: &str) -> String {
    let shown = if text.encode_utf16().count() <= QUOTED {
        text.to_string()
    } else {
        format!("{}...", text.chars().take(QUOTED).collect::<String>())
    };
    format!("\"{}\"", shown.replace('\n', "\\n"))
}
