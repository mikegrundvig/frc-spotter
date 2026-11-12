//! The writes: starting an action, cancelling a run, pushing packs. Each is taken only from the
//! robot controller's address, refused, every one, while the board's `agent.json` can't be read,
//! and must be signed when the board lists trusted keys. A controller the agent only works out
//! from its own address may start the built-in actions and nothing else.

use std::fs;
use std::io::Write;
use std::net::IpAddr;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use http_body_util::BodyExt;
use hyper::body::Incoming;
use hyper::{Request, Response};
use sha2::{Digest, Sha256};
use tokio::time::{Instant, timeout_at};

use super::Server;
use super::answer::{self, Body, Refused};
use crate::actions::runs;
use crate::agent::Controller;
use crate::board::config;
use crate::packs::pack::CORE;
use crate::packs::push::{self, Refusal};
use crate::protocol;

/// How long after answering a push the agent ends, for systemd to start it again.
const EXIT_DELAY: Duration = Duration::from_millis(300);

/// Whether the controller may make a write: one that's named, any; one only worked out from the
/// board's own address, the start of a built-in action.
pub fn may(controller: &Controller, built_in: bool) -> bool {
    built_in || controller.named
}

/// Refuses a write unless the board's settings can be read and it comes from the robot
/// controller's address; and, unless it's starting a built-in action, unless that controller is
/// named.
fn writer(server: &Server, from: IpAddr, what: &str, built_in: bool) -> Result<(), Refused> {
    let unreadable = &server.agent.configuration.unreadable;
    if !unreadable.is_empty() {
        return Err(Refused::new(
            503,
            format!("every write is refused while {unreadable}"),
        ));
    }
    let controller = server.agent.controller();
    if controller.address.is_empty() {
        server.refused(&format!(
            "Refused a write ({what}) from {from}: {}",
            controller.why
        ));
        return Err(Refused::new(
            403,
            format!("writes are taken from nobody: {}", controller.why),
        ));
    }
    if from.to_string() != controller.address {
        server.refused(&format!(
            "Refused a write ({what}) from {from}: not the robot controller"
        ));
        return Err(Refused::new(
            403,
            format!(
                "only the robot controller ({}) may {what}",
                controller.address
            ),
        ));
    }
    if !may(&controller, built_in) {
        server.refused(&format!(
            "Refused a write ({what}) from {from}: a controller only worked out"
        ));
        return Err(Refused::new(
            403,
            format!(
                "this board takes only its built-in actions from a robot controller it works out from its own address ({}): name the controller, or the team, in {} to {what}",
                controller.address,
                config::PATH
            ),
        ));
    }
    Ok(())
}

/// Checks a write's signature, when the board requires them.
fn signed(
    server: &Server,
    method: &str,
    path: &str,
    body_hash: &[u8],
    signature: Option<&str>,
) -> Result<(), Refused> {
    let signatures = &server.agent.signatures;
    if !signatures.required() {
        return Ok(());
    }
    signatures
        .verify(method, path, body_hash, signature)
        .map_err(|why| {
            server.refused(&format!(
                "Refused a write, unsigned or wrongly signed: {why}"
            ));
            Refused::new(401, why)
        })
}

/// A request's parts a write needs once its body is taken: its method, path and signature.
struct Signed {
    method: String,
    path: String,
    signature: Option<String>,
}

fn parts(request: &Request<Incoming>) -> Signed {
    Signed {
        method: request.method().to_string(),
        path: request.uri().path().to_string(),
        signature: request
            .headers()
            .get(protocol::SIGNATURE)
            .and_then(|value| value.to_str().ok())
            .map(str::to_string),
    }
}

/// Reads a write's body into a file, at most `max` bytes (`413` past them), within the time a
/// request has to arrive: its SHA-256, for its signature.
async fn body(request: Request<Incoming>, into: &Path, max: u64) -> Result<Vec<u8>, Refused> {
    let until = Instant::now() + Duration::from_secs(protocol::REQUEST_SECONDS);
    let mut out = fs::File::create(into)?;
    let mut digest = Sha256::new();
    let mut total = 0u64;
    let mut body = request.into_body();
    loop {
        let frame = match timeout_at(until, body.frame()).await {
            Err(_) => {
                return Err(Refused::new(
                    408,
                    "the request took longer to arrive than the time a request has",
                ));
            }
            Ok(None) => break,
            Ok(Some(Err(e))) => {
                return Err(Refused::new(400, format!("the body couldn't be read: {e}")));
            }
            Ok(Some(Ok(frame))) => frame,
        };
        let Ok(data) = frame.into_data() else {
            continue;
        };
        total += data.len() as u64;
        if total > max {
            return Err(Refused::new(
                413,
                format!(
                    "the body is larger than the {} MiB taken",
                    max / (1024 * 1024)
                ),
            ));
        }
        digest.update(&data);
        out.write_all(&data)?;
    }
    Ok(digest.finalize().to_vec())
}

