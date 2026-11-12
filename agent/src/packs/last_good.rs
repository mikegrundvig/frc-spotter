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
    if let Some(marker) = Marker::read(host)
        && host.path(MARKER).exists()
    {
        Marker {
            stopped: true,
            ..marker
        }
        .write(host);
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

#[cfg(test)]
mod tests {
    use prost::Message;

    use super::*;
    use crate::configuration::Configuration;
    use crate::packs::push;
    use crate::protocol::pack_hash;
    use crate::testing::{Fixture, files};

    /// A board with pushed packs, its controller named by its team: only then does it take pushes,
    /// and load pushed packs.
    fn board() -> Fixture {
        let fixture = Fixture::new();
        fixture.config("{\"team\": 1234}");
        fixture.pushed("team", "pack: team\n");
        fixture
    }

    fn marked(fixture: &Fixture) -> bool {
        fixture.path(MARKER).exists()
    }

    fn loads(fixture: &Fixture) -> usize {
        fixture.configuration().packs.packs.len()
    }

    fn new_boot(fixture: &Fixture, id: u8) {
        fixture.write(
            BOOT_ID,
            &format!("3c1e6a2e-0000-4000-8000-00000000000{id}\n"),
        );
    }

    #[test]
    fn a_start_with_pushed_packs_leaves_its_marker_until_its_stayed_up() {
        let fixture = board();
        let first = fixture.configuration();
        let names: Vec<&str> = first.packs.packs.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["team"]);
        assert!(first.problems.is_empty(), "{:?}", first.problems);
        assert!(marked(&fixture));
        healthy(&fixture.host);
        assert!(!marked(&fixture));
        assert_eq!(loads(&fixture), 1);
    }

    #[test]
    fn a_start_after_one_that_didnt_stay_up_sets_its_pushed_packs_aside_and_still_takes_pushes() {
        let fixture = board();
        // The first start loads them, and never stays up: it crashes, or is killed out of memory.
        fixture.configuration();
        let second: Configuration = fixture.configuration();
        assert!(second.packs.packs.is_empty());
        assert_eq!(second.problems, [set_aside()]);
        // What's on its disk is still what it says it has, so it isn't pushed the same again.
        let on_disk = pack_hash::of_folder(&fixture.path(PUSHED)).expect("its hash");
        assert!(!on_disk.is_empty());
        assert_eq!(second.packs.pushed_hash, on_disk);
        assert!(second.accepts_pushes());

        // That start stays up: the next one tries them again.
        healthy(&fixture.host);
        assert_eq!(loads(&fixture), 1);
    }

    #[test]
    fn a_push_gives_its_packs_a_fresh_try() {
        let fixture = board();
        fixture.configuration();
        assert!(marked(&fixture));
        let body = push::bundle_file(&fixture.host).expect("a file for the bundle");
        let bundle = files(&[("team/pack.yaml", "pack: team\nversion: 2.0.0\n")]);
        fs::write(&body, bundle.encode_to_vec()).expect("the bundle");
        push::apply(&fixture.host, &body, "1").expect("pushed");
        assert!(!marked(&fixture));
        let versions: Vec<String> = fixture
            .configuration()
            .packs
            .packs
            .iter()
            .map(|p| p.version.clone())
            .collect();
        assert_eq!(versions, ["2.0.0"]);
    }

    #[test]
    fn a_board_without_pushed_packs_or_refusing_them_leaves_no_marker() {
        let none = Fixture::new();
        none.pack("vision", "pack: vision\n");
        none.configuration();
        assert!(!marked(&none));

        let refusing = board();
        refusing.config("{\"acceptPushes\": false}");
        refusing.configuration();
        assert!(!marked(&refusing));
    }

    #[tokio::test]
    async fn an_agent_counts_its_start_good_once_its_been_up() {
        let fixture = board();
        let agent = fixture.agent(None);
        assert!(marked(&fixture));
        let collectors = agent.collectors.start();
        // Not yet: a minute from now, on the agent's own timer, which a test doesn't wait for.
        assert!(marked(&fixture));
        assert_eq!(HEALTHY, Duration::from_secs(60));
        for each in collectors {
            each.abort();
        }
    }

    #[test]
    fn a_clean_stop_isnt_a_start_that_didnt_stay_up() {
        let fixture = board();
        fixture.configuration();
        // Restarted for a change, a moment later: systemd stops it, and its hook says so.
        stopped(&fixture.host);
        assert_eq!(loads(&fixture), 1);
        stopped(&fixture.host);
        assert_eq!(loads(&fixture), 1);
    }

    #[test]
    fn a_boot_counts_only_the_second_in_a_row_whose_start_didnt_stay_up() {
        let fixture = board();
        fixture.configuration();
        // Switched off within the minute, as a robot often is: the next boot loads them.
        new_boot(&fixture, 2);
        assert_eq!(loads(&fixture), 1);
        // And again, within the minute: two boots in a row, as a pack that reboots the board makes.
        new_boot(&fixture, 3);
        let third = fixture.configuration();
        assert!(third.packs.packs.is_empty());
        assert_eq!(third.problems, [set_aside()]);
        // A boot that stays up starts afresh.
        healthy(&fixture.host);
        new_boot(&fixture, 4);
        assert_eq!(loads(&fixture), 1);
    }
}
