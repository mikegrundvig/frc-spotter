//! Signed writes, for a board that lists trusted keys in its `agent.json`: only the holder of a
//! trusted private key can change it. Reading stays open.
//!
//! No replays, and no clocks needed. Each stream connection gets a fresh random challenge. A write
//! signs, with Ed25519, the UTF-8 bytes of `challenge \n counter \n method \n path \n hex SHA-256
//! of the body`, and says `Spotter-Signature: <key id> <counter> <signature>`. The agent checks the
//! signature against that key, that the challenge is a current connection's, and that the counter
//! is higher than any it has seen on that connection.

use std::sync::Mutex;

use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};

use crate::board::config::public_key;
use crate::protocol::signing;

/// The length of a challenge, in random bytes.
const CHALLENGE_BYTES: usize = 32;

pub struct Signatures {
    keys: Vec<(String, VerifyingKey)>,
    /// Each current challenge, and the highest counter seen on its connection, in the order they
    /// opened.
    challenges: Mutex<Vec<(String, i64)>>,
}

impl Signatures {
    /// The keys a write must be signed by, as `agent.json` lists them (checked as it was read);
    /// none for no signatures.
    pub fn new(trusted: &[String]) -> Signatures {
        let keys = trusted
            .iter()
            .filter_map(|key| {
                let der = STANDARD.decode(key).ok()?;
                Some((signing::key_id(&der), public_key(key).ok()?))
            })
            .collect();
        Signatures {
            keys,
            challenges: Mutex::new(Vec::new()),
        }
    }

    /// Whether writes must be signed: the board lists trusted keys.
    pub fn required(&self) -> bool {
        !self.keys.is_empty()
    }

    /// A new connection's challenge, current until it closes.
    pub fn open(&self) -> String {
        let mut bytes = [0u8; CHALLENGE_BYTES];
        getrandom::fill(&mut bytes).expect("the system's random numbers");
        let challenge = URL_SAFE_NO_PAD.encode(bytes);
        self.challenges
            .lock()
            .expect("the challenges' lock")
            .push((challenge.clone(), 0));
        challenge
    }

    /// A connection gone: its challenge is no longer current.
    pub fn close(&self, challenge: &str) {
        self.challenges
            .lock()
            .expect("the challenges' lock")
            .retain(|(each, _)| each != challenge);
    }

    /// Checks a write's signature, and takes its counter: why it's refused, if it is.
    pub fn verify(
        &self,
        method: &str,
        path: &str,
        body_hash: &[u8],
        header: Option<&str>,
    ) -> Result<(), String> {
        let header = header
            .map(str::trim)
            .filter(|h| !h.is_empty())
            .ok_or_else(|| {
                "this board requires signed writes, and this one isn't signed".to_string()
            })?;
        let parts: Vec<&str> = header.split(' ').filter(|part| !part.is_empty()).collect();
        if parts.len() != 3 {
            return Err("Spotter-Signature is <key id> <counter> <signature>".to_string());
        }
        let Some((_, key)) = self.keys.iter().find(|(id, _)| id == parts[0]) else {
            return Err(format!("key {} isn't one this board trusts", parts[0]));
        };
        let malformed =
            || "Spotter-Signature is <key id> <counter> <signature in base64>".to_string();
        let counter: i64 = java_long(parts[1]).ok_or_else(malformed)?;
        let signature = STANDARD.decode(parts[2]).map_err(|_| malformed())?;
        let mut challenges = self.challenges.lock().expect("the challenges' lock");
        if challenges.is_empty() {
            return Err("no stream is connected, so there's no challenge to sign".to_string());
        }
        for (challenge, last) in challenges.iter_mut() {
            let message = signing::message(challenge, counter, method, path, body_hash);
            if valid(key, &message, &signature) {
                if counter <= *last {
                    return Err(format!(
                        "counter {counter} was used already on this connection (the last was {last})"
                    ));
                }
                *last = counter;
                return Ok(());
            }
        }
        Err(format!(
            "the signature doesn't match this request, signed by key {} over a current connection's challenge",
            parts[0]
        ))
    }
}

/// A number as Java's `Long.parseLong` reads one: an optional sign and decimal digits.
fn java_long(text: &str) -> Option<i64> {
    let digits = text.strip_prefix(['-', '+']).unwrap_or(text);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok()
}

