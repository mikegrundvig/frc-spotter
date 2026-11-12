//! What the agent writes to its log (its journal, on a board): each line with its control
//! characters escaped and cut at [`MAX_LINE`], so what it quotes can't forge a line; and a log that
//! writes at most one line a period, so a flood of refused requests can't flood the journal.

use std::sync::Mutex;

/// The most of a line written to the agent's log.
pub const MAX_LINE: usize = 2000;

/// Text with its control characters escaped (`\n`, `\u0000`), cut at [`MAX_LINE`].
pub fn escaped(text: &str) -> String {
    let mut line = String::with_capacity(text.len().min(MAX_LINE) + 16);
    let mut written = 0usize;
    let mut total = 0usize;
    for c in text.chars() {
        total += 1;
        if written >= MAX_LINE {
            continue;
        }
        let code = c as u32;
        let escape = match c {
            '\n' => Some("\\n".to_string()),
            '\r' => Some("\\r".to_string()),
            '\t' => Some("\\t".to_string()),
            _ if code < 0x20 || code == 0x7f || (0x80..0xa0).contains(&code) => {
                Some(format!("\\u{code:04x}"))
            }
            _ => None,
        };
        match escape {
            Some(escape) => {
                written += escape.len();
                line.push_str(&escape);
            }
            None => {
                written += 1;
                line.push(c);
            }
        }
    }
    if written >= MAX_LINE && total > MAX_LINE {
        line = line.chars().take(MAX_LINE).collect();
        line.push_str("...");
    }
    line
}

/// A log that writes at most one line a period: one for the first event, then one a period for
/// those since, counted.
pub struct RateLimited {
    period_micros: u64,
    state: Mutex<Held>,
}

struct Held {
    last_micros: Option<u64>,
    held: u32,
}

impl RateLimited {
    pub fn new(period_micros: u64) -> RateLimited {
        RateLimited {
            period_micros,
            state: Mutex::new(Held {
                last_micros: None,
                held: 0,
            }),
        }
    }

    /// The line to write now for this message, or none when one was written within the period
    /// (then it's counted for the next).
    pub fn line(&self, message: &str, now_micros: u64) -> Option<String> {
        let mut state = self.state.lock().expect("the log's lock");
        if let Some(last) = state.last_micros
            && now_micros.saturating_sub(last) < self.period_micros
        {
            state.held += 1;
            return None;
        }
        let line = if state.held == 0 {
            message.to_string()
        } else {
            format!("{message} (and {} more like it before)", state.held)
        };
        state.held = 0;
        state.last_micros = Some(now_micros);
        Some(line)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::Fixture;

    #[test]
    fn what_the_agent_logs_has_its_control_characters_escaped_and_is_cut() {
        assert_eq!(escaped("a\nb\r\u{0}c\td"), "a\\nb\\r\\u0000c\\td");
        let cut = escaped(&"x".repeat(MAX_LINE + 10));
        assert_eq!(cut.len(), MAX_LINE + 3);
        assert!(cut.ends_with("..."));
        let fixture = Fixture::new();
        fixture.host.log("one\n<2>two");
        assert!(fixture.log().contains("one\\n<2>two"));
    }
}
