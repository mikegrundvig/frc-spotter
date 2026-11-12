//! Where a board's packs come from, which win, and which it trusts.

use std::sync::Arc;

use super::*;
use crate::board::host::{FixedAddresses, Owners};
use crate::testing::Fixture;

const VISION: &str = "pack: vision
version: VERSION
collectors:
  - id: web
    run: [./web]
    every: 2s
    fields:
      up: {type: boolean}
logs:
  - id: log
    run: [/opt/vision/log]
actions:
  - id: restart
    run: [systemctl, restart, vision]
";

fn vision_yaml(version: &str) -> String {
    VISION.replace("VERSION", version)
}

/// The vision pack, installed with its programs, root's alone as an installer leaves them.
fn vision(fixture: &Fixture, version: &str) -> String {
    let folder = fixture.pack("vision", &vision_yaml(version));
    fixture.script(&format!("{folder}/web"), "echo true");
    fixture.script("/opt/vision/log", "true");
    folder
}

/// The packs, taking pushed ones.
fn taking(host: &Host) -> Loaded {
    load(host, "", false)
}

fn names(loaded: &Loaded) -> Vec<&str> {
    loaded.packs.iter().map(|p| p.name.as_str()).collect()
}

fn pushed_hash(fixture: &Fixture) -> String {
    pack_hash::of_folder(&fixture.path(PUSHED)).expect("the pushed packs' hash")
}

fn owner(user: &str, mode: u32) -> Owner {
    Owner {
        user: user.into(),
        mode,
    }
}

#[test]
fn a_board_with_no_packs_has_none_and_no_problems() {
    let loaded = taking(&Fixture::new().host);
    assert!(loaded.packs.is_empty());
    assert!(loaded.problems.is_empty());
    assert!(loaded.pushed_hash.is_empty());
}

#[test]
fn installed_and_pushed_packs_load_in_the_order_of_their_names() {
    let fixture = Fixture::new();
    vision(&fixture, "1.0.0");
    fixture.pushed("detector", "pack: detector\nversion: 0.2.0\n");
    let loaded = taking(&fixture.host);
    assert!(loaded.problems.is_empty(), "{:?}", loaded.problems);
    assert_eq!(names(&loaded), ["detector", "vision"]);
    assert!(loaded.packs[0].pushed);
    assert_eq!(loaded.packs[0].folder, format!("{PUSHED}/detector"));
    assert!(!loaded.packs[1].pushed);
    assert!(!loaded.pushed_hash.is_empty());
    assert_eq!(loaded.pushed_hash, pushed_hash(&fixture));
}

/// A pushed vision 2.0.0 over an installed 1.0.0: which loads, by whether the board takes pushes.
#[test]
fn a_pushed_pack_wins_over_an_installed_one_unless_the_board_refuses_pushes() {
    let rows: &[(&str, &str, bool, &[&str])] = &[
        ("", "2.0.0", true, &[]),
        (
            "agent.json refuses pushes",
            "1.0.0",
            false,
            &["/var/lib/frc-spotter/packs: ignored, as agent.json refuses pushes"],
        ),
    ];
    for (refused, version, pushed, problems) in rows {
        let fixture = Fixture::new();
        vision(&fixture, "1.0.0");
        fixture.pushed("vision", &vision_yaml("2.0.0"));
        let loaded = load(&fixture.host, refused, false);
        let versions: Vec<&str> = loaded.packs.iter().map(|p| p.version.as_str()).collect();
        assert_eq!(versions, [*version], "{refused}");
        assert_eq!(loaded.packs[0].pushed, *pushed, "{refused}");
        assert_eq!(loaded.problems, *problems, "{refused}");
        assert_eq!(loaded.pushed_hash.is_empty(), !refused.is_empty());
    }
}

