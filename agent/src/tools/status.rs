//! `status <board> [--wait=5s]`: a board's problems first, then each value as `id  value unit
//! (label)` or `unavailable: reason` (and its level, when it's at warning or failing), then its
//! identity and its packs, read from its stream as the robot's manager reads it.

use std::collections::HashSet;
use std::io::{self, Write};
use std::net::SocketAddr;
use std::time::Duration;

use bytes::{Buf, BytesMut};
use http_body_util::{BodyExt, Empty};
use hyper_util::rt::TokioIo;
use prost::Message;
use tokio::net::TcpStream;
use tokio::time::{Instant, timeout_at};

use super::judge::{self, Level, Reading};
use super::{FOUND, OK, USAGE};
use crate::packs::duration;
use crate::proto::{Description, Event, FieldType, FieldValue, event};
use crate::protocol::{self, wire_check};

/// How long status waits for a board, unless it's told.
const WAIT: Duration = Duration::from_secs(5);

pub fn run(args: &[String], out: &mut dyn Write, err: &mut dyn Write) -> u8 {
    let mut address: Option<&str> = None;
    let mut wait = WAIT;
    for arg in args {
        if let Some(given) = arg.strip_prefix("--wait=") {
            match duration::parse(given) {
                Some(read) => wait = read,
                None => {
                    let _ = writeln!(
                        err,
                        "frc-spotter: --wait is a duration, such as 5s, not {given}"
                    );
                    return USAGE;
                }
            }
        } else if address.is_none() {
            address = Some(arg);
        } else {
            let _ = writeln!(err, "frc-spotter: status reads one board, not {arg} too");
            return USAGE;
        }
    }
    let Some(address) = address.map(str::trim).filter(|a| !a.is_empty()) else {
        let _ = writeln!(
            err,
            "frc-spotter: status needs a board: its address, or host:port"
        );
        return USAGE;
    };
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(e) => {
            let _ = writeln!(err, "frc-spotter: {e}");
            return FOUND;
        }
    };
    match runtime.block_on(read(address, wait)) {
        Ok(board) => {
            print(address, &board, out);
            OK
        }
        Err(why) => {
            let _ = writeln!(
                out,
                "{address}: not reached: {why} (waited {} s)",
                duration::seconds(wait)
            );
            FOUND
        }
    }
}

/// A board as its stream gave it: its description, each value, and its protocol's version.
struct Board {
    description: Description,
    values: Vec<Option<FieldValue>>,
    protocol: String,
}

/// Reads a board, trying again until it answers or the wait is over: why not, if it didn't.
async fn read(address: &str, wait: Duration) -> Result<Board, String> {
    let until = Instant::now() + wait;
    let mut why = "no answer in time: is it on the robot's network?".to_string();
    loop {
        match timeout_at(until, attempt(address)).await {
            Err(_) => return Err(why),
            Ok(Ok(board)) => return Ok(board),
            Ok(Err(failed)) => why = failed,
        }
        if timeout_at(until, tokio::time::sleep(Duration::from_millis(100)))
            .await
            .is_err()
        {
            return Err(why);
        }
    }
}

/// The host and port an address names: on the protocol's port unless it says.
fn place(address: &str) -> (String, u16) {
    if let Some((host, port)) = address.rsplit_once(':')
        && (!host.contains(':') || host.starts_with('['))
        && let Ok(port) = port.parse()
    {
        return (
            host.trim_start_matches('[')
                .trim_end_matches(']')
                .to_string(),
            port,
        );
    }
    (address.to_string(), protocol::PORT)
}

/// One try: connect, open the stream, and read until the description and every value have come.
async fn attempt(address: &str) -> Result<Board, String> {
    let (host, port) = place(address);
    let addresses: Vec<SocketAddr> = match tokio::net::lookup_host((host.as_str(), port)).await {
        Ok(found) => found.collect(),
        Err(_) => return Err(format!("its name doesn't resolve ({host})")),
    };
    let mut last = format!("its name doesn't resolve ({host})");
    let mut stream = None;
    for each in addresses {
        match TcpStream::connect(each).await {
            Ok(connected) => {
                stream = Some(connected);
                break;
            }
            Err(e) => last = connect_failure(&e),
        }
    }
    let stream = stream.ok_or(last)?;
    let (mut sender, connection) =
        hyper::client::conn::http1::handshake::<_, Empty<bytes::Bytes>>(TokioIo::new(stream))
            .await
            .map_err(|e| e.to_string())?;
    tokio::spawn(connection);
    let request = hyper::Request::get(format!("{}?heartbeat=1s", protocol::STREAM))
        .header(hyper::header::HOST, format!("{host}:{port}"))
        .header(hyper::header::ACCEPT, protocol::PROTOBUF)
        .body(Empty::new())
        .map_err(|e| e.to_string())?;
    let response = sender
        .send_request(request)
        .await
        .map_err(|e| e.to_string())?;
    let protocol = response
        .headers()
        .get(protocol::HEADER)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    if !protocol.starts_with("2.") {
        return Err(format!("it speaks protocol {protocol}, not 2"));
    }
    if !response.status().is_success() {
        return Err(format!("it answered {}", response.status().as_u16()));
    }
    let mut body = response.into_body();
    let mut buffer = BytesMut::new();
    let mut description: Option<Description> = None;
    let mut values: Vec<Option<FieldValue>> = Vec::new();
    let mut seen = HashSet::new();
    loop {
        while let Some(event) = next_event(&mut buffer)? {
            match event.event {
                Some(event::Event::Described(described)) => {
                    values = vec![None; described.values.len()];
                    seen.clear();
                    description = Some(described);
                }
                Some(event::Event::Values(sent)) => {
                    for value in sent.values {
                        let index = value.index as usize;
                        if index < values.len() {
                            seen.insert(index);
                            values[index] = Some(value);
                        }
                    }
                }
                _ => {}
            }
            if let Some(description) = &description
                && seen.len() == values.len()
            {
                return Ok(Board {
                    description: description.clone(),
                    values,
                    protocol,
                });
            }
        }
        match body.frame().await {
            None => return Err("its stream ended".to_string()),
            Some(Err(e)) => return Err(e.to_string()),
            Some(Ok(frame)) => {
                if let Ok(data) = frame.into_data() {
                    buffer.extend_from_slice(&data);
                }
            }
        }
    }
}

