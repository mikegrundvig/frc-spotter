//! What a pack's collector, log or action runs, fixed in its pack: never anything a request says.
//! Three kinds, the same for all three uses, but a file read, which only a collector may use.

/// The kinds of command: each one's key in a pack, and the names its result's code and whole
/// output go by, which a pack's fields read and an action's response declares.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Run,
    Http,
    Read,
}

/// The named parts every command fills: how it went, and why.
pub const OUTCOME: &str = "outcome";
pub const OUTCOME_MESSAGE: &str = "outcomeMessage";
/// A `run` command's named parts: its exit code, and its standard output.
pub const EXIT: &str = "exit";
pub const OUTPUT: &str = "output";
/// An `http` request's named parts: its status, and its body.
pub const STATUS: &str = "status";
pub const BODY: &str = "body";

impl Kind {
    /// Every kind, in the order a pack's mistakes name them.
    pub const ALL: [Kind; 3] = [Kind::Run, Kind::Http, Kind::Read];

    /// Its key in a pack: `run:`, `http:` or `file:`.
    pub fn key(self) -> &'static str {
        match self {
            Kind::Run => "run",
            Kind::Http => "http",
            Kind::Read => "file",
        }
    }

    /// The name of its result's code: a process's exit code, a response's status; empty for none.
    pub fn code(self) -> &'static str {
        match self {
            Kind::Run => EXIT,
            Kind::Http => STATUS,
            Kind::Read => "",
        }
    }

    /// The name of its whole output: standard output, a response's body; empty for none.
    pub fn whole(self) -> &'static str {
        match self {
            Kind::Run => OUTPUT,
            Kind::Http => BODY,
            Kind::Read => "",
        }
    }

    /// Which named part a field's name is, if any.
    pub fn part(self, name: &str) -> Option<Part> {
        if name == OUTCOME {
            Some(Part::Outcome)
        } else if name == OUTCOME_MESSAGE {
            Some(Part::OutcomeMessage)
        } else if !self.code().is_empty() && name == self.code() {
            Some(Part::Code)
        } else if !self.whole().is_empty() && name == self.whole() {
            Some(Part::Whole)
        } else {
            None
        }
    }
}

/// What a named part of a command's result is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Part {
    Outcome,
    OutcomeMessage,
    Code,
    Whole,
}

/// The method of a request to the board itself.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
}

impl Method {
    pub fn name(self) -> &'static str {
        match self {
            Method::Get => "GET",
            Method::Post => "POST",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum Command {
    /// A fixed command line, run as the agent's user with no shell unless it names one, from its
    /// pack's folder: a program written `./name` is the pack's own, in that folder.
    Run { argv: Vec<String> },
    /// A request to the board itself (`localhost` only).
    Http(Http),
    /// Reading a file, absolute: a collector's only.
    Read { path: String },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Http {
    pub method: Method,
    /// The URL: `http://localhost...`.
    pub url: String,
    /// The form field an action's input is sent as (multipart); empty to send it as the body.
    pub form: String,
    /// The file name the form's field says it sends; empty for the field's own name.
    pub filename: String,
}

impl Http {
    /// The file name the form's field says it sends: its own, unless the pack names one.
    pub fn file(&self) -> &str {
        if self.filename.is_empty() {
            &self.form
        } else {
            &self.filename
        }
    }
}

impl Command {
    pub fn kind(&self) -> Kind {
        match self {
            Command::Run { .. } => Kind::Run,
            Command::Http(_) => Kind::Http,
            Command::Read { .. } => Kind::Read,
        }
    }

    /// The programs it names by path, on the board, which only root may change if an installed
    /// pack is to run it: a run's program when named by a path (its own `./name`, or absolute),
    /// and any argument that's a file of the pack's own (`[python3, ./check.py]`). An absolute path
    /// past the program is left alone: it's usually data, such as a file to hash.
    pub fn programs_by_path(&self, folder: &str) -> Vec<String> {
        let Command::Run { argv } = self else {
            return Vec::new();
        };
        let mut named = Vec::new();
        if by_path(&argv[0]) {
            named.push(program_path(&argv[0], folder));
        }
        for argument in &argv[1..] {
            if let Some(own) = argument.strip_prefix("./") {
                named.push(format!("{folder}/{own}"));
            }
        }
        named
    }
}

/// Whether a pack names its program by a path (its own `./name`, or an absolute one).
pub fn by_path(program: &str) -> bool {
    program.starts_with("./") || program.starts_with('/')
}

/// Where a program is, on the board: in the pack's folder, or as written.
pub fn program_path(program: &str, folder: &str) -> String {
    match program.strip_prefix("./") {
        Some(own) => format!("{folder}/{own}"),
        None => program.to_string(),
    }
}
