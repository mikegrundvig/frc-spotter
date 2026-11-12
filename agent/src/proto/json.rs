//! The JSON form of the messages, as QuickBuffers writes it (docs/agent.md, "Protocol 2"): names in
//! lowerCamelCase, enums by their names, a map as a list of `{key, value}`, bytes in base64.

use std::collections::BTreeMap;

use base64::Engine;
use serde::Serializer;
use serde::ser::SerializeSeq;

/// An enum field, written as its value's name (`FIELD_TYPE_NUMBER`); a number it has no name for,
/// as the number.
pub fn enum_name<E, S>(value: &i32, serializer: S) -> Result<S::Ok, S::Error>
where
    E: TryFrom<i32> + Named,
    S: Serializer,
{
    match E::try_from(*value) {
        Ok(known) => serializer.serialize_str(known.name()),
        Err(_) => serializer.serialize_i32(*value),
    }
}

/// An enum's value's name, as spotter.proto writes it.
pub trait Named {
    fn name(&self) -> &'static str;
}

macro_rules! named {
    ($($kind:ty),*) => {
        $(impl Named for $kind {
            fn name(&self) -> &'static str {
                self.as_str_name()
            }
        })*
    };
}

named!(
    super::FieldType,
    super::Input,
    super::Level,
    super::LogLevel,
    super::Outcome
);

/// A map, as a list of `{"key": ..., "value": ...}`.
pub fn entries<S: Serializer>(
    map: &BTreeMap<String, String>,
    serializer: S,
) -> Result<S::Ok, S::Error> {
    #[derive(serde::Serialize)]
    struct Entry<'a> {
        key: &'a str,
        value: &'a str,
    }
    let mut list = serializer.serialize_seq(Some(map.len()))?;
    for (key, value) in map {
        list.serialize_element(&Entry { key, value })?;
    }
    list.end()
}

/// Bytes, in base64.
pub fn base64<S: Serializer>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error> {
    serializer.serialize_str(&base64::engine::general_purpose::STANDARD.encode(bytes))
}

/// A message as a response's JSON: pretty-printed.
pub fn pretty<M: serde::Serialize>(message: &M) -> Vec<u8> {
    serde_json::to_vec_pretty(message).unwrap_or_default()
}

/// A message as one line of a stream's JSON lines.
pub fn line<M: serde::Serialize>(message: &M) -> Vec<u8> {
    let mut bytes = serde_json::to_vec(message).unwrap_or_default();
    bytes.push(b'\n');
    bytes
}
