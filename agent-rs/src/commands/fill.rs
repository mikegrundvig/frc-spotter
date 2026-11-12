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
            if is_number(first)
                && let Ok(number) = first.parse::<f64>()
                && number.is_finite()
            {
                return value(field_value::Value::Number(number));
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

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;
    use crate::packs::command::{Command, Kind};

    fn field(name: &str, kind: FieldType) -> Field {
        Field::of(name, kind)
    }

    fn number() -> Field {
        field("busiest", FieldType::Number)
    }
    fn text_field() -> Field {
        field("version", FieldType::Text)
    }
    fn flag() -> Field {
        field("capped", FieldType::Boolean)
    }
    fn status_field() -> Field {
        field("status", FieldType::Status)
    }
    fn outcome() -> Field {
        field("outcome", FieldType::Text)
    }
    fn why() -> Field {
        field("outcomeMessage", FieldType::Text)
    }
    fn exit() -> Field {
        field("exit", FieldType::Number)
    }
    fn output() -> Field {
        field("output", FieldType::Text)
    }
    fn code() -> Field {
        field("status", FieldType::Number)
    }
    fn body() -> Field {
        field("body", FieldType::Text)
    }

    fn completed(kind: Kind, code: i32, output: &[u8], truncated: bool, errors: &str) -> Ran {
        Ran {
            kind,
            outcome: Outcome::Completed,
            message: String::new(),
            code,
            output: output.to_vec(),
            truncated,
            errors: errors.to_string(),
            ended: None,
        }
    }

    fn ran(exit: i32, output: &str, errors: &str) -> Ran {
        completed(Kind::Run, exit, output.as_bytes(), false, errors)
    }

    fn answered(status: i32, body: &str) -> Ran {
        completed(Kind::Http, status, body.as_bytes(), false, "")
    }

    /// What a value holds, as a test compares it.
    fn held(value: &FieldValue) -> field_value::Value {
        value.value.clone().expect("a value")
    }

    fn num(n: f64) -> field_value::Value {
        field_value::Value::Number(n)
    }
    fn txt(t: &str) -> field_value::Value {
        field_value::Value::Text(t.into())
    }
    fn flagged(f: bool) -> field_value::Value {
        field_value::Value::Flag(f)
    }
    fn gone(why: &str) -> field_value::Value {
        field_value::Value::Unavailable(why.into())
    }

    fn one(field: Field, output: &str) -> field_value::Value {
        held(&from_output(&[field], output)[0])
    }

    fn filled(fields: &[Field], ran: &Ran, max: usize) -> Vec<field_value::Value> {
        fill(fields, ran, max, "/v2/runs/r/files/")
            .iter()
            .map(held)
            .collect()
    }

    #[test]
    fn a_json_object_fills_each_field_from_the_key_of_its_name() {
        let values = from_output(
            &[number(), flag(), text_field()],
            "{\"busiest\": 87, \"capped\": false, \"version\": \"3.1\", \"other\": 1}",
        );
        let values: Vec<_> = values.iter().map(held).collect();
        assert_eq!(values, [num(87.0), flagged(false), txt("3.1")]);
    }

    #[test]
    fn a_key_not_there_is_unavailable_and_says_which() {
        let values = from_output(&[number(), text_field()], "{\"busiest\": 3}");
        assert_eq!(held(&values[1]), gone("its output has no \"version\""));
        // One field, a JSON object without its key: the object rule still holds.
        assert_eq!(
            one(text_field(), "{\"status\": \"up\"}"),
            gone("its output has no \"version\"")
        );
        assert_eq!(
            one(text_field(), "{\"version\": null}"),
            gone("\"version\" is null")
        );
    }

    #[test]
    fn other_output_is_one_value_trimmed_and_converted() {
        let rows = [
            // /proc/uptime: its first number fills the one field.
            (number(), "4123.55 15991.04\n", num(4123.55)),
            (text_field(), "  active\n", txt("active")),
            (text_field(), "", txt("")),
            (flag(), "TRUE\n", flagged(true)),
            (text_field(), "[1, 2]", txt("[1, 2]")),
            (number(), "-1.5e3", num(-1500.0)),
        ];
        for (field, output, value) in rows {
            assert_eq!(one(field, output), value, "{output}");
        }
    }

    #[test]
    fn what_doesnt_convert_is_unavailable_with_why() {
        let longer = "x".repeat(100);
        let quoted = format!("not a number: \"{}...\"", "x".repeat(QUOTED));
        let rows = [
            (number(), "fast", "not a number: \"fast\""),
            (number(), "", "not a number: \"\""),
            (flag(), "yes", "not true or false: \"yes\""),
            (number(), longer.as_str(), quoted.as_str()),
        ];
        for (field, output, why) in rows {
            assert_eq!(one(field, output), gone(why), "{output}");
        }
        for output in ["1e999", "NaN"] {
            assert!(
                matches!(one(number(), output), field_value::Value::Unavailable(_)),
                "{output}"
            );
        }
        let status = one(status_field(), "ok");
        assert!(
            matches!(&status, field_value::Value::Unavailable(why) if why.starts_with("a status is a JSON object")),
            "{status:?}"
        );
        let two = from_output(&[number(), text_field()], "87");
        assert_eq!(
            held(&two[0]),
            gone("its output isn't a JSON object, which its 2 fields need: \"87\"")
        );
    }

    #[test]
    fn json_values_convert_to_the_fields_type() {
        let failing = from_json(
            &status_field(),
            &json!({"level": "failing", "message": "2 since boot"}),
        );
        assert_eq!(
            held(&failing),
            field_value::Value::Status(Status {
                level: Level::Failing as i32,
                message: "2 since boot".into(),
            })
        );
        let ok = from_json(&status_field(), &json!({"level": "OK"}));
        assert!(
            matches!(held(&ok), field_value::Value::Status(Status { level, .. }) if level == Level::Ok as i32)
        );
        let bad = held(&from_json(&status_field(), &json!({"level": "bad"})));
        assert!(
            matches!(&bad, field_value::Value::Unavailable(why) if why.starts_with("a status's level is ok, warning or failing")),
            "{bad:?}"
        );
        let report = field("report", FieldType::Json);
        let rows = [
            (number(), json!("12.5"), num(12.5)),
            (number(), json!(true), gone("not a number: \"true\"")),
            (flag(), json!("false"), flagged(false)),
            (flag(), json!(1), gone("not true or false: \"1\"")),
            (text_field(), json!(87), txt("87")),
            (text_field(), json!([1, 2]), txt("[1,2]")),
            (
                report.clone(),
                json!({"cameras": 2}),
                field_value::Value::Json("{\"cameras\":2}".into()),
            ),
        ];
        for (field, found, value) in rows {
            assert_eq!(held(&from_json(&field, &found)), value, "{found}");
        }
        assert_eq!(
            held(&from_text(&report, "{\"a\": 1}")),
            field_value::Value::Json("{\"a\": 1}".into())
        );
    }

    #[test]
    fn the_named_parts_are_the_commands_own_and_the_rest_come_from_its_output() {
        let page = "{\"version\": \"2027.1\", \"status\": \"ok\"}";
        let values = filled(
            &[outcome(), code(), text_field(), body(), why()],
            &answered(503, page),
            1024,
        );
        // An HTTP error is a status like any other, for the pack's limits to judge.
        assert_eq!(
            values,
            [
                txt("completed"),
                num(503.0),
                txt("2027.1"),
                txt(page),
                txt("")
            ]
        );
        // A JSON key with a part's name never fills the part: status is the HTTP status above.
        assert_eq!(
            filled(&[code()], &answered(200, "{\"status\": 7}"), 1024),
            [num(200.0)]
        );
    }

    #[test]
    fn a_runs_exit_code_is_its_and_its_output_fills_the_one_other_field() {
        // systemctl is-active says inactive with exit 3: what it printed is the value, the exit a
        // part.
        assert_eq!(
            filled(
                &[text_field(), exit(), output()],
                &ran(3, "inactive\n", ""),
                1024
            ),
            [txt("inactive"), num(3.0), txt("inactive\n")]
        );
        // Nothing printed, whatever the exit: an empty text, or no number.
        assert_eq!(
            filled(&[text_field()], &ran(2, "", "no such unit"), 1024),
            [txt("")]
        );
        assert_eq!(
            filled(&[number()], &ran(2, "", ""), 1024),
            [gone("not a number: \"\"")]
        );
        // Parts declared alone leave nothing for the output to fill.
        assert_eq!(filled(&[exit()], &ran(0, "a\nb", ""), 1024), [num(0.0)]);
    }

    #[test]
    fn a_command_that_didnt_complete_fills_its_outcome_and_nothing_else() {
        let timed_out = Ran::failed(Kind::Run, Outcome::TimedOut, "timed out after 5s");
        let late = gone("timed out after 5s");
        assert_eq!(
            filled(
                &[outcome(), why(), exit(), output(), text_field()],
                &timed_out,
                1024
            ),
            [
                txt("timedOut"),
                txt("timed out after 5s"),
                late.clone(),
                late.clone(),
                late
            ]
        );
        let refused = Ran::failed(
            Kind::Http,
            Outcome::Unreachable,
            "connection refused (localhost:5800)",
        );
        assert_eq!(
            filled(&[outcome(), code()], &refused, 1024),
            [
                txt("unreachable"),
                gone("connection refused (localhost:5800)")
            ]
        );
        let missing = Ran::failed(Kind::Read, Outcome::CouldNotStart, "no such file: /x");
        assert_eq!(
            filled(&[outcome(), text_field()], &missing, 1024),
            [txt("couldNotStart"), gone("no such file: /x")]
        );
    }

    #[test]
    fn output_past_the_most_kept_leaves_the_calls_own_parts_and_nothing_read_from_it() {
        let more = gone("it printed more than the 2 KiB kept");
        let truncated = completed(Kind::Run, 3, &[0; 2048], true, "");
        // The call completed: its outcome and exit code stand; what was read from its output, the
        // whole of it and its keys, isn't.
        assert_eq!(
            filled(
                &[outcome(), why(), exit(), output(), text_field()],
                &truncated,
                2048
            ),
            [
                txt("completed"),
                txt(""),
                num(3.0),
                more.clone(),
                more.clone()
            ]
        );
        let page = completed(Kind::Http, 200, &[0; 2048], true, "");
        assert_eq!(filled(&[code(), body()], &page, 2048), [num(200.0), more]);
    }

    #[test]
    fn each_kind_has_its_parts() {
        let all = [
            "outcome",
            "outcomeMessage",
            "exit",
            "output",
            "status",
            "body",
        ];
        let rows: [(Kind, &[&str]); 3] = [
            (Kind::Run, &["outcome", "outcomeMessage", "exit", "output"]),
            (Kind::Http, &["outcome", "outcomeMessage", "status", "body"]),
            (Kind::Read, &["outcome", "outcomeMessage"]),
        ];
        for (kind, parts) in rows {
            let has: Vec<&str> = all
                .iter()
                .copied()
                .filter(|name| kind.part(name).is_some())
                .collect();
            assert_eq!(has, parts, "{kind:?}");
        }
        assert_eq!(Command::Read { path: "/x".into() }.kind(), Kind::Read);
        assert_eq!(name(Outcome::Cancelled), "cancelled");
        assert_eq!(name(Outcome::Lost), "lost");
        assert_eq!(name(Outcome::Unspecified), "");
        let json_output = field("output", FieldType::Json);
        assert_eq!(
            filled(&[json_output], &ran(0, "{\"a\": 1}", ""), 1024),
            [field_value::Value::Json("{\"a\": 1}".into())]
        );
    }
}
