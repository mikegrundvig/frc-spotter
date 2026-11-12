//! The agent's answers: every one a spotter.proto message, in protobuf, or in its JSON form when
//! the request asks for it; every one with the protocol's version, refusals and failures included.

use std::convert::Infallible;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
use hyper::body::{Body as HttpBody, Frame, SizeHint};
use hyper::{Response, StatusCode};
use tokio::sync::mpsc;

use crate::proto::{Problem, json};
use crate::protocol;

/// A response's body: its bytes whole, or a stream's events as they come.
pub enum Body {
    Whole(Option<Bytes>),
    Stream(mpsc::Receiver<Bytes>),
}

impl HttpBody for Body {
    type Data = Bytes;
    type Error = Infallible;

    fn poll_frame(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, Infallible>>> {
        match self.get_mut() {
            Body::Whole(bytes) => Poll::Ready(bytes.take().map(|b| Ok(Frame::data(b)))),
            Body::Stream(events) => events
                .poll_recv(cx)
                .map(|event| event.map(|b| Ok(Frame::data(b)))),
        }
    }

    fn is_end_stream(&self) -> bool {
        matches!(self, Body::Whole(None))
    }

    fn size_hint(&self) -> SizeHint {
        match self {
            Body::Whole(Some(bytes)) => SizeHint::with_exact(bytes.len() as u64),
            Body::Whole(None) => SizeHint::with_exact(0),
            Body::Stream(_) => SizeHint::default(),
        }
    }
}

/// A request that can't be answered as asked: its status, why, and any header it takes.
#[derive(Debug)]
pub struct Refused {
    pub status: u16,
    pub reason: String,
    /// `Allow`, for the wrong method.
    pub allow: Option<&'static str>,
    /// Whether the agent itself failed: its journal is told why, the answer only that it failed.
    pub internal: bool,
}

impl Refused {
    pub fn new(status: u16, reason: impl Into<String>) -> Refused {
        Refused {
            status,
            reason: reason.into(),
            allow: None,
            internal: false,
        }
    }
}

impl From<io::Error> for Refused {
    fn from(e: io::Error) -> Refused {
        Refused {
            internal: true,
            ..Refused::new(500, e.to_string())
        }
    }
}

/// A response with the headers every answer carries.
pub fn response(status: u16, body: Body) -> Response<Body> {
    let mut response = Response::new(body);
    *response.status_mut() =
        StatusCode::from_u16(status).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    let headers = response.headers_mut();
    headers.insert(
        protocol::HEADER,
        hyper::header::HeaderValue::from_static(protocol::VERSION),
    );
    headers.insert(
        hyper::header::CACHE_CONTROL,
        hyper::header::HeaderValue::from_static("no-store"),
    );
    headers.insert(
        "x-content-type-options",
        hyper::header::HeaderValue::from_static("nosniff"),
    );
    response
}

/// A message, in protobuf or in JSON.
pub fn message<M: prost::Message + serde::Serialize>(
    status: u16,
    message: &M,
    json: bool,
) -> Response<Body> {
    let (bytes, kind) = if json {
        (json::pretty(message), "application/json; charset=utf-8")
    } else {
        (message.encode_to_vec(), protocol::PROTOBUF)
    };
    let mut answer = response(
        status,
        Body::Whole((!bytes.is_empty()).then(|| Bytes::from(bytes))),
    );
    answer.headers_mut().insert(
        hyper::header::CONTENT_TYPE,
        hyper::header::HeaderValue::from_static(kind),
    );
    answer
}

/// A refusal: its status, and a `Problem` saying why.
pub fn refusal(refused: &Refused, json: bool) -> Response<Body> {
    let mut answer = message(
        refused.status,
        &Problem {
            message: refused.reason.clone(),
        },
        json,
    );
    if refused.status == 503 {
        answer.headers_mut().insert(
            hyper::header::RETRY_AFTER,
            hyper::header::HeaderValue::from_static("1"),
        );
    }
    if let Some(allow) = refused.allow {
        answer.headers_mut().insert(
            hyper::header::ALLOW,
            hyper::header::HeaderValue::from_static(allow),
        );
    }
    answer
}

/// An answer with no body.
pub fn empty(status: u16) -> Response<Body> {
    response(status, Body::Whole(None))
}

/// Whether a request asks for JSON rather than protobuf.
pub fn wants_json(accept: Option<&str>) -> bool {
    let Some(accept) = accept else {
        return false;
    };
    let asked = accept.to_lowercase();
    asked.contains(protocol::JSON) && !asked.contains(protocol::PROTOBUF)
}
