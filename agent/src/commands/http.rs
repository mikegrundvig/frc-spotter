//! An `http` command: a request to the board itself, its answer's status and body. Bounded by its
//! timeout, from connecting to the end of the body, and by the most of its body kept. Its input,
//! when it has one, is sent as the body, or as a multipart form's one file field.

use std::fs;
use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::Path;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use bytes::Bytes;
use http_body_util::BodyExt;
use hyper::body::{Body, Frame, SizeHint};
use hyper_util::rt::TokioIo;
use tokio::net::TcpStream;

use super::{Cancellation, Options, Ran};
use crate::packs::command::{Http, Kind, Method};
use crate::packs::duration;
use crate::proto::Outcome;

pub async fn run(
    http: &Http,
    timeout: Duration,
    options: Options,
    cancellation: &Cancellation,
) -> Ran {
    let Ok(uri) = http.url.parse::<hyper::Uri>() else {
        return unreachable(format!("not a URL: {}", http.url));
    };
    let host = uri.host().unwrap_or("").to_string();
    let port = uri.port_u16().unwrap_or(80);
    let place = format!("{host}:{port}");
    let exchange = exchange(http, &uri, &host, port, &place, options);
    tokio::select! {
        ran = tokio::time::timeout(timeout.max(Duration::from_millis(1)), exchange) => match ran {
            Ok(ran) => ran,
            Err(_) => Ran::failed(
                Kind::Http,
                Outcome::TimedOut,
                format!("no answer within {} ({place})", duration::show(timeout)),
            ),
        },
        _ = cancellation.cancelled() => Ran::failed(Kind::Http, Outcome::Cancelled, "cancelled"),
    }
}

fn unreachable(message: String) -> Ran {
    Ran::failed(Kind::Http, Outcome::Unreachable, message)
}

/// The addresses a local URL's host names: `localhost` is the board's loopback, IPv4's first.
fn addresses(host: &str, port: u16) -> Vec<SocketAddr> {
    let trimmed = host.trim_start_matches('[').trim_end_matches(']');
    match trimmed.parse::<IpAddr>() {
        Ok(ip) => vec![SocketAddr::new(ip, port)],
        Err(_) => vec![
            SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port),
            SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), port),
        ],
    }
}

async fn exchange(
    http: &Http,
    uri: &hyper::Uri,
    host: &str,
    port: u16,
    place: &str,
    options: Options,
) -> Ran {
    let mut connected = None;
    let mut refused = None;
    for address in addresses(host, port) {
        match TcpStream::connect(address).await {
            Ok(stream) => {
                connected = Some(stream);
                break;
            }
            Err(e) => refused = Some(e),
        }
    }
    let Some(stream) = connected else {
        let e = refused.unwrap_or_else(|| io::Error::other("no address"));
        return unreachable(match e.kind() {
            io::ErrorKind::ConnectionRefused => format!("connection refused ({place})"),
            io::ErrorKind::HostUnreachable | io::ErrorKind::NetworkUnreachable => {
                format!("no route ({place})")
            }
            _ => format!("{e} ({place})"),
        });
    };
    let (mut sender, connection) =
        match hyper::client::conn::http1::handshake::<_, Upload>(TokioIo::new(stream)).await {
            Ok(both) => both,
            Err(e) => return unreachable(format!("{e} ({place})")),
        };
    let connection = tokio::spawn(connection);
    let body = match upload(http, options.input.as_deref()) {
        Ok(body) => body,
        Err(e) => {
            return Ran::failed(
                Kind::Http,
                Outcome::CouldNotStart,
                format!("its input can't be read: {e}"),
            );
        }
    };
    let path = uri.path_and_query().map(|p| p.as_str()).unwrap_or("/");
    let mut request = hyper::Request::builder()
        .method(http.method.name())
        .uri(path)
        .header(
            hyper::header::HOST,
            uri.authority().map(|a| a.as_str()).unwrap_or(host),
        );
    if http.method == Method::Post {
        request = request.header(hyper::header::CONTENT_LENGTH, body.length);
        if let Some(kind) = &body.content_type {
            request = request.header(hyper::header::CONTENT_TYPE, kind);
        }
    }
    let Ok(request) = request.body(body) else {
        return unreachable(format!("not a URL: {}", http.url));
    };
    let response = match sender.send_request(request).await {
        Ok(response) => response,
        Err(e) => return unreachable(format!("{e} ({place})")),
    };
    let status = i32::from(response.status().as_u16());
    let mut body = response.into_body();
    let mut kept = Vec::new();
    let mut truncated = false;
    while let Some(frame) = body.frame().await {
        let Ok(frame) = frame else {
            break;
        };
        let Ok(data) = frame.into_data() else {
            continue;
        };
        let room = options.max_output.saturating_sub(kept.len());
        if data.len() > room {
            kept.extend_from_slice(&data[..room]);
            truncated = true;
            break;
        }
        kept.extend_from_slice(&data);
    }
    connection.abort();
    Ran {
        kind: Kind::Http,
        outcome: Outcome::Completed,
        message: String::new(),
        code: status,
        output: kept,
        truncated,
        errors: String::new(),
        ended: None,
    }
}

