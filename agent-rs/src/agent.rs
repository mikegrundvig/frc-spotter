//! The agent: a runner of what its packs declare, measuring nothing itself. It describes the board
//! (its identity, packs, values, logs and actions), keeps the values its collectors fill, runs its
//! actions, pages its logs, takes pushed packs, and knows who may change the board: the robot
//! controller, signing its writes if the board requires it.

use std::collections::BTreeSet;
use std::sync::{Arc, Mutex};

use crate::actions::runs::{self, Runs};
use crate::board::config;
use crate::board::host::Host;
use crate::board::identity;
use crate::commands::Commands;
use crate::configuration::Configuration;
use crate::logs::{self, Logs};
use crate::packs::pack;
use crate::proto::{Description, Identity, Values};
use crate::protocol;
use crate::server::describer;
use crate::server::events::Events;
use crate::server::signatures::Signatures;
use crate::values::collectors::Collectors;
use crate::values::store::Store;

/// How often the description is read afresh: the board's addresses may change as it runs.
pub const DESCRIPTION_NANOS: u64 = 10_000_000_000;

/// The one address a write is taken from, or why there's none.
#[derive(Clone, Debug, PartialEq)]
pub struct Controller {
    /// The address; empty when there's none, and none is taken.
    pub address: String,
    /// Where the address came from, or why there's none.
    pub why: String,
    /// Whether it's named (on the command line, or in the settings), rather than worked out from
    /// the board's own address: only a named one may push packs, or run more than the built-in
    /// actions.
    pub named: bool,
}

/// What ends the agent for systemd to start it again: after a push.
pub type Exit = Box<dyn Fn() + Send + Sync>;

pub struct Agent {
    pub host: Arc<Host>,
    pub configuration: Configuration,
    version: String,
    pub events: Arc<Events>,
    pub store: Arc<Store>,
    pub commands: Arc<Commands>,
    pub collectors: Arc<Collectors>,
    pub runs: Arc<Runs>,
    pub logs: Logs,
    pub signatures: Signatures,
    controller_override: Option<String>,
    exit: Exit,
    described: Mutex<Option<(Description, u64)>>,
}

impl Agent {
    /// An agent on a board: `controller_override` is the one address a write is taken from, given
    /// on the command line; none to take the settings', or else the robot controller's on the
    /// board's own network.
    pub fn new(
        host: Arc<Host>,
        configuration: Configuration,
        version: &str,
        controller_override: Option<String>,
        exit: Exit,
    ) -> Arc<Agent> {
        let packs = configuration.packs.packs.clone();
        let events = Arc::new(Events::default());
        let told = events.clone();
        let store = Arc::new(Store::new(
            pack::values(&packs),
            Box::new(move || told.values_changed()),
        ));
        let commands = Arc::new(Commands::new(host.clone()));
        let collectors = Arc::new(Collectors::new(
            host.clone(),
            &packs,
            commands.clone(),
            store.clone(),
        ));
        let told = events.clone();
        let runs = Arc::new(Runs::new(
            host.clone(),
            commands.clone(),
            Arc::new(move |event| told.run(event)),
            runs::declared(&packs),
            runs::MAX_KEPT,
        ));
        let logs = Logs::new(commands.clone(), &packs, logs::TIMEOUT);
        let signatures = Signatures::new(&configuration.config.trusted_keys);
        Arc::new(Agent {
            host,
            configuration,
            version: version.to_string(),
            events,
            store,
            commands,
            collectors,
            runs,
            logs,
            signatures,
            controller_override,
            exit,
            described: Mutex::new(None),
        })
    }

    /// The monotonic clock, nanoseconds since boot: what values and events are timed by.
    pub fn nanos(&self) -> u64 {
        self.host.nanos()
    }

    /// Writes a line to the agent's log.
    pub fn log(&self, message: &str) {
        self.host.log(message);
    }

