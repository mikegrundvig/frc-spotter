//! What a page of a log asks for: where from, the cursor to page from, how many, and the least
//! important level, as a request's query asks for it, checked. It reaches a log's command as
//! `SPOTTER_*` variables, which are anyone's: so each is only what it can be.

use std::collections::HashMap;

use super::lines;
use crate::proto::LogLevel;

/// A page's entries unless asked, and at most.
const DEFAULT_LIMIT: usize = 100;
const MAX_LIMIT: usize = 1000;

/// The longest cursor taken: a journal's is about 100 characters.
const MAX_CURSOR: usize = 1024;

/// A request that can't be answered as asked, with its status.
#[derive(Debug)]
pub struct Refused {
    pub status: u16,
    pub reason: String,
}

impl Refused {
    pub fn new(status: u16, reason: impl Into<String>) -> Refused {
        Refused {
            status,
            reason: reason.into(),
        }
    }
}

/// Where a page starts.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum From {
    Latest,
    Before,
    After,
}

impl From {
    fn name(self) -> &'static str {
        match self {
            From::Latest => "latest",
            From::Before => "before",
            From::After => "after",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Paging {
    pub from: From,
    /// The cursor to page from, for `before` and `after`; empty otherwise.
    pub cursor: String,
    /// The most entries wanted.
    pub limit: usize,
    /// The least important level wanted.
    pub level: LogLevel,
}

impl Paging {
    /// Paging as a request's query asks for it, checked.
    pub fn of(query: &HashMap<String, String>) -> Result<Paging, Refused> {
        let asked = |key: &str| query.get(key).map(String::as_str).unwrap_or("");
        let from = match query.get("from").map(String::as_str).unwrap_or("latest") {
            "latest" => From::Latest,
            "before" => From::Before,
            "after" => From::After,
            other => {
                return Err(Refused::new(
                    400,
                    format!("from is latest, before or after, not {other}"),
                ));
            }
        };
        let cursor = asked("cursor");
        if from != From::Latest && cursor.is_empty() {
            return Err(Refused::new(
                400,
                format!("from={} pages from a cursor: give cursor=", from.name()),
            ));
        }
        if !is_cursor(cursor) {
            // It reaches a log's command as SPOTTER_CURSOR: anyone's, so only what a cursor is.
            return Err(Refused::new(
                400,
                format!(
                    "a cursor is printable ASCII, with no spaces, at most {MAX_CURSOR} characters"
                ),
            ));
        }
        let mut limit = DEFAULT_LIMIT;
        let given = asked("limit");
        if !given.is_empty() {
            limit = given
                .parse::<i64>()
                .ok()
                .filter(|n| (1..=MAX_LIMIT as i64).contains(n))
                .map(|n| n as usize)
                .ok_or_else(|| {
                    Refused::new(400, format!("limit is 1 to {MAX_LIMIT}, not {given}"))
                })?;
        }
        let mut level = LogLevel::Debug;
        let named = asked("level");
        if !named.is_empty() {
            level = lines::level_of(named);
            if level == LogLevel::Unspecified {
                return Err(Refused::new(
                    400,
                    format!("level is error, warning, info, debug, or 0 to 7, not {named}"),
                ));
            }
        }
        let cursor = if from == From::Latest {
            String::new()
        } else {
            cursor.to_string()
        };
        Ok(Paging {
            from,
            cursor,
            limit,
            level,
        })
    }

    /// What the log's command is told.
    pub fn environment(&self) -> Vec<(String, String)> {
        vec![
            ("SPOTTER_FROM".into(), self.from.name().into()),
            ("SPOTTER_CURSOR".into(), self.cursor.clone()),
            ("SPOTTER_LIMIT".into(), self.limit.to_string()),
            ("SPOTTER_LEVEL".into(), lines::name(self.level).into()),
        ]
    }
}

/// Whether text is a cursor: printable ASCII, no space, at most [`MAX_CURSOR`].
fn is_cursor(text: &str) -> bool {
    text.len() <= MAX_CURSOR && text.bytes().all(|c| c > b' ' && c <= b'~')
}
