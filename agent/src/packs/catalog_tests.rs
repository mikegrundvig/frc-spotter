//! The catalog's packs (the repository's packs/), installed as a board's installer puts them: each
//! loads with no problem, its programs are there and executable, and it has the values and logs
//! the manager ships names for. What they measure is the container tests'.

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use crate::packs::load::INSTALLED;
use crate::proto::Description;
use crate::testing::{Fixture, catalog, example};

/// The manager's names for the catalog's values and logs, from its source, by pack:
/// `public static final String NAME = "pack.collector.field";` in each pack's class.
const MANAGER_NAMES: &str =
    "../manager/src/main/java/com/michaelgrundvig/frc/spotter/manager/CatalogPacks.java";

/// Copies a catalog pack's own files (no build output) into the board's installed packs, each
/// executable as it is in the catalog.
fn install(fixture: &Fixture, name: &str) {
    copy(
        &catalog().join(name),
        &fixture.path(&format!("{INSTALLED}/{name}")),
    );
}

fn copy(from: &Path, to: &Path) {
    fs::create_dir_all(to).expect("its folder");
    for entry in fs::read_dir(from).expect("the catalog pack") {
        let entry = entry.expect("an entry");
        let name = entry.file_name();
        if name == "build" {
            continue;
        }
        let target = to.join(&name);
        if entry.path().is_dir() {
            copy(&entry.path(), &target);
        } else {
            fs::copy(entry.path(), &target).expect("a file");
        }
    }
}

fn described(fixture: &Fixture, names: &[&str]) -> Description {
    for name in names {
        install(fixture, name);
    }
    fixture.agent(None).description()
}

fn ids(description: &Description) -> Vec<String> {
    description.values.iter().map(|v| v.id.clone()).collect()
}

/// The names the manager ships for one pack, as its source has them.
fn shipped(pack: &str) -> Vec<String> {
    let source = fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join(MANAGER_NAMES))
        .expect("the manager's names");
    source
        .lines()
        .filter(|line| line.contains("public static final String"))
        .filter_map(|line| line.split('"').nth(1))
        .filter(|name| name.split('.').next() == Some(pack))
        .map(str::to_string)
        .collect()
}

#[test]
fn every_catalog_pack_loads_with_no_problem() {
    let description = described(&Fixture::new(), &["debian", "raspberry-pi"]);
    assert!(
        description.problems.is_empty(),
        "{:?}",
        description.problems
    );
    let names: Vec<&str> = description.packs.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(names, ["debian", "raspberry-pi"]);
}

/// Each pack's values and logs are the manager's names, in the pack's order.
#[test]
fn each_pack_has_its_values_and_logs_by_the_names_the_manager_ships() {
    let rows: &[(&str, &[&str], &[&str])] = &[
        (
            "debian",
            &[
                "debian.cpu.busiest",
                "debian.cpu.capped",
                "debian.thermal.hottest",
                "debian.thermal.margin",
                "debian.memory.available",
                "debian.disk.root-free",
                "debian.network.up",
                "debian.network.speed",
                "debian.network.drops",
                "debian.usb.devices",
                "debian.usb.disconnects",
                "debian.clock.synced",
                "debian.clock.offset",
                "debian.drive.wear",
                "debian.drive.unsafe-shutdowns",
                "debian.uptime.seconds",
            ],
            &["debian.journal"],
        ),
        (
            "raspberry-pi",
            &["raspberry-pi.power.episodes", "raspberry-pi.power.summary"],
            &[],
        ),
    ];
    for (pack, values, logs) in rows {
        let description = described(&Fixture::new(), &[pack]);
        assert_eq!(ids(&description), *values, "{pack}");
        let declared: Vec<&str> = description.logs.iter().map(|l| l.id.as_str()).collect();
        assert_eq!(declared, *logs, "{pack}");
        // Every name the manager ships is one its pack has, and it ships a name for each.
        let mut has: Vec<String> = ids(&description);
        has.extend(description.logs.iter().map(|l| l.id.clone()));
        let mut names = shipped(pack);
        assert!(!names.is_empty(), "{pack}");
        has.sort();
        names.sort();
        assert_eq!(has, names, "{pack}");
    }
}

#[test]
fn the_designs_debian_pack_is_the_catalogs() {
    // The catalog's debian pack is the design's example, as written; its scripts are the catalog's.
    let catalogs = fs::read_to_string(catalog().join("debian/pack.yaml")).expect("the catalog's");
    assert_eq!(catalogs, example("debian"));
}

#[test]
fn every_program_a_pack_runs_is_there_and_executable() {
    let rows: &[(&str, &[&str])] = &[
        (
            "debian",
            &[
                "cpu",
                "thermal",
                "memory",
                "disk",
                "network",
                "usb",
                "clock",
                "journal",
                "drive-health",
            ],
        ),
        ("raspberry-pi", &["undervoltage"]),
    ];
    for (pack, programs) in rows {
        for program in *programs {
            let file = catalog().join(pack).join(program);
            let mode = fs::metadata(&file)
                .expect("the program")
                .permissions()
                .mode();
            assert!(mode & 0o111 != 0, "{} runs", file.display());
            let text = fs::read_to_string(&file).expect("its text");
            assert!(text.starts_with("#!/bin/sh\n"), "{}", file.display());
        }
    }
}