#[test]
fn an_installed_pack_whose_file_anyone_but_root_could_change_is_ignored() {
    let fixture = Fixture::new();
    fixture.pack("mine", "pack: mine\n");
    fixture.own(&format!("{INSTALLED}/mine/pack.yaml"), owner("pi", 0o644));
    fixture.pack("shared", "pack: shared\n");
    fixture.own(&format!("{INSTALLED}/shared/pack.yaml"), Owner::root(0o664));
    // A pushed pack is the agent's own, trusted because the controller alone may push.
    fixture.pushed("pushed", "pack: pushed\n");
    fixture.own(
        &format!("{PUSHED}/pushed/pack.yaml"),
        owner("frc-spotter", 0o644),
    );
    let loaded = taking(&fixture.host);
    assert_eq!(names(&loaded), ["pushed"]);
    assert_eq!(
        loaded.problems,
        [
            format!("{INSTALLED}/mine/pack.yaml: isn't root's (its owner is pi), ignored"),
            format!(
                "{INSTALLED}/shared/pack.yaml: may be written by its group or others (mode 664), ignored"
            ),
        ]
    );
}

#[test]
fn a_program_anyone_but_root_could_change_or_that_isnt_there_isnt_run_and_the_rest_of_its_pack_is()
{
    let folder = format!("{INSTALLED}/vision");
    // Each row: what's done to the programs, and what the board says of them.
    let untrusted = |fixture: &Fixture| {
        fixture.script(&format!("{folder}/web"), "echo true");
        fixture.own(&format!("{folder}/web"), owner("pi", 0o755));
        fixture.script("/opt/vision/log", "true");
        fixture.own("/opt/vision/log", Owner::root(0o775));
    };
    // What comes there later was never checked.
    let missing = |_: &Fixture| {};
    type Programs<'a> = &'a dyn Fn(&Fixture);
    let rows: [(Programs, [String; 2]); 2] = [
        (
            &untrusted,
            [
                format!("{folder}/web: isn't root's (its owner is pi), so vision's collector web isn't run"),
                "/opt/vision/log: may be written by its group or others (mode 775), so vision's log log isn't run".to_string(),
            ],
        ),
        (
            &missing,
            [
                format!("{folder}/web: isn't there, so vision's collector web isn't run"),
                "/opt/vision/log: isn't there, so vision's log log isn't run".to_string(),
            ],
        ),
    ];
    for (programs, problems) in rows {
        let fixture = Fixture::new();
        fixture.pack("vision", &vision_yaml("1.0.0"));
        programs(&fixture);
        let loaded = taking(&fixture.host);
        let vision = &loaded.packs[0];
        assert!(vision.collectors.is_empty());
        assert!(vision.logs.is_empty());
        // systemctl is found on the path, not named by one: nothing to check.
        let actions: Vec<&str> = vision.actions.iter().map(|a| a.id.as_str()).collect();
        assert_eq!(actions, ["restart"]);
        assert_eq!(loaded.problems, problems);
    }
}

#[test]
fn a_packs_own_file_given_to_an_interpreter_is_checked_too() {
    let fixture = Fixture::new();
    let folder = fixture.pack(
        "team",
        "pack: team
collectors:
  - id: check
    run: [python3, ./check.py, /opt/team/data.db]
    every: 1s
    fields:
      ok: {type: boolean}
  - id: hash
    run: [sha256sum, /opt/team/data.db]
    every: 1s
    fields:
      hash: {type: text}
",
    );
    fixture.write(&format!("{folder}/check.py"), "print('true')\n");
    fixture.own(&format!("{folder}/check.py"), owner("pi", 0o644));
    // An absolute path past the program is data, such as a file to hash: not checked.
    fixture.write("/opt/team/data.db", "data");
    fixture.own("/opt/team/data.db", owner("pi", 0o664));
    let loaded = taking(&fixture.host);
    let collectors: Vec<&str> = loaded.packs[0]
        .collectors
        .iter()
        .map(|c| c.id.as_str())
        .collect();
    assert_eq!(collectors, ["hash"]);
    assert_eq!(
        loaded.problems,
        [format!(
            "{folder}/check.py: isn't root's (its owner is pi), so team's collector check isn't run"
        )]
    );
}

