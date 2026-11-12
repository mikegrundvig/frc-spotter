//! Where each request goes, by its path and method: the reads here, the writes in `writes`, the
//! stream in `stream`.

use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;

use hyper::body::Incoming;
use hyper::{Method, Request, Response};
use tokio::sync::Notify;

use super::answer::{self, Body, Refused};
use super::{Server, UnderWay, stream, writes};
use crate::actions::runs;
use crate::logs::Paging;
use crate::packs::reader::is_id;
use crate::protocol;

pub async fn route(
    server: &Arc<Server>,
    request: Request<Incoming>,
    from: IpAddr,
    json: bool,
    kill: Arc<Notify>,
    under_way: UnderWay,
) -> Result<Response<Body>, Refused> {
    let path = request.uri().path().to_string();
    let agent = &server.agent;
    if path == protocol::DESCRIBE {
        only(&request, &path, Method::GET)?;
        Ok(answer::message(200, &agent.description(), json))
    } else if path == protocol::VALUES {
        only(&request, &path, Method::GET)?;
        Ok(answer::message(200, &agent.values(), json))
    } else if path == protocol::STREAM {
        only(&request, &path, Method::GET)?;
        stream::open(server, &request, from, json, kill, under_way)
    } else if path == protocol::PACKS {
        only(&request, &path, Method::POST)?;
        writes::push(server, request, from, json).await
    } else if let Some(id) = path.strip_prefix(protocol::LOGS) {
        let id = name(id)?;
        only(&request, &path, Method::GET)?;
        let paging = paging(&request)?;
        let page = agent
            .logs
            .page(&id, &paging)
            .await
            .map_err(|e| Refused::new(e.status, e.reason))?;
        Ok(answer::message(200, &page, json))
    } else if let Some(id) = path.strip_prefix(protocol::ACTIONS) {
        let id = name(id)?;
        only(&request, &path, Method::POST)?;
        writes::action(server, request, from, json, &id).await
    } else if let Some(rest) = path.strip_prefix(protocol::RUNS) {
        let parts: Vec<String> = rest.split('/').map(str::to_string).collect();
        self::runs(server, request, from, json, &path, &parts).await
    } else {
        fixture(server, request, from, &path).await
    }
}

/// Refuses a request whose method isn't the one its path takes.
fn only(request: &Request<Incoming>, path: &str, method: Method) -> Result<(), Refused> {
    if request.method() == method {
        return Ok(());
    }
    let allow = if method == Method::GET { "GET" } else { "POST" };
    Err(Refused {
        allow: Some(allow),
        ..Refused::new(405, format!("{path} takes {allow} only"))
    })
}

/// A name in a path, as packs name things; `404` for anything else.
fn name(text: &str) -> Result<String, Refused> {
    if is_id(text) {
        Ok(text.to_string())
    } else {
        Err(Refused::new(404, format!("no such resource: {text}")))
    }
}

async fn runs(
    server: &Arc<Server>,
    request: Request<Incoming>,
    from: IpAddr,
    json: bool,
    path: &str,
    parts: &[String],
) -> Result<Response<Body>, Refused> {
    let run = &parts[0];
    if !runs::is_id(run) {
        return Err(Refused::new(404, format!("no run {run} is kept")));
    }
    let agent = &server.agent;
    let refused = |e: runs::Refused| Refused::new(e.status, e.reason);
    match parts.len() {
        1 if request.method() == Method::DELETE => writes::cancel(server, request, from, run).await,
        1 if request.method() == Method::GET => {
            let state = agent
                .runs
                .state(run)
                .ok_or_else(|| Refused::new(404, format!("no run {run} is kept")))?;
            Ok(answer::message(200, &state, json))
        }
        1 => Err(Refused {
            allow: Some("GET, DELETE"),
            ..Refused::new(405, format!("{path} takes GET or DELETE only"))
        }),
        2 if parts[1] == "log" => {
            only(&request, path, Method::GET)?;
            let paging = paging(&request)?;
            Ok(answer::message(
                200,
                &agent.runs.log(run, &paging).map_err(refused)?,
                json,
            ))
        }
        3 if parts[1] == "files" => {
            only(&request, path, Method::GET)?;
            let field = name(&parts[2])?;
            let download = agent.runs.file(run, &field).map_err(refused)?;
            download_file(&download)
        }
        _ => Err(Refused::new(404, format!("no such resource: {path}"))),
    }
}

