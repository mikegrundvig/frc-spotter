//! A collector's file read: off the agent's thread, so a file that never answers (a wedged device's
//! in /sys) is given up on at its timeout, and the agent runs on.

use std::fs;
use std::io::{self, Read};
use std::time::Duration;

use super::Ran;
use crate::board::host::Host;
use crate::packs::command::Kind;
use crate::packs::duration;
use crate::proto::Outcome;

pub async fn run(host: &Host, path: &str, timeout: Duration, max_output: usize) -> Ran {
    let file = host.path(path);
    let read = tokio::task::spawn_blocking(move || -> io::Result<Vec<u8>> {
        let mut bytes = Vec::new();
        fs::File::open(file)?
            .take(max_output as u64 + 1)
            .read_to_end(&mut bytes)?;
        Ok(bytes)
    });
    let failed = |message: String| Ran::failed(Kind::Read, Outcome::CouldNotStart, message);
    match tokio::time::timeout(timeout.max(Duration::from_millis(1)), read).await {
        Err(_) => Ran::failed(
            Kind::Read,
            Outcome::TimedOut,
            format!("timed out after {}", duration::show(timeout)),
        ),
        Ok(Err(e)) => failed(format!("{path}: {e}")),
        Ok(Ok(Err(e))) => match e.kind() {
            io::ErrorKind::NotFound => failed(format!("no such file: {path}")),
            io::ErrorKind::PermissionDenied => failed(format!("not allowed: {path}")),
            _ => failed(format!("{path}: {e}")),
        },
        Ok(Ok(Ok(mut bytes))) => {
            let truncated = bytes.len() > max_output;
            bytes.truncate(max_output);
            Ran {
                kind: Kind::Read,
                outcome: Outcome::Completed,
                message: String::new(),
                code: 0,
                output: bytes,
                truncated,
                errors: String::new(),
                ended: None,
            }
        }
    }
}