fn valid(key: &VerifyingKey, message: &[u8], signature: &[u8]) -> bool {
    match Signature::from_slice(signature) {
        Ok(signature) => key.verify(message, &signature).is_ok(),
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use sha2::{Digest, Sha256};

    use super::*;
    use crate::testing::Signer;

    const BODY: &[u8] = b"front camera";
    const PATH: &str = "/v2/actions/team.ok";

    fn hash(body: &[u8]) -> Vec<u8> {
        Sha256::digest(body).to_vec()
    }

    #[test]
    fn a_write_signed_over_the_challenge_with_a_rising_counter_is_taken() {
        let team = Signer::new(1);
        let signatures = Signatures::new(std::slice::from_ref(&team.public));
        assert!(signatures.required());
        assert!(
            team.id.len() == 16
                && team
                    .id
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        );
        let challenge = signatures.open();
        let decoded = URL_SAFE_NO_PAD.decode(&challenge).expect("base64url");
        assert_eq!(decoded.len(), CHALLENGE_BYTES);
        let sign = |counter| team.sign(&challenge, counter, "POST", PATH, BODY);
        let verify = |signed: String| signatures.verify("POST", PATH, &hash(BODY), Some(&signed));
        assert_eq!(verify(sign(1)), Ok(()));
        assert_eq!(verify(sign(5)), Ok(()));
        // Replayed, or not higher: refused.
        assert_eq!(
            verify(sign(5)),
            Err("counter 5 was used already on this connection (the last was 5)".into())
        );
        let lower = verify(sign(3)).expect_err("refused");
        assert!(lower.starts_with("counter 3 was used already"), "{lower}");
    }

    #[test]
    fn a_write_of_another_body_method_path_or_key_is_refused() {
        let team = Signer::new(1);
        let other = Signer::new(2);
        let signatures = Signatures::new(std::slice::from_ref(&team.public));
        let challenge = signatures.open();
        let signed = team.sign(&challenge, 1, "POST", PATH, BODY);
        let by_other = other.sign(&challenge, 1, "POST", PATH, BODY);
        // The right key's id, another key's signature.
        let forged = format!(
            "{} 1 {}",
            team.id,
            by_other.split(' ').nth(2).expect("its signature")
        );
        let rows: [(&str, Vec<u8>, &str, String); 4] = [
            (
                PATH,
                hash(b""),
                &signed,
                "the signature doesn't match this request".into(),
            ),
            (
                "/v2/actions/core.reboot",
                hash(BODY),
                &signed,
                "the signature doesn't match".into(),
            ),
            (
                PATH,
                hash(BODY),
                &by_other,
                format!("key {} isn't one this board trusts", other.id),
            ),
            (
                PATH,
                hash(BODY),
                &forged,
                "the signature doesn't match".into(),
            ),
        ];
        for (path, body, header, why) in rows {
            let refused = signatures
                .verify("POST", path, &body, Some(header))
                .expect_err("refused");
            assert!(refused.starts_with(&why), "{refused}");
        }
    }

    #[test]
    fn an_unsigned_or_malformed_write_is_refused_with_why() {
        let team = Signer::new(1);
        let signatures = Signatures::new(std::slice::from_ref(&team.public));
        signatures.open();
        let verify = |header: Option<&str>| signatures.verify("POST", PATH, &hash(BODY), header);
        assert_eq!(
            verify(None),
            Err("this board requires signed writes, and this one isn't signed".into())
        );
        assert_eq!(
            verify(Some("a b")),
            Err("Spotter-Signature is <key id> <counter> <signature>".into())
        );
        let malformed = verify(Some(&format!("{} one two", team.id))).expect_err("refused");
        assert!(malformed.contains("<signature in base64>"), "{malformed}");
    }

    #[test]
    fn a_challenge_is_useless_after_its_connection_closes() {
        let team = Signer::new(1);
        let signatures = Signatures::new(std::slice::from_ref(&team.public));
        let verify = |signed: String| signatures.verify("POST", PATH, &hash(BODY), Some(&signed));
        let old = signatures.open();
        signatures.close(&old);
        assert_eq!(
            verify(team.sign(&old, 1, "POST", PATH, BODY)),
            Err("no stream is connected, so there's no challenge to sign".into())
        );
        let fresh = signatures.open();
        assert_ne!(fresh, old);
        let stale = verify(team.sign(&old, 1, "POST", PATH, BODY)).expect_err("refused");
        assert!(stale.starts_with("the signature doesn't match"), "{stale}");
        assert_eq!(verify(team.sign(&fresh, 1, "POST", PATH, BODY)), Ok(()));
        assert!(!Signatures::new(&[]).required());
    }
}
