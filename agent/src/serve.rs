//! The agent as a board runs it: `frc-spotter [serve] [--port=N] [--bind=ADDRESS]
//! [--controller=ADDRESS] [--root=DIR]`, on 5808 with nothing to configure. `agent.json` may set
//! the port, the controller's address, and the address it listens on, and the command line
//! overrides that. `--root` reads the board's files from a folder rather than `/`: a test's.
//!
//! One thread serves everything: the requests, the streams, the collectors' schedules, and the
//! commands, whose processes are waited on, never polled. Only a collector's file read goes to a
//! thread of its own, so a file that never answers can't stall the agent.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;

use tokio::signal::unix::{SignalKind, signal};
use tokio::sync::mpsc;

use crate::agent::Agent;
use crate::board::config::is_ipv4;
use crate::board::host::Host;
use crate::configuration::Configuration;
use crate::packs::{last_good, push};
use crate::server::{self, Server};

/// The command line's usage, when it's wrong.
pub const USAGE: &str =
    "Usage: frc-spotter [serve] [--port=N] [--bind=ADDRESS] [--controller=ADDRESS] [--root=DIR]";

/// The most threads a collector's file reads may take at once: one stuck on a wedged device holds
/// its thread, and the next waits its turn and times out.
pub const FILE_THREADS: usize = 4;

/// What the command line says.
#[derive(Debug, Default)]
pub struct Options {
    pub port: Option<u16>,
    pub bind: Option<String>,
    pub controller: Option<String>,
    pub root: PathBuf,
    /// A test's agent, its files counted as root's (`--fixture`); never a board's.
    pub fixture: Fixture,
}

/// What a test's agent does differently: only in a program built with the `fixture` feature.
#[derive(Debug, Default)]
pub struct Fixture {
    /// Its files count as root's, written by root alone, and its address is 127.0.0.1.
    pub on: bool,
    /// The version its description says, in place of its own.
    pub version: Option<String>,
    /// It doesn't end after a push, as one that fails to: it says it would, and runs on.
    pub stays: bool,
    /// Its addresses, in place of 127.0.0.1 alone.
    pub addresses: Option<Vec<String>>,
    /// How long a stream's write may stall before it's closed, in place of 10 s.
    pub stall: Option<std::time::Duration>,
    /// How often a stream sends every value again, in place of 10 s.
    pub complete: Option<std::time::Duration>,
}

/// `--name=value` options, after an optional `serve`; why not, when they can't be used.
pub fn options(args: &[String]) -> Result<Options, String> {
    let mut given: HashMap<String, String> = HashMap::new();
    let mut fixture = Fixture::default();
    for arg in args {
        if arg == "serve" {
            continue;
        }
        if cfg!(feature = "fixture") {
            if arg == "--fixture" {
                fixture.on = true;
                continue;
            }
            if arg == "--fixture-stays" {
                fixture.stays = true;
                continue;
            }
            if let Some(version) = arg.strip_prefix("--fixture-version=") {
                fixture.version = Some(version.to_string());
                continue;
            }
            if let Some(addresses) = arg.strip_prefix("--fixture-addresses=") {
                fixture.addresses = Some(
                    addresses
                        .split(',')
                        .filter(|a| !a.is_empty())
                        .map(str::to_string)
                        .collect(),
                );
                continue;
            }
            if let Some(complete) = arg.strip_prefix("--fixture-complete=") {
                fixture.complete = Some(
                    crate::packs::duration::parse(complete)
                        .ok_or("--fixture-complete is a duration")?,
                );
                continue;
            }
            if let Some(stall) = arg.strip_prefix("--fixture-stall=") {
                fixture.stall = Some(
                    crate::packs::duration::parse(stall).ok_or("--fixture-stall is a duration")?,
                );
                continue;
            }
        }
        let Some((name, value)) = arg.strip_prefix("--").and_then(|rest| rest.split_once('='))
        else {
            return Err(USAGE.to_string());
        };
        given.insert(name.to_string(), value.to_string());
    }
    for (option, flag) in [("controller", "--controller"), ("bind", "--bind")] {
        if let Some(address) = given.get(option)
            && !is_ipv4(address)
        {
            return Err(format!(
                "{flag} must be an IPv4 address, such as 10.12.34.2: {address}"
            ));
        }
    }
    let port = match given.get("port") {
        None => None,
        Some(port) => Some(
            port.parse::<u16>()
                .map_err(|_| format!("--port must be a port number: {port}"))?,
        ),
    };
    Ok(Options {
        port,
        bind: given.get("bind").cloned(),
        controller: given.get("controller").cloned(),
        root: PathBuf::from(given.get("root").map(String::as_str).unwrap_or("/")),
        fixture,
    })
}

