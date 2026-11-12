//! Pushed packs: a bundle of pack folders (`PackBundle`), written with scripts executable, swapped
//! in at once; and every bundle that isn't one the agent takes refused, nothing changed.

use crate::packs::load;
use crate::proto::PackFile;
use crate::testing::{Fixture, bundle, files};

use super::*;

/// A robot's packs, as its deploy folder has them.
fn packs(dir: &Path, name: &str, version: &str) -> PathBuf {
    let folder = dir.join(name);
    fs::create_dir_all(folder.join("vision")).expect("its folder");
    fs::write(
        folder.join("vision/pack.yaml"),
        format!("pack: vision\nversion: {version}\n"),
    )
    .expect("its pack.yaml");
    fs::write(folder.join("vision/check"), "#!/bin/sh\necho ok\n").expect("its script");
    fs::set_permissions(
        folder.join("vision/check"),
        fs::Permissions::from_mode(0o775),
    )
    .expect("its mode");
    folder
}

/// A bundle, as the manager sends it, in the file the request is read to.
fn sent(fixture: &Fixture, bundle: &PackBundle) -> PathBuf {
    let body = bundle_file(&fixture.host).expect("a file for the bundle");
    fs::write(&body, bundle.encode_to_vec()).expect("the bundle");
    body
}

fn hash(folder: &Path) -> String {
    pack_hash::of_folder(folder).expect("its hash")
}

fn mode(file: &Path) -> u32 {
    fs::metadata(file).expect("the file").permissions().mode() & 0o777
}

fn count(folder: &Path) -> usize {
    fs::read_dir(folder).expect("the folder").count()
}

#[test]
fn a_bundle_is_put_in_place_as_its_packs_with_their_hash() {
    let fixture = Fixture::new();
    let robot = packs(fixture.folder(), "robot", "1.0.0");
    apply(&fixture.host, &sent(&fixture, &bundle(&robot)), "1").expect("pushed");
    let pushed = fixture.path(PUSHED);
    assert!(
        fs::symlink_metadata(&pushed)
            .expect("the link")
            .file_type()
            .is_symlink()
    );
    let yaml = fs::read_to_string(pushed.join("vision/pack.yaml")).expect("its pack.yaml");
    assert!(yaml.contains("1.0.0"));
    assert_eq!(mode(&pushed.join("vision/check")), 0o755);
    assert_eq!(mode(&pushed.join("vision/pack.yaml")), 0o644);
    // The robot's hash of its folder, and of its bundle, is the board's of what was put in place.
    assert_eq!(hash(&pushed), hash(&robot));
    assert_eq!(hash(&robot), pack_hash::of_bundle(&bundle(&robot)));
    assert_eq!(
        load::load(&fixture.host, "", false).pushed_hash,
        hash(&robot)
    );
    assert!(
        fixture
            .log()
            .contains(&format!("Packs pushed ({})", hash(&robot)))
    );

    // Pushed again: swapped in one step, the old bundle tidied away.
    let next = packs(fixture.folder(), "next", "2.0.0");
    apply(&fixture.host, &sent(&fixture, &bundle(&next)), "2").expect("pushed again");
    let yaml = fs::read_to_string(pushed.join("vision/pack.yaml")).expect("its pack.yaml");
    assert!(yaml.contains("2.0.0"));
    assert_eq!(count(&fixture.path(BUNDLES)), 1);
}

#[test]
fn packs_put_there_by_hand_are_kept_aside_by_the_first_push_and_stay_through_tidying() {
    let fixture = Fixture::new();
    fixture.pushed("old", "pack: old\n");
    let robot = packs(fixture.folder(), "robot", "1.0.0");
    apply(&fixture.host, &sent(&fixture, &bundle(&robot)), "1").expect("pushed");
    assert!(!fixture.path(&format!("{PUSHED}/old")).exists());
    assert!(fixture.path(&format!("{PUSHED}/vision/pack.yaml")).exists());
    tidy(&fixture.host);
    let kept = fs::read_to_string(fixture.path(&format!("{BUNDLES}/before-1/old/pack.yaml")));
    assert_eq!(kept.expect("kept aside"), "pack: old\n");
}

#[test]
fn packs_put_there_by_hand_are_back_where_they_were_when_the_swap_fails() {
    let fixture = Fixture::new();
    fixture.pushed("old", "pack: old\n");
    // Where the swap's link is made, something already: making it fails, past the move aside.
    fixture.write("/var/lib/frc-spotter/.packs-1", "in the way");
    let robot = packs(fixture.folder(), "robot", "1.0.0");
    let body = sent(&fixture, &bundle(&robot));
    let failed = apply(&fixture.host, &body, "1");
    assert!(matches!(failed, Err(Refusal::Failed(_))), "{failed:?}");
    let pushed = fixture.path(PUSHED);
    assert!(
        !fs::symlink_metadata(&pushed)
            .expect("the packs")
            .is_symlink()
    );
    let old = fs::read_to_string(pushed.join("old/pack.yaml")).expect("put back");
    assert_eq!(old, "pack: old\n");
    assert!(!fixture.path(&format!("{BUNDLES}/1")).exists());
    assert!(!fixture.path(&format!("{BUNDLES}/before-1")).exists());
    assert!(!body.exists());
}