/// A run's file field, as its bytes, to download as the pack names it.
fn download_file(download: &runs::Download) -> Result<Response<Body>, Refused> {
    let bytes = std::fs::read(&download.path)?;
    let mut response = answer::response(
        200,
        Body::Whole((!bytes.is_empty()).then(|| bytes::Bytes::from(bytes))),
    );
    let name: String = download
        .name
        .chars()
        .map(|c| {
            if matches!(c, '"' | '\\' | '\r' | '\n') {
                '_'
            } else {
                c
            }
        })
        .collect();
    let headers = response.headers_mut();
    headers.insert(
        hyper::header::CONTENT_TYPE,
        hyper::header::HeaderValue::from_static("application/octet-stream"),
    );
    if let Ok(disposition) =
        hyper::header::HeaderValue::from_str(&format!("attachment; filename=\"{name}\""))
    {
        headers.insert(hyper::header::CONTENT_DISPOSITION, disposition);
    }
    Ok(response)
}

fn paging(request: &Request<Incoming>) -> Result<Paging, Refused> {
    Paging::of(&query(request.uri().query())?).map_err(|e| Refused::new(e.status, e.reason))
}

/// A request's query, URL-decoded: each key's last value.
pub fn query(raw: Option<&str>) -> Result<HashMap<String, String>, Refused> {
    let mut query = HashMap::new();
    let Some(raw) = raw.filter(|raw| !raw.is_empty()) else {
        return Ok(query);
    };
    // As Java's URLDecoder: a % not followed by two hex digits is no URL encoding.
    let bytes = raw.as_bytes();
    let escaped = |at: usize| bytes.get(at).is_some_and(u8::is_ascii_hexdigit);
    for (i, byte) in bytes.iter().enumerate() {
        if *byte == b'%' && !(escaped(i + 1) && escaped(i + 2)) {
            return Err(Refused::new(400, "the query isn't URL-encoded"));
        }
    }
    for (key, value) in form_urlencoded::parse(raw.as_bytes()) {
        query.insert(key.into_owned(), value.into_owned());
    }
    Ok(query)
}

/// What only a test's agent takes, from the test on this computer: a value set as its collector
/// would (`POST /fixture/values/<id>`, a `FieldValue` in protobuf), several at once (`POST
/// /fixture/values`, a `Values`, each naming its id), and how many streams are open (`GET
/// /fixture/streams`). A board's agent has none.
#[cfg(feature = "fixture")]
async fn fixture(
    server: &Arc<Server>,
    request: Request<Incoming>,
    from: IpAddr,
    path: &str,
) -> Result<Response<Body>, Refused> {
    use http_body_util::BodyExt;
    use prost::Message;
    if !from.is_loopback() {
        return Err(Refused::new(
            403,
            "a test's requests come from this computer only",
        ));
    }
    // How many streams are open, as text.
    if path == "/fixture/streams" {
        let open = server.streams().to_string();
        return Ok(answer::response(
            200,
            Body::Whole(Some(bytes::Bytes::from(open))),
        ));
    }
    let bytes = request
        .into_body()
        .collect()
        .await
        .map_err(|e| Refused::new(400, e.to_string()))?
        .to_bytes();
    // Several values at once, a `Values` whose each names its value's id, set one after another
    // as the agent's own thread sets them: no stream sends between them.
    let values: Vec<(String, crate::proto::FieldValue)> = if path == "/fixture/values" {
        crate::proto::Values::decode(bytes)
            .map_err(|e| Refused::new(400, e.to_string()))?
            .values
            .into_iter()
            .map(|value| (value.name.clone(), value))
            .collect()
    } else if let Some(id) = path.strip_prefix("/fixture/values/") {
        let value = crate::proto::FieldValue::decode(bytes)
            .map_err(|e| Refused::new(400, e.to_string()))?;
        vec![(id.to_string(), value)]
    } else {
        return Err(Refused::new(404, format!("no such resource: {path}")));
    };
    let description = server.agent.description();
    for (id, value) in values {
        let index = description
            .values
            .iter()
            .position(|declared| declared.id == id)
            .ok_or_else(|| Refused::new(404, format!("no value {id}")))?;
        let value = crate::proto::FieldValue {
            name: String::new(),
            ..value
        };
        server.agent.store.set(index, vec![value]);
    }
    Ok(answer::empty(204))
}

#[cfg(not(feature = "fixture"))]
async fn fixture(
    _: &Arc<Server>,
    _: Request<Incoming>,
    _: IpAddr,
    path: &str,
) -> Result<Response<Body>, Refused> {
    Err(Refused::new(404, format!("no such resource: {path}")))
}
