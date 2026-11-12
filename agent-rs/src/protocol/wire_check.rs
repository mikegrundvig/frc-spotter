//! Spotter's messages, checked before they're parsed (`WireCheck` on the manager's side): every
//! length-delimited field must fit within the message that holds it, and a message may hold at
//! most [`MAX_REPEATED`] elements of repeated fields in all, at every depth. prost checks a length
//! against what's there before it allocates, but every element of a repeated field is a whole
//! message's worth of memory: a 16 MiB bundle of empty files would be hundreds of megabytes. This
//! walks the bytes by the type's fields (spotter.proto's, in the table below), building nothing.

use prost::bytes::Buf;
use prost::encoding::decode_varint;

/// The most elements of repeated fields one message may hold, at every depth.
pub const MAX_REPEATED: usize = 1 << 14;

/// A message type, as the check walks it: which of its fields hold a message, and of which type,
/// and which repeat.
pub struct Type {
    pub name: &'static str,
    messages: &'static [(u32, &'static Type)],
    repeated: &'static [u32],
}

impl Type {
    fn message(&self, field: u32) -> Option<&'static Type> {
        self.messages
            .iter()
            .find(|(number, _)| *number == field)
            .map(|(_, kind)| *kind)
    }

    fn repeats(&self, field: u32) -> bool {
        self.repeated.contains(&field)
    }
}

const fn leaf(name: &'static str) -> Type {
    Type {
        name,
        messages: &[],
        repeated: &[],
    }
}

// spotter.proto's messages, leaves first: each field that holds a message, and each that repeats.
static SCALAR: Type = leaf("spotter.v2.Scalar");
static LIMIT: Type = Type {
    name: "spotter.v2.Limit",
    messages: &[(3, &SCALAR), (4, &SCALAR)],
    repeated: &[],
};
static FIELD_DECLARATION: Type = Type {
    name: "spotter.v2.FieldDeclaration",
    messages: &[(5, &LIMIT), (6, &LIMIT)],
    repeated: &[],
};
static OS_RELEASE_ENTRY: Type = leaf("spotter.v2.Identity.OsReleaseEntry");
static IDENTITY: Type = Type {
    name: "spotter.v2.Identity",
    messages: &[(5, &OS_RELEASE_ENTRY)],
    repeated: &[2, 5],
};
static PACK: Type = leaf("spotter.v2.Pack");
static LOG_DECLARATION: Type = leaf("spotter.v2.LogDeclaration");
static ACTION_DECLARATION: Type = Type {
    name: "spotter.v2.ActionDeclaration",
    messages: &[(8, &FIELD_DECLARATION)],
    repeated: &[8],
};
static STATUS: Type = leaf("spotter.v2.Status");
static FIELD_VALUE: Type = Type {
    name: "spotter.v2.FieldValue",
    messages: &[(6, &STATUS)],
    repeated: &[],
};
static LOG_ENTRY: Type = leaf("spotter.v2.LogEntry");
static RUN_RESULT: Type = Type {
    name: "spotter.v2.RunResult",
    messages: &[(3, &FIELD_VALUE)],
    repeated: &[3],
};
static HEARTBEAT: Type = leaf("spotter.v2.Heartbeat");
static PACK_FILE: Type = leaf("spotter.v2.PackFile");

/// `Description`: what a board has.
pub static DESCRIPTION: Type = Type {
    name: "spotter.v2.Description",
    messages: &[
        (3, &IDENTITY),
        (4, &PACK),
        (5, &FIELD_DECLARATION),
        (6, &LOG_DECLARATION),
        (7, &ACTION_DECLARATION),
    ],
    repeated: &[4, 5, 6, 7, 8],
};

/// `Values`: a board's values, or those that changed.
pub static VALUES: Type = Type {
    name: "spotter.v2.Values",
    messages: &[(4, &FIELD_VALUE)],
    repeated: &[4],
};

/// `RunState`: a kept run.
pub static RUN_STATE: Type = Type {
    name: "spotter.v2.RunState",
    messages: &[(4, &RUN_RESULT)],
    repeated: &[],
};