#[test]
fn a_folder_on_the_way_to_a_pack_or_its_program_that_anyone_but_root_could_change_isnt_trusted() {
    let vision = format!("{INSTALLED}/vision");
    // Each row: a folder anyone but root could change, its owner, and what the board says of it.
    let rows: Vec<(&str, Owner, String)> = vec![
        (
            &vision,
            Owner::root(0o775),
            format!(
                "{vision}/pack.yaml: its folder {vision} may be written by its group or others (mode 775), ignored"
            ),
        ),
        (
            "/etc",
            owner("pi", 0o755),
            format!("{vision}/pack.yaml: its folder /etc isn't root's (its owner is pi), ignored"),
        ),
        (
            "/opt/vision",
            owner("pi", 0o755),
            "/opt/vision/log: its folder /opt/vision isn't root's (its owner is pi), so vision's log log isn't run".to_string(),
        ),
        // Where a link leads: the program as it really is.
        (
            "/srv/team",
            Owner::root(0o777),
            "/opt/vision/log: its folder /srv/team may be written by its group or others (mode 777), so vision's log log isn't run".to_string(),
        ),
    ];
    for (folder, its, says) in rows {
        let board = Fixture::new();
        let pack = board.pack("vision", &vision_yaml("1.0.0"));
        board.script(&format!("{pack}/web"), "echo true");
        board.script("/srv/team/log", "true");
        std::fs::create_dir_all(board.path("/opt/vision")).expect("its folder");
        std::os::unix::fs::symlink(board.path("/srv/team/log"), board.path("/opt/vision/log"))
            .expect("the link");
        board.own(folder, its);
        assert_eq!(taking(&board.host).problems, [says], "{folder}");
    }
}

#[test]
fn anything_but_a_packs_folder_is_ignored_and_says_why() {
    let fixture = Fixture::new();
    fixture.write(&format!("{INSTALLED}/health.yaml"), "pack: health\n");
    fixture.write(&format!("{INSTALLED}/empty/README"), "nothing here\n");
    fixture.pack("broken", "pack: broken\ncollectors: [\n");
    fixture.pack("wrong", "pack: other\n");
    let loaded = taking(&fixture.host);
    assert!(loaded.packs.is_empty());
    assert_eq!(loaded.problems.len(), 4, "{:?}", loaded.problems);
    let broken = format!("{INSTALLED}/broken/pack.yaml:3: ");
    assert!(
        loaded
            .problems
            .iter()
            .any(|p| p.starts_with(&broken) && p.ends_with(" (the pack is ignored)")),
        "{:?}",
        loaded.problems
    );
    for problem in [
        format!("{INSTALLED}/empty: has no pack.yaml, ignored"),
        format!(
            "{INSTALLED}/health.yaml: not a pack (a pack is a folder, <name>/pack.yaml), ignored"
        ),
        format!(
            "{INSTALLED}/wrong/pack.yaml:1: pack other is in a folder named wrong: a pack's folder is its name (the pack is ignored)"
        ),
    ] {
        assert!(loaded.problems.contains(&problem), "{problem}");
    }
}

#[test]
fn a_duplicate_name_ignores_the_whole_pack_and_problems_say_why() {
    let fixture = Fixture::new();
    fixture.pack(
        "vision",
        "pack: vision\ncollectors:\n  - {id: web, run: [a], every: 1s, fields: {up: {type: boolean}}}\n  - {id: web, run: [b], every: 1s, fields: {up: {type: boolean}}}\n",
    );
    let loaded = taking(&fixture.host);
    assert!(loaded.packs.is_empty());
    assert_eq!(
        loaded.problems,
        [format!(
            "{INSTALLED}/vision/pack.yaml:4: collector web is declared twice (the pack is ignored)"
        )]
    );
}

