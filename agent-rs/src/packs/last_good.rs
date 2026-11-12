//! The last good start, so a pushed pack that crashes the agent, or reboots the board, can't keep
//! the board from serving and taking the next push. Before the agent loads pushed packs, it leaves
//! a marker, [`MARKER`]; once it has been up [`HEALTHY`] it takes it away. A start that finds the
//! marker still there knows a start with those packs didn't stay up, and sets them aside for
//! itself; once it has been up [`HEALTHY`] it takes the marker away, so the next start tries them
//! again. A push takes it away too: new packs get a fresh try.
//!
//! What counts, so that ordinary use doesn't: a start in the same boot counts the last one only if
//! the agent didn't stop cleanly (it crashed, or was killed out of memory), as a restart for a
//! change stops it cleanly and says so in the marker. A start after a reboot or a power cut counts
//! only the second boot in a row whose start didn't stay up.

use std::fs;
use std::time::Duration;

use serde_json::{Value, json};

use super::load::PUSHED;
use crate::board::host::Host;
use crate::board::identity::BOOT_ID;
use crate::board::json;

/// The marker: there while a start with pushed packs hasn't yet stayed up.
pub const MARKER: &str = "/var/lib/frc-spotter/starting";

/// How long a start must stay up to count as good.
pub const HEALTHY: Duration = Duration::from_secs(60);

/// How many boots in a row may start with the pushed packs and not stay up.
const BOOTS: i64 = 2;

/// The marker: the boot its start was in, how many boots in a row a start with the pushed packs
/// hasn't stayed up, and whether its start stopped cleanly.
struct Marker {
    boot: String,
    boots: i64,
    stopped: bool,
}

impl Marker {
    fn read(host: &Host) -> Option<Marker> {
        // Unreadable: a start that didn't finish writing it; counted as one that didn't stay up.
        let unreadable = Marker {
            boot: String::new(),
            boots: BOOTS,
            stopped: false,
        };
        let text = match host.read(MARKER) {
            Ok(Some(text)) => text,
            Ok(None) => return None,
            Err(_) => return Some(unreadable),
        };
        let Ok(members) = json::object(&text) else {
            return Some(unreadable);
        };
        Some(Marker {
            boot: match members.get("boot") {
                Some(Value::String(boot)) => boot.clone(),
                _ => String::new(),
            },
            boots: members.get("boots").and_then(json::whole).unwrap_or(1),
            stopped: members.get("stopped") == Some(&Value::Bool(true)),
        })
    }

    fn write(&self, host: &Host) {
        let text = json!({"boot": self.boot, "boots": self.boots, "stopped": self.stopped});
        if let Err(e) = fs::write(host.path(MARKER), format!("{text}\n")) {
            // Not writable: no push could have put packs there either. They load as they are.
            host.log(&format!(
                "Couldn't leave {MARKER} ({e}): a start that fails isn't noticed"
            ));
        }
    }
}

fn boot(host: &Host) -> String {
    host.line(BOOT_ID).ok().flatten().unwrap_or_default()
}

/// Whether this start may load the board's pushed packs: yes, leaving the marker, unless the last
/// start with them didn't stay up (as counted above), when it sets them aside.
pub fn begin(host: &Host) -> bool {
    let boot = boot(host);
    let mut boots = 1;
    if let Some(marker) = Marker::read(host) {
        let same_boot = !boot.is_empty() && boot == marker.boot;
        if same_boot && !marker.stopped {
            return false;
        }
        boots = if same_boot {
            marker.boots
        } else {
            marker.boots + 1
        };
        if boots > BOOTS {
            return false;
        }
    }
    Marker {
        boot,
        boots,
        stopped: false,
    }
    .write(host);
    true
}

/// The agent stopped cleanly: a start in this boot doesn't count this one as one that didn't stay
/// up.
pub fn stopped(host: &Host) {
    if let Some(marker) = Marker::read(host) {
        if host.path(MARKER).exists() {
            Marker {
                stopped: true,
                ..marker
            }
            .write(host);
        }
    }
}

/// This start stayed up: the next one may load the pushed packs.
pub fn healthy(host: &Host) {
    forget(host, "it stayed up");
}

/// New packs were pushed: the next start tries them.
pub fn pushed(host: &Host) {
    forget(host, "packs were pushed");
}

fn forget(host: &Host, why: &str) {
    match fs::remove_file(host.path(MARKER)) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => host.log(&format!("Couldn't remove {MARKER} as {why}: {e}")),
    }
}

/// Why the pushed packs were set aside, as the board's problems say it.
pub fn set_aside() -> String {
    format!(
        "{PUSHED}: set aside for this start, as the last start with them didn't stay up {} s (the agent crashed or was killed, or the board went down in two boots in a row); the next start tries them again, and a push replaces them",
        HEALTHY.as_secs()
    )
}
