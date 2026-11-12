//! The agent as a board runs it: its .deb installed in a Debian 13 container under systemd, its
//! unit's sandbox and all, read from outside as the robot reads it and written to from the
//! controller's address. It needs a container runtime (Docker, or rootless Podman with SYS_ADMIN in
//! its user namespace and no SELinux labels) and the .deb to install (`SPOTTER_DEB`, as
//! `package/package.sh` makes it). Without either it skips, saying so; with
//! `SPOTTER_REQUIRE_CONTAINERS=true` (CI on Linux) it fails instead.

use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::{Duration, Instant};

use frc_spotter::proto::{Description, Values, field_value};
use prost::Message;

/// The longest the board takes to boot, its agent to start, or a run to finish.
const WAIT: Duration = Duration::from_secs(90);

/// Why the test can't run here, or the runtime and the .deb it runs with.
fn needs() -> Result<(String, PathBuf), String> {
    let runtime = ["podman", "docker"]
        .into_iter()
        .find(|runtime| {
            Command::new(runtime)
                .arg("--version")
                .output()
                .is_ok_and(|o| o.status.success())
        })
        .ok_or("no container runtime (podman or docker) is installed")?;
    let deb = std::env::var("SPOTTER_DEB")
        .map_err(|_| "SPOTTER_DEB names no .deb to install".to_string())?;
    let deb = PathBuf::from(deb);
    if !deb.is_file() {
        return Err(format!(
            "SPOTTER_DEB names {}, which isn't there",
            deb.display()
        ));
    }
    Ok((runtime.to_string(), deb))
}

fn run(runtime: &str, args: &[&str]) -> Output {
    Command::new(runtime)
        .args(args)
        .output()
        .expect("the container runtime runs")
}

fn ok(runtime: &str, args: &[&str]) -> String {
    let output = run(runtime, args);
    assert!(
        output.status.success(),
        "{runtime} {args:?} failed: {}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    String::from_utf8_lossy(&output.stdout).trim().to_string()
}

/// The image's folder: the Containerfile, the .deb, the packs, agent.json.
fn context(deb: &Path) -> tempfile::TempDir {
    let dir = tempfile::tempdir().expect("a folder");
    let here = Path::new(env!("CARGO_MANIFEST_DIR"));
    std::fs::copy(
        here.join("tests/container/Containerfile"),
        dir.path().join("Containerfile"),
    )
    .expect("copied");
    std::fs::copy(deb, dir.path().join("frc-spotter.deb")).expect("copied");
    let packs = dir.path().join("packs");
    copy(&here.join("../packs/debian"), &packs.join("debian"));
    write(
        &packs.join("standin/pack.yaml"),
        "pack: standin\ncollectors:\n  - id: health\n    run: [./health]\n    every: 1s\n    fields:\n      fps: {type: number}\n      user: {type: text}\nactions:\n  - id: hello\n    run: [./hello]\n  - id: escape\n    run: [./escape]\n",
    );
    script(
        &packs.join("standin/health"),
        "echo \"{\\\"fps\\\": 60, \\\"user\\\": \\\"$(id -un)\\\"}\"",
    );
    script(&packs.join("standin/hello"), "echo hello; echo logged >&2");
    // Outside the sandbox's writable folders: refused, read-only.
    script(&packs.join("standin/escape"), "touch /opt/escaped");
    write(&packs.join("untrusted/pack.yaml"), "pack: untrusted\n");
    write(
        &dir.path().join("agent.json"),
        "{\"controller\": \"127.0.0.1\"}\n",
    );
    dir
}

fn write(file: &Path, text: &str) {
    std::fs::create_dir_all(file.parent().expect("a folder")).expect("its folders");
    std::fs::write(file, text).expect("written");
}

fn script(file: &Path, body: &str) {
    use std::os::unix::fs::PermissionsExt;
    write(file, &format!("#!/bin/sh\n{body}\n"));
    std::fs::set_permissions(file, std::fs::Permissions::from_mode(0o755)).expect("its mode");
}

fn copy(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).expect("a folder");
    for entry in std::fs::read_dir(from).expect("the catalog's pack") {
        let entry = entry.expect("an entry");
        std::fs::copy(entry.path(), to.join(entry.file_name())).expect("copied");
    }
}

