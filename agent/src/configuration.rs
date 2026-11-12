//! What this board's agent runs, read once as it starts: its settings, if it has any, and its
//! packs. It needs neither: with no packs, it's still a valid agent, reporting its identity,
//! sending heartbeats, and offering power-off and reboot.
//!
//! Settings that are there but can't be read fail closed: a board whose owner meant it to refuse
//! pushes, or to require signatures, mustn't take writes because a typo dropped the file. So every
//! write is refused (and pushed packs ignored) until the file is fixed; reading works as ever.

use crate::board::config::{self, AgentConfig};
use crate::board::host::Host;
use crate::packs::last_good;
use crate::packs::load::{self, Loaded, PUSHED};

#[derive(Clone, Debug)]
pub struct Configuration {
    /// Its settings; the defaults when it has none, or they can't be read.
    pub config: AgentConfig,
    pub packs: Loaded,
    /// What it ignored, and why: its settings', then its packs'.
    pub problems: Vec<String>,
    /// Why its settings are there and can't be read, which refuses every write; empty when
    /// they're fine, or not there.
    pub unreadable: String,
    /// Whether the robot controller is named (on the command line, or by its address or its team
    /// in the settings), not only worked out from the board's own address: only a named one may
    /// push packs, or run more than the built-in actions.
    pub controller_named: bool,
}

impl Configuration {
    /// Reads this board's settings and packs; `command_line` is whether the command line names the
    /// controller.
    pub fn read(host: &Host, command_line: bool) -> Configuration {
        let mut problems = Vec::new();
        let mut settings = AgentConfig::default();
        let mut unreadable = String::new();
        let read = host
            .read(config::PATH)
            .map_err(|e| e.to_string())
            .and_then(|text| text.map(|text| AgentConfig::parse(&text)).transpose());
        match read {
            Ok(Some(parsed)) => settings = parsed,
            Ok(None) => {}
            Err(why) => {
                unreadable = format!("{} can't be read: {why}", config::PATH);
                problems.push(format!(
                    "{}: can't be read, so every write is refused: {why}",
                    config::PATH
                ));
            }
        }
        let named = command_line || !settings.named_controller().is_empty();
        let refused = refused(&unreadable, &settings, named);
        // The last good start: pushed packs are loaded behind a marker, and set aside for a start
        // that finds the last one with them didn't stay up.
        let set_aside = refused.is_empty()
            && host.list(PUSHED).is_ok_and(|names| !names.is_empty())
            && !last_good::begin(host);
        let packs = load::load(host, &refused, set_aside);
        problems.extend(packs.problems.iter().cloned());
        Configuration {
            config: settings,
            packs,
            problems,
            unreadable,
            controller_named: named,
        }
    }

    /// Whether the board takes pushes: its settings say so, can be read, and name the controller.
    pub fn accepts_pushes(&self) -> bool {
        self.pushes_refused().is_empty()
    }

    /// Why the board refuses pushes; empty when it takes them.
    pub fn pushes_refused(&self) -> String {
        refused(&self.unreadable, &self.config, self.controller_named)
    }
}

/// Why a board refuses pushes, as its problems and its 403 say; empty when it takes them.
fn refused(unreadable: &str, config: &AgentConfig, named: bool) -> String {
    if !unreadable.is_empty() {
        return "its agent.json can't be read".to_string();
    }
    if !config.accept_pushes {
        return "its agent.json says acceptPushes: false".to_string();
    }
    if !named {
        return "its agent.json names no controller or team, and pushes are taken from a named one only"
            .to_string();
    }
    String::new()
}
