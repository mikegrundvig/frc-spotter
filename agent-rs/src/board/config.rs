//! The board's own settings, in `/etc/frc-spotter/agent.json`, for whoever owns the board; the
//! agent needs none of them, and a push can't change them.
//!
//! ```json
//! {"team": 1234, "port": 5809, "bind": "10.12.34.11",
//!  "acceptPushes": false, "trustedKeys": ["MCowBQYDK2VwAyEA..."]}
//! ```
//!
//! Naming the robot controller, by its address or its team's number, is what lets the robot push
//! packs and run the packs' actions: a controller the agent only works out from its own address may
//! run its two built-in actions and nothing else.

use std::net::Ipv4Addr;

use base64::Engine;
use ed25519_dalek::VerifyingKey;
use ed25519_dalek::pkcs8::DecodePublicKey;
use serde_json::Value;

use super::json;
use crate::protocol;

/// Where the settings are.
pub const PATH: &str = "/etc/frc-spotter/agent.json";

/// The keys the file may have, and no others, in their names' order.
const KEYS: [&str; 6] = [
    "acceptPushes",
    "bind",
    "controller",
    "port",
    "team",
    "trustedKeys",
];

/// The highest team number a robot's network can hold: 10.255.99.x.
const MAX_TEAM: i64 = 25599;

#[derive(Clone, Debug, PartialEq)]
pub struct AgentConfig {
    /// The port it serves on.
    pub port: u16,
    /// The one address a write is taken from; empty to find it from the computer's own address,
    /// unless the team is named.
    pub controller: String,
    /// The robot's team, whose controller, 10.TE.AM.2, a write is taken from; 0 for none.
    pub team: u32,
    /// The address it listens on; empty for every address.
    pub bind: String,
    /// Whether the robot may push packs to it; when not, it ignores pushed packs.
    pub accept_pushes: bool,
    /// The Ed25519 public keys a write must be signed by, each its X.509 encoding in base64; none
    /// for no signatures.
    pub trusted_keys: Vec<String>,
}

impl Default for AgentConfig {
    /// Nothing set.
    fn default() -> AgentConfig {
        AgentConfig {
            port: protocol::PORT,
            controller: String::new(),
            team: 0,
            bind: String::new(),
            accept_pushes: true,
            trusted_keys: Vec::new(),
        }
    }
}

/// Whether text is an IPv4 address written out: four numbers from 0 to 255, without leading
/// zeros. Never a name, which would be looked up.
pub fn is_ipv4(text: &str) -> bool {
    let parts: Vec<&str> = text.split('.').collect();
    parts.len() == 4
        && parts.iter().all(|part| {
            !part.is_empty()
                && part.len() <= 3
                && part.bytes().all(|b| b.is_ascii_digit())
                && (part.len() == 1 || !part.starts_with('0'))
                && part.parse::<u16>().is_ok_and(|n| n <= 255)
        })
        && text.parse::<Ipv4Addr>().is_ok()
}

/// A trusted key's text as the key it is: an Ed25519 public key's X.509 encoding, in base64.
pub fn public_key(key: &str) -> Result<VerifyingKey, String> {
    let refused = || format!("trusted key \"{key}\" isn't an Ed25519 public key in base64 (X.509)");
    let der = base64::engine::general_purpose::STANDARD
        .decode(key)
        .map_err(|_| refused())?;
    VerifyingKey::from_public_key_der(&der).map_err(|_| refused())
}

impl AgentConfig {
    /// The settings, checked as Java's constructor checks them.
    fn checked(
        port: i64,
        controller: String,
        team: i64,
        bind: String,
        accept_pushes: bool,
        trusted_keys: Vec<String>,
    ) -> Result<AgentConfig, String> {
        if !(1024..=65535).contains(&port) {
            return Err(format!(
                "port {port} is out of range: 1024 to 65535 (5800-5810 is FRC's team range)"
            ));
        }
        for address in [&controller, &bind] {
            if !address.is_empty() && !is_ipv4(address) {
                return Err(format!(
                    "\"{address}\" isn't an IPv4 address written out, such as 10.12.34.2"
                ));
            }
        }
        if !(0..=MAX_TEAM).contains(&team) {
            return Err(format!("team {team} is out of range: 1 to {MAX_TEAM}"));
        }
        if team > 0 && !controller.is_empty() {
            return Err(format!(
                "it names the controller or the team, not both: the team's controller is 10.TE.AM.{}",
                protocol::CONTROLLER
            ));
        }
        for key in &trusted_keys {
            public_key(key)?;
        }
        Ok(AgentConfig {
            port: port as u16,
            controller,
            team: team as u32,
            bind,
            accept_pushes,
            trusted_keys,
        })
    }

    /// The robot controller it names, by its address or its team's; empty when it names none.
    pub fn named_controller(&self) -> String {
        if !self.controller.is_empty() {
            return self.controller.clone();
        }
        if self.team == 0 {
            return String::new();
        }
        format!(
            "10.{}.{}.{}",
            self.team / 100,
            self.team % 100,
            protocol::CONTROLLER
        )
    }

    /// The settings from the file's text, checked: a key it doesn't know is refused, so an
    /// agent.json from before 0.4.0 says so rather than half working.
    pub fn parse(text: &str) -> Result<AgentConfig, String> {
        let members = json::object(text)?;
        let unknown: Vec<&str> = members
            .keys()
            .map(String::as_str)
            .filter(|key| !KEYS.contains(key))
            .collect();
        if !unknown.is_empty() {
            let mut unknown = unknown;
            unknown.sort();
            return Err(format!(
                "it may set {} only, not {} (packs are folders in /etc/frc-spotter/packs/)",
                KEYS.join(", "),
                unknown.join(", ")
            ));
        }
        let mut keys = Vec::new();
        match members.get("trustedKeys") {
            None => {}
            Some(Value::Array(listed)) => {
                for key in listed {
                    let Value::String(key) = key else {
                        return Err("trustedKeys must be a list of keys, each text".to_string());
                    };
                    keys.push(key.clone());
                }
            }
            Some(_) => return Err("trustedKeys must be a list of keys".to_string()),
        }
        let port = match members.get("port") {
            None => i64::from(protocol::PORT),
            Some(value) => json::whole(value).ok_or("port must be a whole number")?,
        };
        let team = match members.get("team") {
            None => 0,
            Some(value) => json::whole(value)
                .ok_or("team must be a whole number, such as 1234")?
                .max(-1),
        };
        let accept = match members.get("acceptPushes") {
            None => true,
            Some(Value::Bool(accept)) => *accept,
            Some(_) => return Err("acceptPushes must be true or false".to_string()),
        };
        AgentConfig::checked(
            port,
            text_member(&members, "controller")?,
            team,
            text_member(&members, "bind")?,
            accept,
            keys,
        )
    }
}

fn text_member(members: &serde_json::Map<String, Value>, key: &str) -> Result<String, String> {
    match members.get(key) {
        None => Ok(String::new()),
        Some(Value::String(text)) => Ok(text.clone()),
        Some(_) => Err(format!("{key} must be text")),
    }
}
