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

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    /// journalctl -o json's names, as the debian pack maps them.
    fn journal() -> LogMap {
        LogMap {
            time: "__REALTIME_TIMESTAMP".into(),
            time_unit: "us".into(),
            level: "PRIORITY".into(),
            source: "SYSLOG_IDENTIFIER".into(),
            message: "MESSAGE".into(),
            cursor: "__CURSOR".into(),
        }
    }

    #[test]
    fn journal_lines_are_entries_by_their_map() {
        let line = "{\"__REALTIME_TIMESTAMP\": \"1759406400123456\", \"PRIORITY\": \"3\", \"SYSLOG_IDENTIFIER\": \"detector\", \"MESSAGE\": \"camera lost\", \"__CURSOR\": \"s=abc;i=1\"}";
        let entry = entry(line, &journal(), 0).expect("an entry");
        assert_eq!(
            entry,
            LogEntry {
                time_micros: 1_759_406_400_123_456,
                level: LogLevel::Error as i32,
                source: "detector".into(),
                message: "camera lost".into(),
                cursor: "s=abc;i=1".into(),
            }
        );
    }

    #[test]
    fn levels_are_names_or_syslogs_numbers() {
        let rows = [
            (json!("error"), LogLevel::Error),
            (json!(0), LogLevel::Error),
            (json!("2"), LogLevel::Error),
            (json!("WARNING"), LogLevel::Warning),
            (json!("4"), LogLevel::Warning),
            (json!("5"), LogLevel::Info),
            (json!("info"), LogLevel::Info),
            (json!(7), LogLevel::Debug),
            (json!("loud"), LogLevel::Unspecified),
        ];
        for (given, wanted) in rows {
            assert_eq!(level(Some(&given)), wanted, "{given}");
        }
        assert_eq!(level(None), LogLevel::Unspecified);
        assert_eq!(name(LogLevel::Warning), "warning");
        assert_eq!(name(LogLevel::Unspecified), "debug");
    }

    #[test]
    fn times_are_iso_8601_or_a_number_in_the_declared_unit() {
        let rows = [
            (json!("2026-10-02T12:00:01.5Z"), "", 1_790_942_401_500_000),
            (
                json!("2026-10-02T08:00:01-04:00"),
                "",
                1_790_942_401_000_000,
            ),
            (json!(1_759_406_401_i64), "s", 1_759_406_401_000_000),
            (json!("1759406401000"), "ms", 1_759_406_401_000_000),
            (
                json!(1_759_406_401_000_000_000_i64),
                "ns",
                1_759_406_401_000_000,
            ),
            // A number without a unit, or text that's neither: no time.
            (json!(1_759_406_401_i64), "", 0),
            (json!("yesterday"), "", 0),
            (json!("soon"), "us", 0),
        ];
        for (given, unit, wanted) in rows {
            assert_eq!(micros(Some(&given), unit), wanted, "{given} {unit}");
        }
        assert_eq!(micros(None, "us"), 0);
    }

    #[test]
    fn a_plain_line_is_a_message_at_info() {
        let map = LogMap::default();
        let plain = entry("  step 2 of 3  ", &map, 42).expect("an entry");
        assert_eq!(plain.message, "step 2 of 3");
        assert_eq!(plain.level, LogLevel::Info as i32);
        assert_eq!(plain.time_micros, 42);
        assert_eq!(entry("   ", &map, 42), None);
        let json = entry(
            "{\"level\": \"warning\", \"message\": {\"a\": 1}}",
            &map,
            42,
        )
        .expect("an entry");
        assert_eq!(json.level, LogLevel::Warning as i32);
        assert_eq!(json.message, "{\"a\":1}");
        assert_eq!(json.time_micros, 42);
        let long = format!("{{\"message\": \"{}\"}}", "x".repeat(5000));
        let cut = entry(&long, &map, 0).expect("an entry");
        assert_eq!(cut.message.len(), MAX_MESSAGE);
    }
}
