//! spotter.proto's messages, generated at build time (build.rs), and how they're written.

pub mod json;

include!(concat!(env!("OUT_DIR"), "/spotter.v2.rs"));
