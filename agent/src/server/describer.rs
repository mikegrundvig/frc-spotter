//! What a board has, as its description says it: its identity, its packs, the values they report,
//! the logs they serve, the actions they offer (after the two built in), and what the agent
//! ignored. What runs stays on the board: no command, URL or path a pack names is in it. Its
//! revision is a CRC-32 of everything else in it, so it changes whenever anything does, and an
//! agent restarted with the same packs on the same board describes itself with the same revision.

use prost::Message;

use crate::configuration::Configuration;
use crate::packs::pack::{self, CORE};
use crate::proto::{Description, Identity, LogDeclaration};
use crate::protocol;
use crate::values::collectors;

/// The longest a problem is in the description: the agent's journal has it whole.
pub const MAX_PROBLEM: usize = 1024;

/// The description, with this identity, and its revision.
pub fn describe(version: &str, configuration: &Configuration, identity: Identity) -> Description {
    let packs = &configuration.packs.packs;
    let mut description = Description {
        agent_version: version.to_string(),
        identity: Some(identity),
        pushed_packs: configuration.packs.pushed_hash.clone(),
        refuses_pushes: !configuration.accepts_pushes(),
        requires_signatures: !configuration.config.trusted_keys.is_empty(),
        ..Description::default()
    };
    description.packs = packs.iter().map(|each| each.description()).collect();
    for slot in collectors::slots(packs) {
        for field in &slot.collector.fields {
            description.values.push(field.declaration(&slot.id(field)));
        }
    }
    for each in packs {
        for log in &each.logs {
            description.logs.push(LogDeclaration {
                id: format!("{}.{}", each.name, log.id),
                label: log.label.clone(),
            });
        }
    }
    description.actions = pack::built_in()
        .iter()
        .map(|action| action.declaration(CORE))
        .collect();
    for each in packs {
        description.actions.extend(
            each.actions
                .iter()
                .map(|action| action.declaration(&each.name)),
        );
    }
    let problems = &configuration.problems;
    for (i, problem) in problems.iter().enumerate().take(protocol::MAX_PROBLEMS) {
        description.problems.push(
            if i == protocol::MAX_PROBLEMS - 1 && problems.len() > protocol::MAX_PROBLEMS {
                format!(
                    "{} more problems: the agent's journal lists them all",
                    problems.len() - i
                )
            } else {
                cut(problem)
            },
        );
    }
    description.revision = crc32fast::hash(&description.encode_to_vec());
    description
}

fn cut(problem: &str) -> String {
    if problem.encode_utf16().count() <= MAX_PROBLEM {
        problem.to_string()
    } else {
        format!(
            "{}...",
            problem.chars().take(MAX_PROBLEM).collect::<String>()
        )
    }
}