#[test]
fn a_pushed_pack_that_fails_to_load_still_counts_in_the_hash() {
    let fixture = Fixture::new();
    fixture.pushed("broken", "pack: broken\nnope: 1\n");
    let loaded = taking(&fixture.host);
    assert!(loaded.packs.is_empty());
    assert_eq!(loaded.problems.len(), 1);
    assert_eq!(loaded.pushed_hash, pushed_hash(&fixture));
}

/// A pack of `collectors` collectors, `fields` values each, and some actions.
fn sized(fixture: &Fixture, name: &str, collectors: usize, fields: usize, actions: usize) {
    let mut yaml = format!("pack: {name}\ncollectors:\n");
    for c in 0..collectors {
        yaml.push_str(&format!(
            "  - id: c{c}\n    file: /proc/uptime\n    every: 1s\n    fields:\n"
        ));
        for f in 0..fields {
            yaml.push_str(&format!("      f{f}: {{type: number}}\n"));
        }
    }
    if actions > 0 {
        yaml.push_str("actions:\n");
        for a in 0..actions {
            yaml.push_str(&format!("  - {{id: a{a}, run: [true]}}\n"));
        }
    }
    fixture.pack(name, &yaml);
}

#[test]
fn a_boards_packs_are_bounded_and_one_that_would_pass_a_bound_is_ignored_whole() {
    // Each row: the packs a board has, which load, and the one problem.
    type Packs = fn(&Fixture);
    let rows: [(Packs, Vec<String>, String); 3] = [
        (
            // 33 collectors of 64 values: 2,112, past the 2,048 a board may have.
            |fixture| {
                sized(fixture, "big", 33, 64, 0);
                sized(fixture, "small", 1, 1, 0);
            },
            vec!["small".into()],
            format!(
                "{INSTALLED}/big: ignored, as its 2112 values would pass the 2048 a board may have (0 so far)"
            ),
        ),
        (
            |fixture| {
                for i in 0..4 {
                    sized(fixture, &format!("a{i}"), 0, 0, 32);
                }
            },
            vec!["a0".into(), "a1".into(), "a2".into()],
            format!(
                "{INSTALLED}/a3: ignored, as its 32 actions would pass the 128 a board may have (98 so far)"
            ),
        ),
        (
            |fixture| {
                for i in 0..=MAX_PACKS {
                    fixture.pack(&format!("p{i:02}"), &format!("pack: p{i:02}\n"));
                }
            },
            (0..MAX_PACKS).map(|i| format!("p{i:02}")).collect(),
            format!("{INSTALLED}/p32: ignored, as a board has at most 32 packs"),
        ),
    ];
    for (packs, kept, problem) in rows {
        let fixture = Fixture::new();
        packs(&fixture);
        let loaded = taking(&fixture.host);
        assert_eq!(
            names(&loaded),
            kept.iter().map(String::as_str).collect::<Vec<_>>()
        );
        assert_eq!(loaded.problems, [problem]);
    }
}

/// Owners that can't say who owns anything of one pack's.
struct Failing;

impl Owners for Failing {
    fn of(&self, _: &Host, path: &str) -> io::Result<Owner> {
        if path.contains("/odd/") {
            return Err(io::Error::other("a filesystem in a strange state"));
        }
        Ok(Owner::root(0o644))
    }
}

/// Whatever reading one pack meets, the others load. In Java, any Throwable, caught; here, the
/// error its files return (a panic ends the agent, for systemd to start it again).
#[test]
fn whatever_reading_one_pack_meets_the_others_load() {
    let fixture = Fixture::new();
    vision(&fixture, "1.0.0");
    fixture.pack("odd", "pack: odd\n");
    let failing = Host::new(
        fixture.root.clone(),
        Box::new(Failing),
        Box::new(FixedAddresses(Vec::new())),
        Arc::new(|| 0),
        Arc::new(|_: &str| {}),
    );
    let loaded = taking(&failing);
    assert_eq!(names(&loaded), ["vision"]);
    assert_eq!(
        loaded.problems,
        [format!(
            "{INSTALLED}/odd/pack.yaml: can't be read: a filesystem in a strange state, ignored"
        )]
    );
}
