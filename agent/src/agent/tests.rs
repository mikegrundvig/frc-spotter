//! The agent's description and values: what a board says it has, and what it ignored.

use std::sync::atomic::Ordering;

use super::*;
use crate::board::config::AgentConfig;
use crate::board::host::Owner;
use crate::packs::load::{INSTALLED, Loaded};
use crate::proto::{FieldType, Identity, Input, Level, field_value, scalar};
use crate::server::writes::may;
use crate::testing::{ADDRESS, Fixture, Signer};
use crate::values::store::NOT_YET;

const VISION: &str = "pack: vision
version: 1.0.0
collectors:
  - id: service
    run: [./service]
    every: 2s
    fields:
      running: {label: Vision's service, type: text, fail: {notEquals: active}}
  - id: health
    run: [./health]
    every: 1s
    fields:
      fps: {label: Frame rate, type: number, unit: fps, warn: {below: 25}, fail: {below: 10}}
      camera: {type: status}
logs:
  - id: log
    label: Vision's log
    run: [./log]
actions:
  - id: restart
    label: Restart vision
    description: Restarts its service
    run: [./restart]
    confirm: Restart it?
    timeout: 90s
    whileEnabled: true
    response:
      exit: {type: number, fail: {notEquals: 0}}
";

fn ids<T>(items: &[T], id: impl Fn(&T) -> &str) -> Vec<String> {
    items.iter().map(|item| id(item).to_string()).collect()
}

#[test]
fn a_board_with_no_packs_is_a_valid_agent_with_its_identity_and_two_actions() {
    let fixture = Fixture::new();
    let agent = fixture.agent(None);
    let description = agent.description();
    assert_eq!(description.agent_version, "0.4.0-test");
    let identity = description.identity.clone().expect("its identity");
    assert_eq!(identity.hostname, "vision-front");
    assert_eq!(identity.addresses, [ADDRESS]);
    assert_eq!(identity.mac, "c0:74:2b:fe:12:34");
    assert_eq!(identity.boot_id, "3c1e6a2e-0000-4000-8000-000000000001");
    // In its keys' order, not the file's: the order a map keeps.
    let release: Vec<String> = identity
        .os_release
        .iter()
        .map(|(key, value)| format!("{key}={value}"))
        .collect();
    assert_eq!(
        release,
        [
            "ID=debian",
            "PRETTY_NAME=Debian GNU/Linux 13 (trixie)",
            "VERSION_ID=13"
        ]
    );
    assert!(description.packs.is_empty());
    assert!(description.values.is_empty());
    assert!(description.logs.is_empty());
    assert_eq!(
        ids(&description.actions, |a| &a.id),
        ["core.power-off", "core.reboot"]
    );
    let off = &description.actions[0];
    assert_eq!(off.label, "Power off");
    assert_eq!(off.confirm, "Power this computer off?");
    assert_eq!(off.input, Input::None as i32);
    assert!(!off.while_enabled);
    assert_eq!(off.timeout_seconds, 60);
    assert_eq!(
        ids(&off.response, |f| &f.id),
        ["outcome", "outcomeMessage", "exit", "output"]
    );
    assert!(description.problems.is_empty());
    assert!(description.pushed_packs.is_empty());
    // Nothing configured, so no controller named: it takes no pushes.
    assert!(description.refuses_pushes);
    assert!(!description.requires_signatures);
    let values = agent.values();
    assert!(values.complete);
    assert!(values.values.is_empty());
    assert_eq!(values.revision, description.revision);
}

#[test]
fn it_describes_its_packs_values_logs_and_actions_by_id_and_index() {
    let fixture = Fixture::new();
    let vision = fixture.pack("vision", VISION);
    // Its programs are there, so they're trusted: what they print is another test's.
    for program in ["service", "health", "log", "restart"] {
        fixture.script(&format!("{vision}/{program}"), "true");
    }
    fixture.pushed("detector", "pack: detector\nversion: 0.2.0\n");
    fixture.config("{\"team\": 1234}");
    let agent = fixture.agent(None);
    let description = agent.description();
    let packs: Vec<String> = description
        .packs
        .iter()
        .map(|p| format!("{} {} {} {}", p.name, p.version, p.folder, p.pushed))
        .collect();
    assert_eq!(
        packs,
        [
            "detector 0.2.0 /var/lib/frc-spotter/packs/detector true",
            "vision 1.0.0 /etc/frc-spotter/packs/vision false",
        ]
    );
    assert_eq!(
        ids(&description.values, |v| &v.id),
        [
            "vision.service.running",
            "vision.health.fps",
            "vision.health.camera"
        ]
    );
    let fps = &description.values[1];
    assert_eq!(fps.label, "Frame rate");
    assert_eq!(fps.r#type, FieldType::Number as i32);
    assert_eq!(fps.unit, "fps");
    assert_eq!(fps.warn.as_ref().and_then(|l| l.below), Some(25.0));
    assert_eq!(fps.fail.as_ref().and_then(|l| l.below), Some(10.0));
    assert!(description.values[2].warn.is_none());
    let logs: Vec<String> = description
        .logs
        .iter()
        .map(|l| format!("{}: {}", l.id, l.label))
        .collect();
    assert_eq!(logs, ["vision.log: Vision's log"]);
    let restart = &description.actions[2];
    assert_eq!(restart.id, "vision.restart");
    assert_eq!(restart.description, "Restarts its service");
    assert_eq!(restart.timeout_seconds, 90);
    assert!(restart.while_enabled);
    let not_equals = restart.response[2]
        .fail
        .as_ref()
        .and_then(|l| l.not_equals.clone())
        .and_then(|s| s.value);
    assert_eq!(not_equals, Some(scalar::Value::Number(0.0)));
    assert_eq!(description.pushed_packs.len(), 64);
    // What runs stays on the board.
    let shown = format!("{description:?}");
    assert!(!shown.contains("./service") && !shown.contains("./restart"));
    let values = agent.values();
    let indexes: Vec<u32> = values.values.iter().map(|v| v.index).collect();
    assert_eq!(indexes, [0, 1, 2]);
    assert!(
        values
            .values
            .iter()
            .all(|v| v.value == Some(field_value::Value::Unavailable(NOT_YET.into())))
    );
}

#[tokio::test]
async fn its_collectors_fill_its_values_when_they_run() {
    let fixture = Fixture::new();
    let folder = fixture.pack("vision", VISION);
    fixture.script(&format!("{folder}/service"), "echo inactive; exit 3");
    fixture.script(
        &format!("{folder}/health"),
        "echo '{\"fps\": 29.5, \"camera\": {\"level\": \"warning\", \"message\": \"slow\"}}'",
    );
    let agent = fixture.agent(None);
    agent.collectors.run_all().await;
    let values: Vec<_> = agent
        .values()
        .values
        .into_iter()
        .map(|v| v.value.expect("a value"))
        .collect();
    assert_eq!(
        values,
        [
            field_value::Value::Text("inactive".into()),
            field_value::Value::Number(29.5),
            field_value::Value::Status(crate::proto::Status {
                level: Level::Warning as i32,
                message: "slow".into(),
            }),
        ]
    );
}

#[test]
fn its_revision_changes_with_its_description_and_only_then() {
    let fixture = Fixture::new();
    let first = {
        let agent = fixture.agent(None);
        let first = agent.description().revision;
        assert_eq!(agent.description().revision, first);
        // Its addresses change as it runs: read afresh after a while, a new revision.
        fixture.set_addresses(&[ADDRESS, "10.12.34.12"]);
        assert_eq!(agent.description().revision, first);
        fixture.nanos.fetch_add(DESCRIPTION_NANOS, Ordering::SeqCst);
        let identity = agent.description().identity.expect("its identity");
        assert_eq!(identity.addresses.len(), 2);
        assert_ne!(agent.description().revision, first);
        first
    };
    fixture.set_addresses(&[ADDRESS]);
    assert_eq!(fixture.agent(None).description().revision, first);
}

#[test]
fn its_settings_say_whether_it_refuses_pushes_and_requires_signatures() {
    let fixture = Fixture::new();
    fixture.config(&format!(
        "{{\"acceptPushes\": false, \"trustedKeys\": [\"{}\"]}}",
        Signer::new(1).public
    ));
    let description = fixture.agent(None).description();
    assert!(description.refuses_pushes);
    assert!(description.requires_signatures);
}

#[test]
fn what_it_ignored_is_in_its_problems() {
    let fixture = Fixture::new();
    fixture.config("{\"packs\": []}");
    fixture.pack("mine", "pack: mine\n");
    fixture.own(
        &format!("{INSTALLED}/mine/pack.yaml"),
        Owner {
            user: "pi".into(),
            mode: 0o644,
        },
    );
    assert_eq!(
        fixture.agent(None).description().problems,
        [
            "/etc/frc-spotter/agent.json: can't be read, so every write is refused: it may set acceptPushes, bind, controller, port, team, trustedKeys only, not packs (packs are folders in /etc/frc-spotter/packs/)".to_string(),
            format!("{INSTALLED}/mine/pack.yaml: isn't root's (its owner is pi), ignored"),
        ]
    );
}

#[test]
fn a_description_lists_at_most_its_most_problems_each_cut() {
    let mut many: Vec<String> = (0..200).map(|i| format!("problem {i}")).collect();
    many[0] = "x".repeat(2000);
    let configuration = Configuration {
        config: AgentConfig::default(),
        packs: Loaded::default(),
        problems: many,
        unreadable: String::new(),
        controller_named: false,
    };
    let description = describer::describe("0.4.0", &configuration, Identity::default());
    assert_eq!(description.problems.len(), protocol::MAX_PROBLEMS);
    assert_eq!(description.problems[0].len(), describer::MAX_PROBLEM + 3);
    assert_eq!(
        description.problems[127],
        "73 more problems: the agent's journal lists them all"
    );
}

#[test]
fn an_identity_that_cant_be_read_is_logged_and_the_last_one_kept() {
    let fixture = Fixture::new();
    let agent = fixture.agent(None);
    let hostname = |agent: &Agent| agent.description().identity.expect("an identity").hostname;
    assert_eq!(hostname(&agent), "vision-front");
    *fixture.addresses.lock().expect("the addresses") = Err("no interfaces".into());
    // Read afresh, and failing: the last one it read stands.
    fixture.nanos.fetch_add(DESCRIPTION_NANOS, Ordering::SeqCst);
    assert_eq!(hostname(&agent), "vision-front");
    // An agent that never read one has none.
    assert_eq!(hostname(&fixture.agent(None)), "");
    assert!(
        fixture
            .log()
            .contains("Couldn't read this board's identity: no interfaces"),
        "{}",
        fixture.log()
    );
}

#[test]
fn the_controller_is_2_on_its_own_robot_network_unless_named() {
    let fixture = Fixture::new();
    {
        let agent = fixture.agent(None);
        let controller = agent.controller();
        assert_eq!(controller.address, "10.12.34.2");
        // Only worked out: it takes the built-in actions from it, and no pushes.
        assert!(!controller.named);
        assert!(may(&controller, true));
        assert!(!may(&controller, false));
        assert!(!agent.configuration.accepts_pushes());
        assert_eq!(
            agent.configuration.pushes_refused(),
            "its agent.json names no controller or team, and pushes are taken from a named one only"
        );
        assert!(agent.description().refuses_pushes);
        fixture.set_addresses(&[ADDRESS, "10.99.71.11"]);
        assert_eq!(agent.controller().address, "");
        assert!(
            agent
                .controller()
                .why
                .contains("more than one 10.x network (10.12.34.x, 10.99.71.x)")
        );
        fixture.set_addresses(&["192.168.1.5"]);
        assert!(
            agent
                .controller()
                .why
                .starts_with("this computer has no 10.TE.AM.x address")
        );
    }
    fixture.set_addresses(&[ADDRESS]);
    fixture.config("{\"controller\": \"10.12.34.3\"}");
    {
        let agent = fixture.agent(None);
        assert_eq!(agent.controller().address, "10.12.34.3");
        assert!(agent.controller().named);
        assert!(may(&agent.controller(), false));
        assert!(agent.configuration.accepts_pushes());
    }
    fixture.config("{\"team\": 1234}");
    {
        let agent = fixture.agent(None);
        assert_eq!(agent.controller().address, "10.12.34.2");
        assert!(agent.controller().named);
        assert!(!agent.description().refuses_pushes);
    }
    let agent = fixture.agent(Some("127.0.0.1"));
    assert_eq!(agent.controller().address, "127.0.0.1");
    assert_eq!(agent.names(), ["vision-front", "vision-front.local"]);
}

#[test]
fn os_release_is_read_as_its_manual_writes_it() {
    let parsed = identity::parse_os_release(
        "# comment\nID=debian\nNAME='Debian'\nPRETTY=\"say \\\"hi\\\" \\$x\"\nnot a line\n1BAD=x\n",
    );
    let entries: Vec<(&str, &str)> = parsed
        .iter()
        .map(|(k, v)| (k.as_str(), v.as_str()))
        .collect();
    assert_eq!(
        entries,
        [
            ("ID", "debian"),
            ("NAME", "Debian"),
            ("PRETTY", "say \"hi\" $x")
        ]
    );
    let fixture = Fixture::new();
    fixture.write("/usr/lib/os-release", "ID=fallback\n");
    std::fs::remove_file(fixture.path("/etc/os-release")).expect("removed");
    let identity = fixture
        .agent(None)
        .description()
        .identity
        .expect("its identity");
    assert_eq!(
        identity.os_release.get("ID").map(String::as_str),
        Some("fallback")
    );
}
