//! Signed writes, as both sides compute them (docs/agent.md, "Signed writes"; `Signing` on the
//! manager's side): what a write signs, and the id a signature names its key by.

use sha2::{Digest, Sha256};

use super::pack_hash::hex;

/// A key's id: the first 16 hex characters of the SHA-256 of its X.509 encoding.
pub fn key_id(x509: &[u8]) -> String {
    hex(&Sha256::digest(x509))[..16].to_string()
}

/// What a write signs: its stream connection's challenge, its counter on that connection, its
/// method, its path (with no query), and the lowercase hex SHA-256 of its body, one to a line.
pub fn message(
    challenge: &str,
    counter: i64,
    method: &str,
    path: &str,
    body_hash: &[u8],
) -> Vec<u8> {
    format!(
        "{challenge}\n{counter}\n{method}\n{path}\n{}",
        hex(body_hash)
    )
    .into_bytes()
}