/// `RunEvent`: a run started, logged a line, or finished.
pub static RUN_EVENT: Type = Type {
    name: "spotter.v2.RunEvent",
    messages: &[(5, &LOG_ENTRY), (6, &RUN_RESULT)],
    repeated: &[],
};

/// `Runs`: the runs a board keeps.
pub static RUNS: Type = Type {
    name: "spotter.v2.Runs",
    messages: &[(1, &RUN_STATE)],
    repeated: &[1],
};

/// `Event`: one of a stream's.
pub static EVENT: Type = Type {
    name: "spotter.v2.Event",
    messages: &[
        (1, &DESCRIPTION),
        (2, &VALUES),
        (3, &RUNS),
        (4, &RUN_EVENT),
        (5, &HEARTBEAT),
    ],
    repeated: &[],
};

/// `LogPage`: a page of a log.
pub static LOG_PAGE: Type = Type {
    name: "spotter.v2.LogPage",
    messages: &[(1, &LOG_ENTRY)],
    repeated: &[1],
};

/// `PackBundle`: packs, as the robot pushes them.
pub static PACK_BUNDLE: Type = Type {
    name: "spotter.v2.PackBundle",
    messages: &[(1, &PACK_FILE)],
    repeated: &[1],
};

/// The wire types proto3 uses.
#[derive(Clone, Copy)]
enum WireType {
    Varint,
    Fixed64,
    LengthDelimited,
    Fixed32,
}

impl WireType {
    fn of(tag: u64) -> Option<WireType> {
        match tag & 7 {
            0 => Some(WireType::Varint),
            1 => Some(WireType::Fixed64),
            2 => Some(WireType::LengthDelimited),
            5 => Some(WireType::Fixed32),
            _ => None,
        }
    }
}

/// Checks a whole message's bytes before they're parsed: why they can't be parsed safely, if not.
pub fn check(kind: &Type, bytes: &[u8]) -> Result<(), String> {
    walk(kind, bytes, 0).map(|_| ())
}

/// Walks one message's fields: the count of repeated elements so far, after it.
fn walk(kind: &Type, mut bytes: &[u8], mut count: usize) -> Result<usize, String> {
    while bytes.has_remaining() {
        let tag = decode_varint(&mut bytes).map_err(|e| format!("{}: {e}", kind.name))?;
        let field = (tag >> 3) as u32;
        if field == 0 {
            return Err(format!("{}: a field numbered 0", kind.name));
        }
        let Some(wire) = WireType::of(tag) else {
            return Err(format!(
                "{}'s field {field} has wire type {}, which spotter.proto has none of",
                kind.name,
                tag & 7
            ));
        };
        match wire {
            WireType::Varint => {
                decode_varint(&mut bytes).map_err(|e| format!("{}: {e}", kind.name))?;
            }
            WireType::Fixed64 => skip(&mut bytes, 8, kind)?,
            WireType::Fixed32 => skip(&mut bytes, 4, kind)?,
            WireType::LengthDelimited => {
                let length =
                    decode_varint(&mut bytes).map_err(|e| format!("{}: {e}", kind.name))?;
                let left = bytes.remaining();
                if length > left as u64 {
                    return Err(format!(
                        "{}'s field {field} says {length} bytes, and {left} are left",
                        kind.name
                    ));
                }
                if kind.repeats(field) {
                    count += 1;
                    if count > MAX_REPEATED {
                        return Err(format!(
                            "more than {MAX_REPEATED} elements of lists in one message, at {}'s field {field}",
                            kind.name
                        ));
                    }
                }
                let (inner, rest) = bytes.split_at(length as usize);
                if let Some(nested) = kind.message(field) {
                    count = walk(nested, inner, count)?;
                }
                bytes = rest;
            }
        }
    }
    Ok(count)
}

fn skip(bytes: &mut &[u8], length: usize, kind: &Type) -> Result<(), String> {
    if bytes.remaining() < length {
        return Err(format!("{}: cut short", kind.name));
    }
    bytes.advance(length);
    Ok(())
}
