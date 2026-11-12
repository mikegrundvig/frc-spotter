//! A log's lines as entries: each a JSON object, its parts under the keys its pack's `map` names
//! (`journalctl -o json` is a log as it stands). An entry's time is ISO 8601 text, or a number with
//! the unit the map declares; its level `error`, `warning`, `info`, `debug`, or syslog's 0 to 7
//! read as those. A line that isn't JSON is a message on its own, at `info`.

use serde_json::Value;

use crate::board::json;
use crate::packs::pack::LogMap;
use crate::packs::reader::is_number;
use crate::proto::{LogEntry, LogLevel};

/// The longest message kept; a longer one is cut.
const MAX_MESSAGE: usize = 4096;

/// An entry from a line; none when the line is blank. `now_micros` is the time an entry without
/// one gets: when a run's line was read; 0 for none.
pub fn entry(line: &str, map: &LogMap, now_micros: i64) -> Option<LogEntry> {
    if line.trim().is_empty() {
        return None;
    }
    let Some(keys) = json::as_object(line) else {
        return Some(LogEntry {
            time_micros: now_micros,
            level: LogLevel::Info as i32,
            message: cut(line.trim()),
            ..LogEntry::default()
        });
    };
    let micros = micros(keys.get(&map.time), &map.time_unit);
    Some(LogEntry {
        time_micros: if micros != 0 { micros } else { now_micros },
        level: level(keys.get(&map.level)) as i32,
        source: text(keys.get(&map.source)),
        message: cut(&text(keys.get(&map.message))),
        cursor: text(keys.get(&map.cursor)),
    })
}

/// A level as a log names it; unspecified when it isn't one.
fn level(value: Option<&Value>) -> LogLevel {
    match value {
        None | Some(Value::Null) => LogLevel::Unspecified,
        Some(given) => level_of(json::text(given).trim()),
    }
}

/// A level as a log or a request names it: a name, or syslog's 0 to 7.
pub fn level_of(text: &str) -> LogLevel {
    match text.to_lowercase().as_str() {
        "error" | "0" | "1" | "2" | "3" => LogLevel::Error,
        "warning" | "4" => LogLevel::Warning,
        "info" | "5" | "6" => LogLevel::Info,
        "debug" | "7" => LogLevel::Debug,
        _ => LogLevel::Unspecified,
    }
}

/// A level's name, as `SPOTTER_LEVEL` gives it.
pub fn name(level: LogLevel) -> &'static str {
    match level {
        LogLevel::Error => "error",
        LogLevel::Warning => "warning",
        LogLevel::Info => "info",
        _ => "debug",
    }
}

/// Whether an entry's level is as important as the least wanted, or has none.
pub fn important(level: i32, least: LogLevel) -> bool {
    level == LogLevel::Unspecified as i32 || level <= least as i32
}

/// A time in microseconds since the epoch: a number (or a number's text, as journalctl writes
/// them) in the declared unit, or ISO 8601 text; 0 when it's neither.
fn micros(value: Option<&Value>, unit: &str) -> i64 {
    let Some(given) = value.filter(|v| !v.is_null()) else {
        return 0;
    };
    let text = json::text(given);
    let text = text.trim();
    if !unit.is_empty() {
        if !is_number(text) {
            return 0;
        }
        let Ok(number) = text.parse::<f64>() else {
            return 0;
        };
        let micros = match unit {
            "ns" => number / 1000.0,
            "ms" => number * 1000.0,
            "s" => number * 1_000_000.0,
            _ => number,
        };
        return micros.round() as i64;
    }
    match time::OffsetDateTime::parse(text, &time::format_description::well_known::Rfc3339) {
        Ok(instant) => (instant.unix_timestamp_nanos() / 1000) as i64,
        Err(_) => 0,
    }
}

fn text(value: Option<&Value>) -> String {
    match value {
        None | Some(Value::Null) => String::new(),
        Some(given) => json::text(given),
    }
}

fn cut(text: &str) -> String {
    if text.encode_utf16().count() <= MAX_MESSAGE {
        text.to_string()
    } else {
        text.chars().take(MAX_MESSAGE).collect()
    }
}
