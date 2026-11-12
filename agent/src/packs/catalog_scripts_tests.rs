//! The catalog's scripts that read the journal, run against a stand-in journalctl first on the PATH:
//! what they make of its failing, and what they ask it for. What they make of the real one is the
//! container tests'.

use std::collections::HashMap;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use crate::testing::catalog;

/// A journalctl that prints `$JOURNAL_LINES` lines of JSON, or its kernel log (`-k`) from after its
/// cursor, a line number, saying the cursor it got to as journalctl does; then exits `$STATUS`. It
/// notes each cursor it was asked from.
const JOURNALCTL: &str = r#"#!/bin/sh
after=0
kernel=no
for argument; do
  case $argument in
    --after-cursor=*) after=${argument#--after-cursor=} ;;
    -k) kernel=yes ;;
  esac
done
echo "$after" >> "$ASKED"
if [ "${STATUS:-0}" != 0 ]; then
  echo "Failed to open the journal" >&2
  exit "$STATUS"
fi
if [ "$kernel" = yes ]; then
  lines=$(wc -l < "$KERNEL")
  [ "$lines" -gt "$after" ] || exit 0
  tail -n +"$((after + 1))" "$KERNEL"
  echo "-- cursor: $lines"
  exit 0
fi
i=0
while [ "$i" -lt "${JOURNAL_LINES:-0}" ]; do
  echo '{"MESSAGE":"line '"$i"'","PRIORITY":"6","SYSLOG_IDENTIFIER":"test"}'
  i=$((i + 1))
done
"#;

/// A folder with the stand-in journalctl, its kernel log, and the note of what it was asked.
struct Journal {
    dir: tempfile::TempDir,
}

/// What a script printed, said on its standard error, and exited with.
struct Ran {
    code: i32,
    out: String,
    err: String,
}

impl Journal {
    fn new() -> Journal {
        let dir = tempfile::tempdir().expect("a folder");
        let bin = dir.path().join("bin");
        fs::create_dir_all(&bin).expect("its bin");
        let journalctl = bin.join("journalctl");
        fs::write(&journalctl, JOURNALCTL).expect("journalctl");
        fs::set_permissions(&journalctl, fs::Permissions::from_mode(0o755)).expect("its mode");
        fs::write(dir.path().join("kernel.log"), "").expect("the kernel log");
        Journal { dir }
    }

    fn path(&self, name: &str) -> PathBuf {
        self.dir.path().join(name)
    }

    fn run(&self, script: &str, environment: &HashMap<&str, String>) -> Ran {
        let path = format!(
            "{}:{}",
            self.path("bin").display(),
            std::env::var("PATH").unwrap_or_default()
        );
        let output = Command::new("/bin/sh")
            .arg(catalog().join("debian").join(script))
            .env_remove("RUNTIME_DIRECTORY")
            .env("PATH", path)
            .env("ASKED", self.path("asked"))
            .env("KERNEL", self.path("kernel.log"))
            .envs(environment)
            .stdin(Stdio::null())
            .output()
            .expect("the script runs");
        Ran {
            code: output.status.code().expect("it exits"),
            out: String::from_utf8_lossy(&output.stdout).into_owned(),
            err: String::from_utf8_lossy(&output.stderr).into_owned(),
        }
    }

    /// Appends lines to the kernel log.
    fn logged(&self, lines: &[&str]) {
        let file = self.path("kernel.log");
        let mut text = fs::read_to_string(&file).expect("the kernel log");
        for line in lines {
            text.push_str(line);
            text.push('\n');
        }
        fs::write(&file, text).expect("the kernel log");
    }

    /// The cursors journalctl was asked from since the last time, and forgets them.
    fn asked_from(&self) -> Vec<String> {
        let file = self.path("asked");
        let asked = fs::read_to_string(&file).expect("what it was asked");
        fs::remove_file(&file).expect("forgotten");
        asked.lines().map(str::to_string).collect()
    }