/// Applies a received body that must be refused: why. The body is gone, refused or not.
fn rejected(fixture: &Fixture, body: &Path) -> String {
    match apply(&fixture.host, body, "refused") {
        Err(Refusal::Rejected(why)) => {
            assert!(!body.exists(), "{why}");
            why
        }
        other => panic!("refused, not {other:?}"),
    }
}

fn refused(fixture: &Fixture, bundle: &PackBundle) -> String {
    rejected(fixture, &sent(fixture, bundle))
}

#[test]
fn a_bundle_the_agent_doesnt_take_is_refused_and_nothing_changes() {
    let fixture = Fixture::new();
    let robot = packs(fixture.folder(), "robot", "1.0.0");
    apply(&fixture.host, &sent(&fixture, &bundle(&robot)), "1").expect("pushed");
    let before = hash(&fixture.path(PUSHED));

    let not_a_bundle = bundle_file(&fixture.host).expect("a file");
    // Field 1, said to be 127 bytes long, and then nothing.
    fs::write(&not_a_bundle, [0x0a, 0x7f]).expect("the body");
    let why = rejected(&fixture, &not_a_bundle);
    assert!(why.starts_with("the bundle isn't a PackBundle"), "{why}");

    for outside in [
        "../../../etc/evil",
        "/etc/evil",
        "vision/../../evil",
        "",
        "vision//check",
        "./x",
        "a\\b",
    ] {
        assert_eq!(
            refused(&fixture, &files(&[(outside, "x")])),
            format!("the bundle names a path outside its folder: {outside}")
        );
    }
    assert!(!fixture.path("/etc/evil").exists());
    for twice in [
        [("vision/pack.yaml", "a"), ("vision/pack.yaml", "b")],
        [("vision", "a"), ("vision/pack.yaml", "b")],
    ] {
        assert_eq!(
            refused(&fixture, &files(&twice)),
            "the bundle names a file twice: vision/pack.yaml"
        );
    }
    assert_eq!(hash(&fixture.path(PUSHED)), before);
}

#[test]
fn a_bundle_that_says_more_than_it_holds_is_refused_before_its_parsed() {
    let fixture = Fixture::new();
    let lying = bundle_file(&fixture.host).expect("a file");
    // PackBundle { files: PackFile { content: 2 GiB, and nothing after it } }.
    fs::write(&lying, [0x0a, 0x06, 0x1a, 0xff, 0xff, 0xff, 0xff, 0x07]).expect("the body");
    assert_eq!(
        rejected(&fixture, &lying),
        "the bundle isn't a PackBundle that can be read safely: spotter.v2.PackFile's field 3 says 2147483647 bytes, and 0 are left"
    );
}

#[test]
fn a_bundle_over_its_limits_is_refused() {
    let fixture = Fixture::new();
    let many = PackBundle {
        files: (0..=protocol::MAX_FILES)
            .map(|i| PackFile {
                path: format!("vision/{i}"),
                ..PackFile::default()
            })
            .collect(),
    };
    assert_eq!(
        refused(&fixture, &many),
        "the bundle holds 4097 files, more than 4096"
    );
    let half = vec![0u8; (protocol::MAX_BUNDLE / 2) as usize];
    let big = PackBundle {
        files: ["a", "b", "c"]
            .iter()
            .map(|name| PackFile {
                path: format!("vision/{name}"),
                executable: false,
                content: half.clone(),
            })
            .collect(),
    };
    assert_eq!(refused(&fixture, &big), "the bundle holds more than 16 MiB");
}

#[test]
fn tidying_removes_what_the_link_doesnt_point_at() {
    let fixture = Fixture::new();
    let robot = packs(fixture.folder(), "robot", "1.0.0");
    apply(&fixture.host, &sent(&fixture, &bundle(&robot)), "1").expect("pushed");
    fs::create_dir_all(fixture.path(&format!("{BUNDLES}/half-unpacked"))).expect("a folder");
    fixture.write(&format!("{BUNDLES}/.bundle-1.pb"), "left");
    tidy(&fixture.host);
    assert_eq!(count(&fixture.path(BUNDLES)), 1);
    assert!(fixture.path(&format!("{PUSHED}/vision/pack.yaml")).exists());
}