/// A POST's body: its input as it is, or as a form's one file field (multipart/form-data, saying
/// it sends a file of the pack's name); nothing when it has none. Read from its file as it's sent.
fn upload(http: &Http, input: Option<&Path>) -> io::Result<Upload> {
    let Some(input) = input.filter(|_| http.method == Method::Post) else {
        return Ok(Upload {
            head: None,
            file: None,
            tail: None,
            length: 0,
            content_type: None,
        });
    };
    let size = fs::metadata(input)?.len();
    let file = Some(fs::File::open(input)?);
    if http.form.is_empty() {
        return Ok(Upload {
            head: None,
            file,
            tail: None,
            length: size,
            content_type: Some("application/octet-stream".to_string()),
        });
    }
    let mut random = [0u8; 16];
    getrandom::fill(&mut random).expect("the system's random numbers");
    let boundary = format!("spotter-{}", crate::protocol::pack_hash::hex(&random));
    let head = format!(
        "--{boundary}\r\nContent-Disposition: form-data; name=\"{}\"; filename=\"{}\"\r\nContent-Type: application/octet-stream\r\n\r\n",
        http.form,
        http.file()
    );
    let tail = format!("\r\n--{boundary}--\r\n");
    Ok(Upload {
        length: head.len() as u64 + size + tail.len() as u64,
        head: Some(Bytes::from(head)),
        file,
        tail: Some(Bytes::from(tail)),
        content_type: Some(format!("multipart/form-data; boundary={boundary}")),
    })
}

/// A request's body read from a file a chunk at a time, between what goes before it and after.
/// The file is the agent's own, in `/run`, so a read never waits.
struct Upload {
    head: Option<Bytes>,
    file: Option<fs::File>,
    tail: Option<Bytes>,
    length: u64,
    content_type: Option<String>,
}

impl Body for Upload {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        _: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, io::Error>>> {
        if let Some(head) = self.head.take() {
            return Poll::Ready(Some(Ok(Frame::data(head))));
        }
        if let Some(file) = self.file.as_mut() {
            let mut chunk = vec![0u8; 64 * 1024];
            match file.read(&mut chunk) {
                Ok(0) => self.file = None,
                Ok(read) => {
                    chunk.truncate(read);
                    return Poll::Ready(Some(Ok(Frame::data(Bytes::from(chunk)))));
                }
                Err(e) => return Poll::Ready(Some(Err(e))),
            }
        }
        Poll::Ready(self.tail.take().map(|tail| Ok(Frame::data(tail))))
    }

    fn size_hint(&self) -> SizeHint {
        SizeHint::with_exact(self.length)
    }
}