pub async fn action(
    server: &Arc<Server>,
    request: Request<Incoming>,
    from: IpAddr,
    json: bool,
    id: &str,
) -> Result<Response<Body>, Refused> {
    writer(
        server,
        from,
        "run an action",
        id.starts_with(&format!("{CORE}.")),
    )?;
    let agent = &server.agent;
    let Some(declared) = agent.runs.action(id) else {
        return Err(Refused::new(404, format!("this board has no action {id}")));
    };
    let none = runs::takes_none(&declared.action);
    let write = parts(&request);
    let input = agent.runs.input_file()?;
    let started = async {
        let hash = body(request, &input, runs::MAX_INPUT).await?;
        signed(
            server,
            &write.method,
            &write.path,
            &hash,
            write.signature.as_deref(),
        )?;
        if none && fs::metadata(&input)?.len() > 0 {
            return Err(Refused::new(400, format!("{id} takes no input")));
        }
        agent
            .runs
            .start(id, if none { None } else { Some(&input) })
            .map_err(|e| Refused {
                internal: e.status == 500,
                ..Refused::new(e.status, e.reason)
            })
    }
    .await;
    let _ = fs::remove_file(&input);
    Ok(answer::message(202, &started?, json))
}

pub async fn cancel(
    server: &Arc<Server>,
    request: Request<Incoming>,
    from: IpAddr,
    run: &str,
) -> Result<Response<Body>, Refused> {
    writer(server, from, "cancel a run", false)?;
    let write = parts(&request);
    // Its body signed as it is: at most 1 KiB of it.
    let mut body = Vec::new();
    let mut incoming = request.into_body();
    while let Some(Ok(frame)) = incoming.frame().await {
        if let Ok(data) = frame.into_data() {
            let room = 1024 - body.len();
            body.extend_from_slice(&data[..data.len().min(room)]);
        }
        if body.len() >= 1024 {
            break;
        }
    }
    signed(
        server,
        &write.method,
        &write.path,
        &Sha256::digest(&body),
        write.signature.as_deref(),
    )?;
    server
        .agent
        .runs
        .cancel(run)
        .map_err(|e| Refused::new(e.status, e.reason))?;
    Ok(answer::empty(204))
}

pub async fn push(
    server: &Arc<Server>,
    request: Request<Incoming>,
    from: IpAddr,
    _json: bool,
) -> Result<Response<Body>, Refused> {
    writer(server, from, "push packs", false)?;
    let agent = &server.agent;
    if !agent.configuration.accepts_pushes() {
        return Err(Refused::new(
            403,
            format!(
                "this board refuses pushes: {}",
                agent.configuration.pushes_refused()
            ),
        ));
    }
    let Ok(_one) = server.pushes.try_acquire() else {
        return Err(Refused::new(503, "a push is under way"));
    };
    let write = parts(&request);
    let bundle = push::bundle_file(&agent.host).map_err(|e| pushed_failed(server, e))?;
    let applied = async {
        let hash = body(request, &bundle, protocol::MAX_BUNDLE).await?;
        signed(
            server,
            &write.method,
            &write.path,
            &hash,
            write.signature.as_deref(),
        )?;
        push::apply(&agent.host, &bundle, &push::random_id()).map_err(|refusal| match refusal {
            Refusal::Rejected(why) => Refused::new(400, why),
            Refusal::Failed(e) => pushed_failed(server, e),
        })
    }
    .await;
    let _ = fs::remove_file(&bundle);
    applied?;
    // In place: the agent restarts to read them, whatever becomes of the answer (the robot may
    // have gone before it's sent).
    let agent = agent.clone();
    tokio::spawn(async move {
        tokio::time::sleep(EXIT_DELAY).await;
        agent.exit();
    });
    Ok(answer::empty(202))
}

fn pushed_failed(server: &Server, e: std::io::Error) -> Refused {
    server.agent.log(&format!("A push failed: {e}"));
    Refused::new(
        500,
        format!("the push failed, and the packs are as they were: {e}"),
    )
}