/// The board, as the options say: the computer's own, or a test's.
fn host(options: &Options) -> Host {
    if options.fixture.on {
        return Host::new(
            options.root.clone(),
            Box::new(crate::board::host::RootOwners),
            Box::new(crate::board::host::FixedAddresses(
                options
                    .fixture
                    .addresses
                    .clone()
                    .unwrap_or_else(|| vec!["127.0.0.1".into()]),
            )),
            Arc::new(crate::board::host::monotonic_nanos),
            Arc::new(|line: &str| log::info!("{line}")),
        );
    }
    Host::system(options.root.clone())
}

/// The port it serves on: the command line's, else its settings', else the protocol's.
pub fn port(options: &Options, configuration: &Configuration) -> u16 {
    options.port.unwrap_or(configuration.config.port)
}

/// The address it listens on: the command line's, else its settings', else every address.
pub fn bind(options: &Options, configuration: &Configuration) -> IpAddr {
    let bind = options
        .bind
        .clone()
        .unwrap_or_else(|| configuration.config.bind.clone());
    bind.parse().unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED))
}

/// The version it says it is: its package's, unless it's a test's agent told another.
pub fn version(options: &Options) -> String {
    options
        .fixture
        .version
        .clone()
        .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_string())
}

/// Serves a board until it's stopped (SIGTERM, SIGINT) or ends itself after a push: its exit
/// code.
pub async fn serve(options: Options) -> u8 {
    let host = Arc::new(host(&options));
    let configuration = Configuration::read(&host, options.controller.is_some());
    for problem in &configuration.problems {
        host.log(problem);
    }
    let port = port(&options, &configuration);
    let bind = bind(&options, &configuration);
    let (exits, mut exit) = mpsc::unbounded_channel::<()>();
    let stays = options.fixture.stays;
    let version = version(&options);
    let told = host.clone();
    let agent = Agent::new(
        host.clone(),
        configuration,
        &version,
        options.controller.clone(),
        Box::new(move || {
            if stays {
                told.log("Would end now, for systemd to start it again: a test's agent runs on");
            } else {
                let _ = exits.send(());
            }
        }),
    );
    let listener = match server::bind(SocketAddr::new(bind, port)).await {
        Ok(listener) => listener,
        Err(e) => {
            host.log(&format!("Couldn't serve on {bind}:{port}: {e}"));
            return 1;
        }
    };
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(port);
    // Takes up the runs a previous agent kept, tidies pushed bundles, and starts the collectors.
    agent.runs.restore();
    push::tidy(&host);
    let collectors = agent.collectors.start();
    let server = tokio::spawn(
        Server::new(
            agent.clone(),
            options.fixture.stall.unwrap_or(server::STALL),
            options
                .fixture
                .complete
                .unwrap_or(std::time::Duration::from_nanos(
                    server::stream::COMPLETE_NANOS,
                )),
        )
        .serve(listener),
    );
    host.log(&format!(
        "Coprocessor agent serving protocol 2 on port {port}"
    ));
    // Once it has been up a minute, this start counts as good.
    let healthy = {
        let host = host.clone();
        tokio::spawn(async move {
            tokio::time::sleep(last_good::HEALTHY).await;
            last_good::healthy(&host);
        })
    };
    let (Ok(mut terminate), Ok(mut interrupt)) = (
        signal(SignalKind::terminate()),
        signal(SignalKind::interrupt()),
    ) else {
        host.log("Couldn't listen for the signals that stop it");
        return 1;
    };
    tokio::select! {
        _ = terminate.recv() => {}
        _ = interrupt.recv() => {}
        _ = exit.recv() => {}
    }
    // Stopped cleanly (systemctl stop or restart, a shutdown, its own exit after a push): its
    // last good start doesn't count this one as one that didn't stay up. A run going now keeps the
    // state it had, so the next start takes it up as lost, and its processes are stopped with it.
    healthy.abort();
    server.abort();
    for each in collectors {
        each.abort();
    }
    agent.runs.close();
    agent.commands.stop_all();
    last_good::stopped(&host);
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::Fixture;

    fn args(given: &[&str]) -> Vec<String> {
        given.iter().map(|a| a.to_string()).collect()
    }

    #[test]
    fn options_are_named_and_anything_else_shows_the_usage() {
        let options =
            options(&args(&["serve", "--port=5809", "--bind=127.0.0.1"])).expect("options");
        assert_eq!(options.port, Some(5809));
        assert_eq!(options.bind.as_deref(), Some("127.0.0.1"));
        assert_eq!(options.controller, None);
        let rooted = options_of(&["--root=/x"]);
        assert_eq!(rooted.root, PathBuf::from("/x"));
        let usage = super::options(&args(&["--port"])).expect_err("usage");
        assert!(usage.starts_with("Usage:"), "{usage}");
    }

    fn options_of(given: &[&str]) -> Options {
        options(&args(given)).expect("options")
    }

    #[test]
    fn a_request_has_as_long_to_arrive_as_the_manager_gives_it() {
        // As long as the manager waits for an answer, so a 16 MiB push the manager is still
        // sending, over the radio, isn't cut off.
        assert_eq!(crate::protocol::REQUEST_SECONDS, 30);
    }

    #[test]
    fn the_controller_is_an_address_never_a_name() {
        for bad in ["robot.local", "10.12.34", "10.12.34.256", "::1", ""] {
            let refused = options(&args(&[&format!("--controller={bad}")])).expect_err(bad);
            assert!(
                refused.contains("--controller must be an IPv4 address"),
                "{bad}: {refused}"
            );
        }
        assert!(options(&args(&["--controller=10.12.34.2"])).is_ok());
        let any = options(&args(&["--bind=any"])).expect_err("refused");
        assert!(any.contains("--bind must be an IPv4 address"), "{any}");
    }

    #[test]
    fn it_serves_on_5808_unless_its_settings_or_command_line_say() {
        let fixture = Fixture::new();
        let none = fixture.configuration();
        let nothing = Options::default();
        assert_eq!(port(&nothing, &none), crate::protocol::PORT);
        assert_eq!(bind(&nothing, &none), IpAddr::V4(Ipv4Addr::UNSPECIFIED));
        fixture.config("{\"port\": 5809, \"bind\": \"127.0.0.1\"}");
        let configured = fixture.configuration();
        assert_eq!(port(&nothing, &configured), 5809);
        assert_eq!(bind(&nothing, &configured), IpAddr::V4(Ipv4Addr::LOCALHOST));
        let given = options_of(&["--port=5807", "--bind=10.12.34.11"]);
        assert_eq!(port(&given, &configured), 5807);
        assert_eq!(bind(&given, &configured).to_string(), "10.12.34.11");
        // Settings that can't be read are ignored.
        fixture.config("{\"port\": 80}");
        assert_eq!(
            port(&nothing, &fixture.configuration()),
            crate::protocol::PORT
        );
    }

    #[test]
    fn its_version_is_its_packages() {
        assert_eq!(version(&Options::default()), env!("CARGO_PKG_VERSION"));
    }
}
