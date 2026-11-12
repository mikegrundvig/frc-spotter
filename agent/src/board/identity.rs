//! Which computer this is, read from the computer itself, with nothing configured: its hostname,
//! its addresses, the wired interface's MAC address, this boot, and `/etc/os-release` as it is.

use std::collections::BTreeMap;
use std::io;

use super::host::Host;
use crate::proto::Identity;

const HOSTNAME: &str = "/proc/sys/kernel/hostname";
pub const BOOT_ID: &str = "/proc/sys/kernel/random/boot_id";
const NETWORK: &str = "/sys/class/net";

/// Where os-release(5) is, and where to look when it isn't.
const OS_RELEASE: &str = "/etc/os-release";
const OS_RELEASE_FALLBACK: &str = "/usr/lib/os-release";

/// The most keys of os-release passed on, and the longest value: a real one has a dozen.
const MAX_OS_RELEASE_KEYS: usize = 64;
const MAX_OS_RELEASE_VALUE: usize = 512;

/// Which computer this is, now.
pub fn read(host: &Host) -> io::Result<Identity> {
    Ok(Identity {
        hostname: hostname(host)?,
        addresses: host.addresses()?,
        mac: mac(host)?,
        boot_id: host.line(BOOT_ID)?.unwrap_or_default(),
        os_release: os_release(host)?,
    })
}

/// Its hostname, which is the manager's name for it; empty when it can't be read.
pub fn hostname(host: &Host) -> io::Result<String> {
    Ok(host.line(HOSTNAME)?.unwrap_or_default())
}

/// The MAC address of the wired interface: the first physical interface (one with a device behind
/// it) that's up, or failing that the first physical one.
fn mac(host: &Host) -> io::Result<String> {
    let mut first = String::new();
    for name in host.list(NETWORK)? {
        let folder = format!("{NETWORK}/{name}");
        if name == "lo" || !host.exists(&format!("{folder}/device")) {
            continue;
        }
        let address = host
            .line(&format!("{folder}/address"))?
            .unwrap_or_default()
            .to_lowercase();
        if host
            .line(&format!("{folder}/operstate"))?
            .unwrap_or_default()
            == "up"
        {
            return Ok(address);
        }
        if first.is_empty() {
            first = address;
        }
    }
    Ok(first)
}

/// `/etc/os-release` (else `/usr/lib/os-release`), every key with its value unquoted; empty when
/// the computer has neither.
fn os_release(host: &Host) -> io::Result<BTreeMap<String, String>> {
    let text = match host.read(OS_RELEASE)? {
        Some(text) => Some(text),
        None => host.read(OS_RELEASE_FALLBACK)?,
    };
    Ok(text.map(|text| parse_os_release(&text)).unwrap_or_default())
}

/// os-release's lines, read as os-release(5) writes them: `KEY=value`, the value maybe in single
/// or double quotes (where a backslash escapes `$ " \ ``), comments and blank lines skipped. A
/// line that isn't an assignment is skipped too. Nothing is read into a value.
pub fn parse_os_release(text: &str) -> BTreeMap<String, String> {
    let mut values = BTreeMap::new();
    for raw in text.split('\n') {
        let line = raw.trim();
        let Some(equals) = line.find('=') else {
            continue;
        };
        if line.starts_with('#') || equals == 0 {
            continue;
        }
        let key = &line[..equals];
        if !is_key(key) {
            continue;
        }
        let value = unquote(&line[equals + 1..]);
        if values.len() < MAX_OS_RELEASE_KEYS || values.contains_key(key) {
            let value: String = value.chars().take(MAX_OS_RELEASE_VALUE).collect();
            values.insert(key.to_string(), value);
        }
    }
    values
}

/// An os-release key: a shell variable's name.
fn is_key(key: &str) -> bool {
    let mut chars = key.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

fn unquote(value: &str) -> String {
    if value.len() >= 2 && value.starts_with('\'') && value.ends_with('\'') {
        return value[1..value.len() - 1].to_string();
    }
    if value.len() >= 2 && value.starts_with('"') && value.ends_with('"') {
        let inner: Vec<char> = value[1..value.len() - 1].chars().collect();
        let mut out = String::new();
        let mut i = 0;
        while i < inner.len() {
            let mut c = inner[i];
            if c == '\\' && i + 1 < inner.len() && "$\"\\`".contains(inner[i + 1]) {
                i += 1;
                c = inner[i];
            }
            out.push(c);
            i += 1;
        }
        return out;
    }
    value.to_string()
}