    /// Ends the agent, for systemd to start it again: after a push.
    pub fn exit(&self) {
        (self.exit)();
    }

    /// The board's description: read afresh at most every [`DESCRIPTION_NANOS`], so an address the
    /// board gains as it runs shows, with a new revision.
    pub fn description(&self) -> Description {
        let now = self.host.nanos();
        let mut described = self.described.lock().expect("the description's lock");
        if let Some((last, at)) = described.as_ref() {
            if now.saturating_sub(*at) < DESCRIPTION_NANOS {
                return last.clone();
            }
        }
        let read = match identity::read(&self.host) {
            Ok(read) => read,
            Err(e) => {
                self.host
                    .log(&format!("Couldn't read this board's identity: {e}"));
                described
                    .as_ref()
                    .and_then(|(last, _)| last.identity.clone())
                    .unwrap_or_else(Identity::default)
            }
        };
        let fresh = describer::describe(&self.version, &self.configuration, read);
        *described = Some((fresh.clone(), now));
        fresh
    }

    /// The description's revision, as [`Agent::description`] would answer it.
    pub fn revision(&self) -> u32 {
        self.description().revision
    }

    /// Every value now, complete, with the revision of the description its indexes belong to.
    pub fn values(&self) -> Values {
        self.store
            .delta(None, self.revision(), self.host.nanos())
            .values
    }

    /// The one address a write is taken from: the command line's, else the settings', else the
    /// robot controller's (10.TE.AM.2) on the network of the board's own 10.TE.AM.x address, as its
    /// addresses are now. None when it has no 10.x address, or has them on more than one such
    /// network, since then it can't tell which robot it's on.
    pub fn controller(&self) -> Controller {
        if let Some(address) = &self.controller_override {
            return Controller {
                address: address.clone(),
                why: "named on the command line".into(),
                named: true,
            };
        }
        let named = self.configuration.config.named_controller();
        if !named.is_empty() {
            return Controller {
                address: named,
                why: format!("named in {}", config::PATH),
                named: true,
            };
        }
        let mut networks = BTreeSet::new();
        for address in self.host.addresses().unwrap_or_default() {
            if let Some(network) = robot_network(&address) {
                networks.insert(network);
            }
        }
        let none = |why: String| Controller {
            address: String::new(),
            why,
            named: false,
        };
        match networks.len() {
            0 => none(format!(
                "this computer has no 10.TE.AM.x address to find the robot controller (10.TE.AM.{}) by; name it, or the team, in {}",
                protocol::CONTROLLER,
                config::PATH
            )),
            1 => Controller {
                address: format!(
                    "{}.{}",
                    networks.first().expect("one network"),
                    protocol::CONTROLLER
                ),
                why: "the robot controller on its own network".into(),
                named: false,
            },
            _ => none(format!(
                "this computer has addresses on more than one 10.x network ({}), so which robot controller it answers to isn't clear; name it, or the team, in {}",
                networks
                    .iter()
                    .map(|n| format!("{n}.x"))
                    .collect::<Vec<_>>()
                    .join(", "),
                config::PATH
            )),
        }
    }

    /// The names this computer answers to: its hostname, and its hostname in `.local`.
    pub fn names(&self) -> Vec<String> {
        let name = identity::hostname(&self.host)
            .unwrap_or_default()
            .to_lowercase();
        if name.is_empty() {
            Vec::new()
        } else {
            vec![format!("{name}.local"), name]
        }
    }
}

/// An address on a robot's network, 10.TE.AM.x: its first three numbers.
fn robot_network(address: &str) -> Option<String> {
    let parts: Vec<&str> = address.split('.').collect();
    let number =
        |part: &str| (1..=3).contains(&part.len()) && part.bytes().all(|b| b.is_ascii_digit());
    if parts.len() == 4 && parts[0] == "10" && parts[1..].iter().all(|part| number(part)) {
        Some(format!("10.{}.{}", parts[1], parts[2]))
    } else {
        None
    }
}
