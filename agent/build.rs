//! Generates spotter.proto's messages (the manager's, which both sides speak) with prost, the proto
//! compiled by protox, so a build needs no protoc. Each message also serializes as the JSON a
//! request asks for with `Accept: application/json`: QuickBuffers' form, which the manager's side
//! reads (docs/agent.md, "Protocol 2").

use std::error::Error;

/// spotter.proto, in the manager's project: one schema for both sides.
const PROTO: &str = "../manager/src/main/proto/spotter.proto";

/// The fields that hold one of spotter.proto's enums, as JSON writes them: by their names.
const ENUM_FIELDS: &[(&str, &str)] = &[
    ("spotter.v2.FieldDeclaration.type", "FieldType"),
    ("spotter.v2.ActionDeclaration.input", "Input"),
    ("spotter.v2.Status.level", "Level"),
    ("spotter.v2.LogEntry.level", "LogLevel"),
    ("spotter.v2.RunResult.outcome", "Outcome"),
];

/// The fields that may be absent (a message, an `optional`), which JSON leaves out when they are.
const OPTIONAL_FIELDS: &[&str] = &[
    "spotter.v2.Description.identity",
    "spotter.v2.FieldDeclaration.warn",
    "spotter.v2.FieldDeclaration.fail",
    "spotter.v2.Limit.above",
    "spotter.v2.Limit.below",
    "spotter.v2.Limit.equals",
    "spotter.v2.Limit.not_equals",
    "spotter.v2.RunState.result",
];

/// The oneofs, which JSON writes as the one field set, in their message.
const ONEOFS: &[&str] = &[
    "spotter.v2.Event.event",
    "spotter.v2.Scalar.value",
    "spotter.v2.FieldValue.value",
    "spotter.v2.RunEvent.change",
];

fn main() -> Result<(), Box<dyn Error>> {
    println!("cargo:rerun-if-changed={PROTO}");
    let descriptors = protox::compile([PROTO], ["../manager/src/main/proto"])?;
    let mut config = prost_build::Config::new();
    // Maps in their keys' order, so a description serializes the same every time (its revision is a
    // hash of it), and JSON writes a map as QuickBuffers does: a list of {key, value}.
    config.btree_map(["."]);
    config.type_attribute(".", "#[derive(serde::Serialize)]");
    config.type_attribute(".", "#[serde(rename_all = \"camelCase\")]");
    for (field, kind) in ENUM_FIELDS {
        config.field_attribute(
            field,
            format!("#[serde(serialize_with = \"crate::proto::json::enum_name::<{kind}, _>\")]"),
        );
    }
    for field in OPTIONAL_FIELDS {
        config.field_attribute(field, "#[serde(skip_serializing_if = \"Option::is_none\")]");
    }
    for oneof in ONEOFS {
        config.field_attribute(oneof, "#[serde(flatten)]");
    }
    config.field_attribute(
        "spotter.v2.Identity.os_release",
        "#[serde(serialize_with = \"crate::proto::json::entries\")]",
    );
    config.field_attribute(
        "spotter.v2.PackFile.content",
        "#[serde(serialize_with = \"crate::proto::json::base64\")]",
    );
    config.compile_fds(descriptors)?;
    Ok(())
}