    /// How many disconnects the usb collector said; none when it said nothing of them.
    fn disconnects(&self, environment: &[(&'static str, String)]) -> Option<u64> {
        let ran = self.run("usb", &environment.iter().cloned().collect());
        assert_eq!(ran.code, 0, "{}", ran.err);
        let (_, after) = ran.out.split_once("\"disconnects\": ")?;
        let digits: String = after.chars().take_while(char::is_ascii_digit).collect();
        digits.parse().ok()
    }
}

#[test]
fn the_journal_says_when_journalctl_fails_and_not_when_it_stopped_reading_it() {
    // Each row: what journalctl does, then the page's exit code, its lines, and what it says.
    type Row<'a> = (&'a str, (&'a str, &'a str), i32, usize, &'a str);
    let rows: &[Row] = &[
        ("fails", ("STATUS", "1"), 1, 0, "journalctl failed (exit 1)"),
        ("prints a few", ("JOURNAL_LINES", "3"), 0, 3, ""),
        // More than a page: the page is cut, and journalctl ends on SIGPIPE, no failure.
        (
            "prints more than a page",
            ("JOURNAL_LINES", "20000"),
            0,
            100,
            "",
        ),
    ];
    let journal = Journal::new();
    for (what, (name, value), code, lines, says) in rows {
        for from in ["latest", "before", "after"] {
            let environment = HashMap::from([
                (*name, value.to_string()),
                ("SPOTTER_FROM", from.to_string()),
                ("SPOTTER_CURSOR", "s=1".to_string()),
            ]);
            let ran = journal.run("journal", &environment);
            let case = format!("{what}, from {from}");
            assert_eq!(ran.code, *code, "{case}");
            assert_eq!(ran.out.lines().count(), *lines, "{case}");
            assert!(ran.err.contains(says), "{case}: {}", ran.err);
        }
    }
}

#[test]
fn usb_counts_disconnects_on_from_where_it_got_to() {
    let journal = Journal::new();
    let runtime = journal.path("run");
    fs::create_dir_all(&runtime).expect("the runtime folder");
    let agent = [("RUNTIME_DIRECTORY", runtime.display().to_string())];
    journal.logged(&[
        "usb 7-1: new device",
        "usb 7-1: USB disconnect, device number 2",
        "usb 8-1: USB disconnect",
    ]);
    assert_eq!(journal.disconnects(&agent), Some(2));
    assert_eq!(journal.asked_from(), ["0"]);

    // Only the lines since: two more, one a disconnect.
    journal.logged(&[
        "usb 7-1: new device",
        "usb 7-1: USB disconnect, device number 3",
    ]);
    assert_eq!(journal.disconnects(&agent), Some(3));
    assert_eq!(journal.asked_from(), ["3"]);
    assert_eq!(journal.disconnects(&agent), Some(3));
    assert_eq!(journal.asked_from(), ["5"]);

    // What it kept is another boot's: counted afresh, from the start.
    let saved: &Path = &runtime.join("debian-usb");
    let kept: Vec<String> = fs::read_to_string(saved)
        .expect("what it kept")
        .lines()
        .map(str::to_string)
        .collect();
    fs::write(saved, format!("another-boot\n{}\n{}\n", kept[1], kept[2])).expect("rewritten");
    assert_eq!(journal.disconnects(&agent), Some(3));
    assert_eq!(journal.asked_from(), ["0"]);

    // With no runtime folder, the whole boot's log, each time.
    assert_eq!(journal.disconnects(&[]), Some(3));
    assert_eq!(journal.asked_from(), ["0"]);

    // A journal it can't read: nothing said of disconnects, and what it kept stands.
    let failing = [
        ("RUNTIME_DIRECTORY", runtime.display().to_string()),
        ("STATUS", "1".to_string()),
    ];
    assert_eq!(journal.disconnects(&failing), None);
    let kept = fs::read_to_string(saved).expect("what it kept");
    assert_eq!(kept.lines().nth(1), Some("3"));
}