/// A request from inside the board: the controller's address there is 127.0.0.1.
fn curl(runtime: &str, name: &str, args: &[&str]) -> Vec<u8> {
    let mut all = vec!["exec", name, "curl", "-sS", "--max-time", "10"];
    all.extend_from_slice(args);
    let output = run(runtime, &all);
    assert!(
        output.status.success(),
        "curl {args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    output.stdout
}

fn until(what: &str, mut done: impl FnMut() -> bool) {
    let start = Instant::now();
    while !done() {
        assert!(start.elapsed() < WAIT, "waited {WAIT:?} for {what}");
        std::thread::sleep(Duration::from_millis(200));
    }
}

#[test]
fn the_installed_package_serves_under_systemd_in_its_sandbox() {
    let (runtime, deb) = match needs() {
        Ok(found) => found,
        Err(why) if std::env::var("SPOTTER_REQUIRE_CONTAINERS").as_deref() == Ok("true") => {
            panic!("the container test must run here, and can't: {why}")
        }
        Err(why) => {
            eprintln!(
                "skipped: the container test needs a container runtime and the agent's .deb: {why}"
            );
            return;
        }
    };
    let dir = context(&deb);
    let image = "frc-spotter-container-test";
    let folder = dir.path().to_str().expect("a path");
    ok(
        &runtime,
        &[
            "build",
            "-q",
            "-t",
            image,
            "-f",
            &format!("{folder}/Containerfile"),
            folder,
        ],
    );
    let name = format!("frc-spotter-test-{}", std::process::id());
    let privileges: &[&str] = if runtime == "docker" {
        &[
            "--privileged",
            "--cgroupns=host",
            "-v",
            "/sys/fs/cgroup:/sys/fs/cgroup:rw",
        ]
    } else {
        &[
            "--cap-add",
            "SYS_ADMIN",
            "--security-opt",
            "label=disable",
            "--systemd=always",
        ]
    };
    let mut start = vec!["run", "-d", "--name", &name, "--hostname", "vision-front"];
    start.extend_from_slice(privileges);
    start.push(image);
    ok(&runtime, &start);
    let stopped = Stopper {
        runtime: runtime.clone(),
        name: name.clone(),
    };

    let unit = |property: &str| {
        ok(
            &runtime,
            &[
                "exec",
                &name,
                "systemctl",
                "show",
                "frc-spotter.service",
                "-p",
                property,
                "--value",
            ],
        )
    };
    until("the agent to be active", || {
        run(
            &runtime,
            &[
                "exec",
                &name,
                "systemctl",
                "is-active",
                "frc-spotter.service",
            ],
        )
        .status
        .success()
    });
    until("the agent to listen", || {
        run(
            &runtime,
            &[
                "exec",
                &name,
                "curl",
                "-sf",
                "http://127.0.0.1:5808/v2/describe",
                "-o",
                "/dev/null",
            ],
        )
        .status
        .success()
    });

    // Its description: the board's identity, the packs it trusts, and the one it doesn't.
    let description = Description::decode(
        curl(&runtime, &name, &["http://127.0.0.1:5808/v2/describe"]).as_slice(),
    )
    .expect("a description");
    // With the agent's journal, should it fail: a board whose identity can't be read says why there.
    assert_eq!(
        description
            .identity
            .as_ref()
            .expect("its identity")
            .hostname,
        "vision-front",
        "the agent's journal:\n{}",
        ok(
            &runtime,
            &[
                "exec",
                &name,
                "journalctl",
                "-u",
                "frc-spotter.service",
                "--no-pager",
                "-n",
                "50"
            ]
        )
    );
    // Its addresses, read in its sandbox: the container's one, on its runtime's network.
    assert!(
        !description
            .identity
            .as_ref()
            .expect("its identity")
            .addresses
            .is_empty(),
        "no addresses in {:?}",
        description.identity
    );
    let packs: Vec<&str> = description.packs.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(packs, ["debian", "standin"]);
    assert!(
        description
            .problems
            .iter()
            .any(|p| p.contains("/untrusted/pack.yaml: isn't root's")),
        "{:?}",
        description.problems
    );

    // Its collectors run as its own user, in its sandbox.
    until("the stand-in's values", || {
        let values =
            Values::decode(curl(&runtime, &name, &["http://127.0.0.1:5808/v2/values"]).as_slice())
                .expect("values");
        let user = description
            .values
            .iter()
            .position(|v| v.id == "standin.health.user")
            .expect("its user");
        values.values[user].value == Some(field_value::Value::Text("frc-spotter".into()))
    });

    // An action from the controller, and one that writes outside the sandbox, refused.
    for (action, outcome) in [
        ("standin.hello", "hello"),
        ("standin.escape", "Read-only file system"),
    ] {
        let started = curl(
            &runtime,
            &name,
            &[
                "-X",
                "POST",
                &format!("http://127.0.0.1:5808/v2/actions/{action}"),
            ],
        );
        let run_id = frc_spotter::proto::Started::decode(started.as_slice())
            .expect("started")
            .run;
        until(&format!("{action} to finish"), || {
            let state = frc_spotter::proto::RunState::decode(
                curl(
                    &runtime,
                    &name,
                    &[&format!("http://127.0.0.1:5808/v2/runs/{run_id}")],
                )
                .as_slice(),
            )
            .expect("a run");
            !state.running
        });
        let output = String::from_utf8_lossy(&curl(
            &runtime,
            &name,
            &[
                "-H",
                "Accept: application/json",
                &format!("http://127.0.0.1:5808/v2/runs/{run_id}/log"),
            ],
        ))
        .into_owned()
            + &String::from_utf8_lossy(&curl(
                &runtime,
                &name,
                &[
                    "-H",
                    "Accept: application/json",
                    &format!("http://127.0.0.1:5808/v2/runs/{run_id}"),
                ],
            ));
        assert!(output.contains(outcome), "{action}: {output}");
    }

    // The unit as its package installs it, and never restarted on the way.
    assert_eq!(unit("User"), "frc-spotter");
    assert_eq!(unit("NRestarts"), "0");
    assert_eq!(unit("MemoryMax"), (200u64 * 1024 * 1024).to_string());
    // A clean stop and start: still never restarted by systemd.
    ok(
        &runtime,
        &["exec", &name, "systemctl", "restart", "frc-spotter.service"],
    );
    until("the agent to listen again", || {
        run(
            &runtime,
            &[
                "exec",
                &name,
                "curl",
                "-sf",
                "http://127.0.0.1:5808/v2/describe",
                "-o",
                "/dev/null",
            ],
        )
        .status
        .success()
    });
    assert_eq!(unit("NRestarts"), "0");

    // Power-off, from the controller: the board goes down.
    curl(
        &runtime,
        &name,
        &[
            "-X",
            "POST",
            "http://127.0.0.1:5808/v2/actions/core.power-off",
        ],
    );
    until("the board to power off", || {
        ok(&runtime, &["inspect", "-f", "{{.State.Running}}", &name]) == "false"
    });
    drop(stopped);
}

/// Removes the board's container however the test ends.
struct Stopper {
    runtime: String,
    name: String,
}

impl Drop for Stopper {
    fn drop(&mut self) {
        let _ = Command::new(&self.runtime)
            .args(["rm", "-f", &self.name])
            .output();
    }
}
