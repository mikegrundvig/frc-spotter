//! What holds a command's output pipes open once it has exited, found as `fuser` finds it: by the
//! pipes' names (`pipe:[33352998]`) among each process's open files in Linux's `/proc`. A program
//! that started a session of its own is outside the command's group, and no descendant of it once
//! the command has exited, so only this finds it. Where `/proc` can't be read, nothing is found.

use std::collections::HashSet;
use std::fs;

use nix::sys::signal::{Signal, kill};
use nix::unistd::Pid;

/// The pipes a process writes its output and its standard error to, read as it starts: once it
/// has exited, they can no longer be read. None when it's gone already.
pub fn of(pid: u32) -> HashSet<String> {
    let mut pipes = HashSet::new();
    for fd in ["1", "2"] {
        if let Ok(target) = fs::read_link(format!("/proc/{pid}/fd/{fd}")) {
            let target = target.to_string_lossy().into_owned();
            if target.starts_with("pipe:") {
                pipes.insert(target);
            }
        }
    }
    pipes
}

/// Kills every process but the agent that holds one of these pipes: what a command left running,
/// outside its group, holding its output.
pub fn kill_holders(pipes: &HashSet<String>) {
    if pipes.is_empty() {
        return;
    }
    let agent = std::process::id();
    let Ok(processes) = fs::read_dir("/proc") else {
        return;
    };
    for process in processes.flatten() {
        let Ok(pid) = process.file_name().to_string_lossy().parse::<u32>() else {
            continue;
        };
        if pid != agent && holds(pid, pipes) {
            let _ = kill(Pid::from_raw(pid as i32), Signal::SIGKILL);
        }
    }
}

/// Whether a process has one of these pipes open: another user's, or one gone as it's read, isn't.
fn holds(pid: u32, pipes: &HashSet<String>) -> bool {
    let Ok(fds) = fs::read_dir(format!("/proc/{pid}/fd")) else {
        return false;
    };
    fds.flatten().any(|fd| {
        fs::read_link(fd.path()).is_ok_and(|target| pipes.contains(&*target.to_string_lossy()))
    })
}

/// A process's descendants, as `/proc` has them now: its children, theirs, and so on.
pub fn descendants(pid: u32) -> Vec<u32> {
    let Ok(processes) = fs::read_dir("/proc") else {
        return Vec::new();
    };
    let mut parents: Vec<(u32, u32)> = Vec::new();
    for process in processes.flatten() {
        let Ok(child) = process.file_name().to_string_lossy().parse::<u32>() else {
            continue;
        };
        if let Some(parent) = parent_of(child) {
            parents.push((child, parent));
        }
    }
    let mut found = Vec::new();
    let mut frontier = vec![pid];
    while let Some(next) = frontier.pop() {
        for (child, parent) in &parents {
            if *parent == next && !found.contains(child) {
                found.push(*child);
                frontier.push(*child);
            }
        }
    }
    found
}

/// A process's parent, from `/proc/<pid>/stat`: the field after its name, which is in brackets
/// and may hold spaces of its own.
fn parent_of(pid: u32) -> Option<u32> {
    let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let after = &stat[stat.rfind(')')? + 1..];
    after.split_whitespace().nth(1)?.parse().ok()
}