/// A connection's failure in plain words.
fn connect_failure(e: &io::Error) -> String {
    match e.kind() {
        io::ErrorKind::ConnectionRefused => {
            "connection refused: is frc-spotter running on it?".to_string()
        }
        io::ErrorKind::HostUnreachable | io::ErrorKind::NetworkUnreachable => {
            "no route to it: is it on the robot's network?".to_string()
        }
        io::ErrorKind::TimedOut => "no answer in time: is it on the robot's network?".to_string(),
        _ => e.to_string(),
    }
}

/// The next whole event in what's been read, each preceded by its length; none until one is
/// whole.
fn next_event(buffer: &mut BytesMut) -> Result<Option<Event>, String> {
    let mut peek = &buffer[..];
    let Ok(length) = prost::encoding::decode_varint(&mut peek) else {
        // Cut short: a varint needs more bytes, unless ten are there already.
        return if buffer.len() >= 10 {
            Err("an event's length can't be read".into())
        } else {
            Ok(None)
        };
    };
    if length > protocol::MAX_EVENT as u64 {
        return Err(format!(
            "an event of {length} bytes, more than an event may be"
        ));
    }
    let header = buffer.len() - peek.len();
    if peek.len() < length as usize {
        return Ok(None);
    }
    let bytes = &peek[..length as usize];
    wire_check::check(&wire_check::EVENT, bytes)?;
    let event = Event::decode(bytes).map_err(|e| e.to_string())?;
    buffer.advance(header + length as usize);
    Ok(Some(event))
}

/// A board, as status prints it.
fn print(address: &str, board: &Board, out: &mut dyn Write) {
    let description = &board.description;
    let identity = description.identity.clone().unwrap_or_default();
    let name = if identity.hostname.is_empty() {
        address
    } else {
        identity.hostname.as_str()
    };
    let _ = writeln!(
        out,
        "{name} ({address}): agent {}, protocol {}",
        description.agent_version, board.protocol
    );
    if description.problems.is_empty() {
        let _ = writeln!(out, "Problems: none");
    } else {
        let _ = writeln!(out, "Problems: {}", description.problems.len());
    }
    for problem in &description.problems {
        let _ = writeln!(out, "  {problem}");
    }
    let _ = writeln!(out, "Values: {}", description.values.len());
    let width = description
        .values
        .iter()
        .map(|v| v.id.chars().count())
        .max()
        .unwrap_or(0);
    for (declaration, value) in description.values.iter().zip(&board.values) {
        let value = value.clone().unwrap_or_default();
        let _ = writeln!(
            out,
            "  {:width$}  {}",
            declaration.id,
            shown(declaration, &value)
        );
    }
    let _ = writeln!(out, "Identity:");
    let _ = writeln!(out, "  hostname   {}", identity.hostname);
    let _ = writeln!(out, "  addresses  {}", identity.addresses.join(", "));
    let _ = writeln!(out, "  mac        {}", identity.mac);
    let _ = writeln!(out, "  boot       {}", identity.boot_id);
    if let Some(pretty) = identity.os_release.get("PRETTY_NAME") {
        let _ = writeln!(out, "  os         {pretty}");
    }
    let _ = writeln!(out, "Packs: {}", description.packs.len());
    for pack in &description.packs {
        let version = if pack.version.is_empty() {
            String::new()
        } else {
            format!(" {}", pack.version)
        };
        let kind = if pack.pushed { "pushed" } else { "installed" };
        let _ = writeln!(out, "  {}{version}  {kind}  {}", pack.name, pack.folder);
    }
}

/// A value as status prints it: `value unit (label)`, or `unavailable: reason`; its level after
/// it, unless it's ok.
fn shown(declaration: &crate::proto::FieldDeclaration, value: &FieldValue) -> String {
    let (level, reason) = judge::judge(declaration, value);
    let reading = Reading::of(value);
    let shown = match &reading {
        Reading::Unavailable(why) => format!("unavailable: {why}"),
        Reading::Number(n) if judge::is_number(declaration) => judge::number(*n, &declaration.unit),
        Reading::Flag(flag) if declaration.r#type == FieldType::Boolean as i32 => flag.to_string(),
        Reading::Status(status, message) => format!("{}: {message}", status.name()),
        Reading::Text(text) => text.clone(),
        Reading::Number(_) | Reading::Flag(_) => String::new(),
    };
    let label = if declaration.label.is_empty() || declaration.label == declaration.id {
        String::new()
    } else {
        format!("  ({})", declaration.label)
    };
    let level = if level == Level::Warning || level == Level::Failing {
        format!("  {}: {reason}", level.name())
    } else {
        String::new()
    };
    format!("{shown}{label}{level}")
}
