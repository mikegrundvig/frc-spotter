//! A value judged by its limits, as the robot's manager judges it (its `Judge`), for `status` to
//! print each value's level and why: within a level, any comparison that matches triggers it, and
//! `fail` is checked before `warn`. A status's level is its script's own.

use crate::proto::{
    FieldDeclaration, FieldType, FieldValue, Level as Sent, Limit, Scalar, field_value, scalar,
};

/// A value's level, as the manager names them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Level {
    Ok,
    Warning,
    Failing,
    Unavailable,
}

impl Level {
    pub fn name(self) -> &'static str {
        match self {
            Level::Ok => "ok",
            Level::Warning => "warning",
            Level::Failing => "failing",
            Level::Unavailable => "unavailable",
        }
    }
}

/// What a missing rule says when the value is empty text.
const EMPTY: &str = "is empty";

/// A number in words, with its unit: `15 %`, `61.2 °C`.
pub fn number(value: f64, unit: &str) -> String {
    let number = if value == value.round() && value.abs() < 1e15 {
        format!("{}", value as i64)
    } else {
        java_double(value)
    };
    if unit.is_empty() {
        number
    } else {
        format!("{number} {unit}")
    }
}

/// A double as Java's `Double.toString` writes the common ones: `12.5`, `0.001`, `1.0E-4`.
fn java_double(value: f64) -> String {
    let magnitude = value.abs();
    if magnitude != 0.0 && !(1e-3..1e7).contains(&magnitude) {
        let text = format!("{value:E}");
        let (mantissa, exponent) = text.split_once('E').unwrap_or((&text, "0"));
        let mantissa = if mantissa.contains('.') {
            mantissa.to_string()
        } else {
            format!("{mantissa}.0")
        };
        return format!("{mantissa}E{exponent}");
    }
    let text = format!("{value}");
    if text.contains('.') || !value.is_finite() {
        text
    } else {
        format!("{text}.0")
    }
}

fn scalar_words(scalar: &Scalar, unit: &str) -> String {
    match &scalar.value {
        Some(scalar::Value::Number(n)) => number(*n, unit),
        Some(scalar::Value::Flag(flag)) => flag.to_string(),
        Some(scalar::Value::Text(text)) => text.clone(),
        None => String::new(),
    }
}

/// A value's level by its declaration's limits, and why: the comparison that matched, a status's
/// message, or why it has none; empty when it's ok.
pub fn judge(declaration: &FieldDeclaration, value: &FieldValue) -> (Level, String) {
    let reading = Reading::of(value);
    if let Reading::Status(level, message) = &reading
        && *level != Level::Unavailable
    {
        return (*level, message.clone());
    }
    let unit = &declaration.unit;
    if let Some(why) = declaration
        .fail
        .as_ref()
        .and_then(|limit| matched(limit, unit, &reading))
    {
        return (Level::Failing, why);
    }
    if let Some(why) = declaration
        .warn
        .as_ref()
        .and_then(|limit| matched(limit, unit, &reading))
    {
        return (Level::Warning, why);
    }
    match &reading {
        Reading::Unavailable(why) => (Level::Unavailable, why.clone()),
        Reading::Status(_, message) => (Level::Unavailable, message.clone()),
        _ => (Level::Ok, String::new()),
    }
}

/// What a value holds, as the manager reads it.
pub enum Reading {
    Number(f64),
    Flag(bool),
    Text(String),
    Status(Level, String),
    Unavailable(String),
}

impl Reading {
    pub fn of(value: &FieldValue) -> Reading {
        match &value.value {
            Some(field_value::Value::Number(n)) => Reading::Number(*n),
            Some(field_value::Value::Flag(flag)) => Reading::Flag(*flag),
            Some(field_value::Value::Text(text))
            | Some(field_value::Value::Json(text))
            | Some(field_value::Value::FileUrl(text)) => Reading::Text(text.clone()),
            Some(field_value::Value::Status(status)) => {
                let level = match Sent::try_from(status.level) {
                    Ok(Sent::Ok) => Level::Ok,
                    Ok(Sent::Warning) => Level::Warning,
                    Ok(Sent::Failing) => Level::Failing,
                    _ => Level::Unavailable,
                };
                Reading::Status(level, status.message.clone())
            }
            Some(field_value::Value::Unavailable(why)) => Reading::Unavailable(why.clone()),
            None => Reading::Unavailable("sent without a value".to_string()),
        }
    }
}

/// What a level's comparison that matches says, or none when none does.
fn matched(limit: &Limit, unit: &str, reading: &Reading) -> Option<String> {
    match reading {
        Reading::Unavailable(why) | Reading::Status(_, why) => limit.missing.then(|| why.clone()),
        Reading::Number(n) => {
            if let Some(above) = limit.above.filter(|above| n > above) {
                return Some(format!("above {}", number(above, unit)));
            }
            if let Some(below) = limit.below.filter(|below| n < below) {
                return Some(format!("below {}", number(below, unit)));
            }
            if let Some(Scalar {
                value: Some(scalar::Value::Number(equals)),
            }) = &limit.equals
                && n == equals
            {
                return Some(format!("is {}", number(*equals, unit)));
            }
            if let Some(Scalar {
                value: Some(scalar::Value::Number(other)),
            }) = &limit.not_equals
                && n != other
            {
                return Some(format!("isn't {}", number(*other, unit)));
            }
            None
        }
        Reading::Flag(flag) => {
            if let Some(Scalar {
                value: Some(scalar::Value::Flag(equals)),
            }) = &limit.equals
                && flag == equals
            {
                return Some(format!("is {equals}"));
            }
            if let Some(Scalar {
                value: Some(scalar::Value::Flag(other)),
            }) = &limit.not_equals
                && flag != other
            {
                return Some(format!("isn't {other}"));
            }
            None
        }
        Reading::Text(text) => {
            if limit.missing && text.is_empty() {
                return Some(EMPTY.to_string());
            }
            if let Some(Scalar {
                value: Some(scalar::Value::Text(equals)),
            }) = &limit.equals
                && text == equals
            {
                return Some(format!("is {}", scalar_words(limit.equals.as_ref()?, unit)));
            }
            if let Some(Scalar {
                value: Some(scalar::Value::Text(other)),
            }) = &limit.not_equals
                && text != other
            {
                return Some(format!(
                    "isn't {}",
                    scalar_words(limit.not_equals.as_ref()?, unit)
                ));
            }
            None
        }
    }
}

/// Whether a value's type is a number's.
pub fn is_number(declaration: &FieldDeclaration) -> bool {
    declaration.r#type == FieldType::Number as i32
}
