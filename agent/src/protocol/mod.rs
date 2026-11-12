//! Spotter's protocol, version 2.0, as the manager's `Protocol` has it: HTTP on [`PORT`], its
//! messages spotter.proto's, in protobuf unless JSON is asked for. The version is in HTTP, never in
//! a message: the major version in the path (`/v2/`), and `major.minor` in [`HEADER`] on every
//! response. Beside it, the code both sides compute alike: the pack hash, what a signed write
//! signs, and the check of a message's structure before it's parsed.

pub mod pack_hash;
pub mod signing;
pub mod wire_check;

/// The protocol's version, in [`HEADER`] on every response.
pub const VERSION: &str = "2.0";

/// The header every response carries the protocol's version in.
pub const HEADER: &str = "Spotter-Protocol";

/// The agent's port unless its `agent.json` says otherwise: in FRC's team range.
pub const PORT: u16 = 5808;

/// Protobuf's media type: what every endpoint answers in unless JSON is asked for.
pub const PROTOBUF: &str = "application/x-protobuf";

/// JSON's media type, asked for with `Accept`.
pub const JSON: &str = "application/json";

/// JSON lines: a stream's form when JSON is asked for.
pub const JSON_LINES: &str = "application/x-ndjson";

pub const DESCRIBE: &str = "/v2/describe";
pub const VALUES: &str = "/v2/values";
pub const STREAM: &str = "/v2/stream";
pub const PACKS: &str = "/v2/packs";
pub const LOGS: &str = "/v2/logs/";
pub const ACTIONS: &str = "/v2/actions/";
pub const RUNS: &str = "/v2/runs/";

/// The header a stream's response carries its connection's challenge in.
pub const CHALLENGE: &str = "Spotter-Challenge";

/// The header a signed write carries: `<key id> <counter> <signature>`.
pub const SIGNATURE: &str = "Spotter-Signature";

/// The last number of the robot controller's address, 10.TE.AM.2: who may change a board.
pub const CONTROLLER: u8 = 2;

/// The most one of a stream's events may be, in bytes.
pub const MAX_EVENT: usize = 1 << 20;

/// The most a run's response may be, in bytes, as its events and its state carry it.
pub const MAX_RESPONSE: usize = 256 << 10;

/// The most values a board may describe.
pub const MAX_VALUES: usize = 2048;

/// The most actions a board may describe, its two built-in ones included.
pub const MAX_ACTIONS: usize = 128;

/// The most problems a board may list: past them, the last says how many more there were.
pub const MAX_PROBLEMS: usize = 128;

/// The most a pushed bundle's files may hold, in bytes.
pub const MAX_BUNDLE: u64 = 16 * 1024 * 1024;

/// The most files a pushed bundle may hold.
pub const MAX_FILES: usize = 4096;

/// The longest one request may take to arrive, in seconds: as long as the manager gives one.
pub const REQUEST_SECONDS: u64 = 30;
