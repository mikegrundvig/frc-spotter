//! A pack's YAML: the spec's examples as they're written, and every mistake at its line.

use std::time::Duration;

use super::*;
use crate::packs::load::INSTALLED;
use crate::testing::example;

fn read_pack(name: &str, yaml: &str) -> Pack {
    read(yaml, &format!("{INSTALLED}/{name}"), false)
        .unwrap_or_else(|problems| panic!("{name} reads: {problems:?}"))
}

fn problems(name: &str, yaml: &str) -> Vec<String> {
    match read(yaml, &format!("{INSTALLED}/{name}"), false) {
        Ok(_) => panic!("{name} has problems:\n{yaml}"),
        Err(problems) => problems,
    }
}

/// Where a problem in pack `p` is: its file, and the line.
fn at(name: &str, line: usize) -> String {
    format!("{INSTALLED}/{name}/pack.yaml:{line}: ")
}

/// One collector `c` in pack `p`, its body as given.
fn collector(body: &str) -> String {
    let mut yaml = "pack: p\ncollectors:\n  - id: c\n".to_string();
    for line in body.lines() {
        yaml.push_str(&format!("    {line}\n"));
    }
    yaml
}

fn run(argv: &[&str]) -> Command {
    Command::Run {
        argv: argv.iter().map(|a| a.to_string()).collect(),
    }
}

fn http(method: Method, url: &str, form: &str, filename: &str) -> Command {
    Command::Http(Http {
        method,
        url: url.into(),
        form: form.into(),
        filename: filename.into(),
    })
}

fn names(fields: &[Field]) -> Vec<&str> {
    fields.iter().map(|f| f.name.as_str()).collect()
}

/// A limit's comparand, as its value.
fn compared(scalar: &Option<Scalar>) -> Option<scalar::Value> {
    scalar.as_ref().and_then(|s| s.value.clone())
}

/// What a pack's problems say: one ending so, one holding this, or exactly these.
enum Says {
    Ends(&'static str),
    Holds(&'static str),
    Only(Vec<String>),
}

fn check(name: &str, yaml: &str, says: &[Says]) {
    let found = problems(name, yaml);
    for each in says {
        let ok = match each {
            Says::Ends(text) => found.iter().any(|p| p.ends_with(text)),
            Says::Holds(text) => found.iter().any(|p| p.contains(text)),
            Says::Only(exactly) => &found == exactly,
        };
        let wanted = match each {
            Says::Ends(text) | Says::Holds(text) => text.to_string(),
            Says::Only(exactly) => format!("{exactly:?}"),
        };
        assert!(ok, "{yaml}\nwanted {wanted}\nfound {found:?}");
    }
}

#[test]
fn debians_pack_reads_as_the_spec_writes_it() {
    let debian = read_pack("debian", &example("debian"));
    assert_eq!(debian.name, "debian");
    assert_eq!(debian.version, "1.0.0");
    assert_eq!(debian.folder, format!("{INSTALLED}/debian"));
    let ids: Vec<&str> = debian.collectors.iter().map(|c| c.id.as_str()).collect();
    assert_eq!(
        ids,
        [
            "cpu", "thermal", "memory", "disk", "network", "usb", "clock", "drive", "uptime"
        ]
    );
    let cpu = &debian.collectors[0];
    assert_eq!(cpu.command, run(&["./cpu"]));
    assert_eq!(cpu.every, Duration::from_secs(1));
    assert_eq!(cpu.timeout, Collector::TIMEOUT);
    assert_eq!(names(&cpu.fields), ["busiest", "capped"]);
    let busiest = &cpu.fields[0];
    assert_eq!(busiest.label, "Busiest core");
    assert_eq!(busiest.kind, FieldType::Number);
    assert_eq!(busiest.unit, "%");
    assert_eq!(
        compared(&cpu.fields[1].warn.equals),
        Some(scalar::Value::Flag(true))
    );
    let margin = &debian.collectors[1].fields[1];
    assert_eq!(margin.warn.below, Some(10.0));
    assert_eq!(margin.fail.below, Some(5.0));
    assert_eq!(margin.fail.above, None);
    assert_eq!(debian.collectors[4].command, run(&["./network"]));
    assert_eq!(
        debian.collectors[7].command,
        Command::Read {
            path: "/run/frc-spotter-debian/drive.json".into()
        }
    );
    assert_eq!(debian.collectors[7].every, Duration::from_secs(60));
    let journal = &debian.logs[0];
    assert_eq!(journal.id, "journal");
    assert_eq!(journal.label, "System log");
    assert_eq!(
        journal.map,
        LogMap {
            time: "__REALTIME_TIMESTAMP".into(),
            time_unit: "us".into(),
            level: "PRIORITY".into(),
            source: "SYSLOG_IDENTIFIER".into(),
            message: "MESSAGE".into(),
            cursor: "__CURSOR".into(),
        }
    );
    assert!(debian.actions.is_empty());
}

#[test]
fn a_web_programs_pack_has_http_collectors_and_actions() {
    let yaml = r#"pack: webapp
collectors:
  - id: service
    run: [systemctl, is-active, webapp.service]
    every: 2s
    fields:
      running: {label: Its service, type: text, fail: {notEquals: active}}
  - id: web
    http: {get: "http://localhost:5800/"}
    every: 5s
    fields:
      status: {label: It answers, type: number, fail: {notEquals: 200, missing: true}}
actions:
  - id: restart
    label: Restart it
    http: {post: "http://localhost:5800/api/restart"}
    confirm: Restart it? It stops for about 20 s.
    response:
      status: {type: number, fail: {notEquals: 200}}
  - id: export
    label: Download settings
    http: {get: "http://localhost:5800/api/settings.zip"}
    timeout: 120s
    response:
      status: {type: number, fail: {notEquals: 200}}
      body: {type: file, name: webapp-settings.zip}
  - id: upload
    label: Send a layout
    http: {post: "http://localhost:5800/api/layout", form: data}
    input: file
    response:
      status: {type: number, fail: {notEquals: 200}}
      body: {type: text}
"#;
    let web = read_pack("webapp", yaml);
    assert_eq!(
        compared(&web.collectors[0].fields[0].fail.not_equals),
        Some(scalar::Value::Text("active".into()))
    );
    assert_eq!(
        web.collectors[1].command,
        http(Method::Get, "http://localhost:5800/", "", "")
    );
    let answers = &web.collectors[1].fields[0];
    assert_eq!(answers.name, "status");
    assert_eq!(answers.kind, FieldType::Number);
    assert!(answers.fail.missing);
    assert_eq!(
        compared(&answers.fail.not_equals),
        Some(scalar::Value::Number(200.0))
    );
    let restart = &web.actions[0];
    assert_eq!(
        restart.command,
        http(Method::Post, "http://localhost:5800/api/restart", "", "")
    );
    assert!(restart.confirm.starts_with("Restart it?"));
    assert_eq!(restart.timeout, Action::TIMEOUT);
    assert_eq!(restart.input, Input::None);
    assert!(!restart.while_enabled);
    // The built-in fields first, the pack's status with its limit.
    assert_eq!(
        names(&restart.response),
        ["outcome", "outcomeMessage", "status", "body"]
    );
    assert_eq!(
        compared(&restart.response[2].fail.not_equals),
        Some(scalar::Value::Number(200.0))
    );
    let export = &web.actions[1];
    assert_eq!(export.timeout, Duration::from_secs(120));
    assert_eq!(export.response[3].kind, FieldType::File);
    assert_eq!(export.response[3].file_name, "webapp-settings.zip");
    let layout = &web.actions[2];
    assert_eq!(
        layout.command,
        http(Method::Post, "http://localhost:5800/api/layout", "data", "")
    );
    assert_eq!(layout.input, Input::File);
}

#[test]
fn a_form_names_its_field_and_may_name_the_file_it_sends() {
    let yaml = r#"pack: p
actions:
  - id: plain
    http: {post: "http://localhost:5800/api/a", form: data}
    input: file
  - id: named
    http: {post: "http://localhost:5800/api/b", form: {field: data, filename: layout.json}}
    input: file
  - id: field-only
    http: {post: "http://localhost:5800/api/c", form: {field: upload}}
    input: file
"#;
    let pack = read_pack("p", yaml);
    let forms: Vec<&Http> = pack
        .actions
        .iter()
        .map(|a| match &a.command {
            Command::Http(http) => http,
            other => panic!("an http action, not {other:?}"),
        })
        .collect();
    assert_eq!(
        Command::Http(forms[0].clone()),
        http(Method::Post, "http://localhost:5800/api/a", "data", "")
    );
    assert_eq!(forms[0].file(), "data");
    assert_eq!(forms[1].form, "data");
    assert_eq!(forms[1].filename, "layout.json");
    assert_eq!(forms[1].file(), "layout.json");
    assert_eq!(forms[2].file(), "upload");

    let bad = r#"pack: p
actions:
  - id: a
    http: {post: "http://localhost/", form: {filename: layout.json}}
    input: file
  - id: b
    http: {post: "http://localhost/", form: {field: data, name: x}}
    input: file
  - id: c
    http: {post: "http://localhost/", form: {field: data, filename: "a\"b.json"}}
    input: file
  - id: d
    http: {post: "http://localhost/", form: "da ta"}
    input: file
"#;
    check(
        "p",
        bad,
        &[
            Says::Ends("a form names its field: {field: data, filename: layout.json}"),
            Says::Ends("unknown key \"name\" in form; known: field, filename"),
            Says::Ends(
                "a form's filename is letters, digits, \".\", \"_\" and \"-\", such as data or layout.json, not \"a\"b.json\"",
            ),
            Says::Ends("not \"da ta\""),
        ],
    );
}

#[test]
fn a_teams_own_pack_has_run_actions_with_json_responses() {
    let detector = read_pack("detector", &example("detector"));
    let kinds: Vec<FieldType> = detector.collectors[0]
        .fields
        .iter()
        .map(|f| f.kind)
        .collect();
    assert_eq!(
        kinds,
        [FieldType::Number, FieldType::Text, FieldType::Status]
    );
    assert_eq!(detector.logs[0].map, LogMap::default());
    let recalibrate = &detector.actions[0];
    assert_eq!(recalibrate.timeout, Duration::from_secs(600));
    assert_eq!(
        names(&recalibrate.response),
        [
            "outcome",
            "outcomeMessage",
            "exit",
            "output",
            "reprojection-error",
            "report"
        ]
    );
    assert_eq!(recalibrate.response[5].kind, FieldType::Json);
    assert_eq!(recalibrate.response[4].warn.above, Some(0.5));
    assert_eq!(detector.actions[1].input, Input::File);
}

#[test]
fn the_raspberry_pis_pack_has_a_status() {
    let pi = read_pack("raspberry-pi", &example("raspberry-pi"));
    assert_eq!(
        pi.collectors[0].command,
        run(&["/usr/lib/frc-spotter-raspberry-pi/undervoltage"])
    );
    assert_eq!(pi.collectors[0].every, Duration::from_secs(5));
}

#[test]
fn a_packs_name_is_its_folders() {
    let rows: &[(&str, &str, Option<String>)] = &[
        (
            "vision",
            "pack: detector\n",
            Some(format!(
                "{}pack detector is in a folder named vision: a pack's folder is its name",
                at("vision", 1)
            )),
        ),
        (
            "core",
            "pack: core\n",
            Some(format!(
                "{}pack core is the agent's own: its built-in actions are core's",
                at("core", 1)
            )),
        ),
        (
            "vision",
            "version: 1\n",
            Some(format!("{}pack: (its name) is missing", at("vision", 1))),
        ),
        // One problem, whatever it says.
        ("Vision", "pack: Vision\n", None),
    ];
    for (folder, yaml, problem) in rows {
        let found = problems(folder, yaml);
        assert_eq!(found.len(), 1, "{yaml}: {found:?}");
        if let Some(problem) = problem {
            assert_eq!(&found[0], problem);
        }
    }
    assert!(read_pack("vision", "pack: vision\n").collectors.is_empty());
}

#[test]
fn an_unknown_key_is_a_problem_at_its_line() {
    let yaml = "pack: vision
collectors:
  - id: web
    run: [curl, localhost]
    evry: 2s
    fields:
      up: {type: boolean}
";
    let found = problems("vision", yaml);
    let unknown = format!("{}unknown key \"evry\" in a collector", at("vision", 5));
    assert!(found.iter().any(|p| p.starts_with(&unknown)), "{found:?}");
    check("vision", yaml, &[Says::Holds("collector web needs every")]);
    check(
        "vision",
        "pack: vision\nprobes: []\n",
        &[Says::Only(vec![format!(
            "{}unknown key \"probes\" in a pack; known: actions, collectors, logs, pack, version",
            at("vision", 2)
        )])],
    );
}

#[test]
fn yaml_it_cant_parse_says_where() {
    // The parser's own words are saphyr's, not snakeyaml-engine's: the line, and that it says why.
    let found = problems("vision", "pack: vision\ncollectors: [\n");
    let line = at("vision", 3);
    assert!(
        found[0].starts_with(&line) && found[0].len() > line.len(),
        "{found:?}"
    );
    check(
        "vision",
        "pack: vision\npack: other\n",
        &[Says::Only(vec![format!(
            "{}found duplicate key pack",
            at("vision", 2)
        )])],
    );
    check(
        "vision",
        "- a\n- b\n",
        &[Says::Only(vec![format!(
            "{INSTALLED}/vision/pack.yaml: expected pack:, and its collectors:"
        )])],
    );
}

/// Each row a collector's body and what its problems say.
fn collectors_say(rows: &[(&str, Says)]) {
    for (body, says) in rows {
        check("p", &collector(body), std::slice::from_ref(says));
    }
}

#[test]
fn a_collector_names_one_command_and_how_often() {
    collectors_say(&[
        (
            "every: 1s\nfields: {v: {type: text}}",
            Says::Ends("a collector names one of run:, http: or file:"),
        ),
        (
            "run: [a]\nfile: /x\nevery: 1s\nfields: {v: {type: text}}",
            Says::Ends(", not run and file"),
        ),
        (
            "run: a\nevery: 1s\nfields: {v: {type: text}}",
            Says::Ends("run is a list: [program, its arguments...]"),
        ),
        (
            "file: proc/uptime\nevery: 1s\nfields: {v: {type: text}}",
            Says::Ends("file is an absolute path, not proc/uptime"),
        ),
        (
            "http: {get: \"http://10.12.34.5:5800/\"}\nevery: 1s\nfields: {v: {type: text}}",
            Says::Holds("http asks the board itself only"),
        ),
        (
            "http: {get: x, post: y}\nevery: 1s\nfields: {v: {type: text}}",
            Says::Ends("http names one of get: or post:, with its URL"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {}",
            Says::Ends("collector c needs at least one field"),
        ),
        (
            "run: [a]\nevery: 1s",
            Says::Ends("collector c needs fields:, the values it fills"),
        ),
    ]);
}

#[test]
fn durations_have_units_and_bounds() {
    let pack = read_pack(
        "p",
        &collector("run: [a]\nevery: 250ms\ntimeout: 1.5s\nfields: {v: {type: text}}"),
    );
    assert_eq!(pack.collectors[0].every, Duration::from_millis(250));
    assert_eq!(pack.collectors[0].timeout, Duration::from_millis(1500));
    let pack = read_pack(
        "p",
        &collector("run: [a]\nevery: 2m\nfields: {v: {type: text}}"),
    );
    assert_eq!(pack.collectors[0].every, Duration::from_secs(120));
    collectors_say(&[
        (
            "run: [a]\nevery: 2\nfields: {v: {type: text}}",
            Says::Ends("a duration is a number and its unit (ms, s, m, h), such as 2s, not 2"),
        ),
        (
            "run: [a]\nevery: 10ms\nfields: {v: {type: text}}",
            Says::Ends("10ms is out of range: 100ms to 24h"),
        ),
    ]);
    assert_eq!(duration::show(Duration::from_secs(3600)), "1h");
    assert_eq!(duration::show(Duration::from_millis(1500)), "1500ms");
}

#[test]
fn values_are_numbers_text_booleans_or_statuses() {
    collectors_say(&[
        (
            "run: [a]\nevery: 1s\nfields: {v: {type: json}}",
            Says::Ends("a value's type is boolean, number, status, text, not json"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {v: {label: V}}",
            Says::Ends("field v needs a type: boolean, number, status, text"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {v: text}",
            Says::Ends("field v is a mapping: {type: ..., label: ...}"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {\"v w\": {type: text}}",
            Says::Holds("isn't a name"),
        ),
    ]);
    // A field's name is its collector's: two collectors may each have a v.
    let two = read_pack(
        "p",
        "pack: p\ncollectors:\n  - {id: a, run: [a], every: 1s, fields: {v: {type: text}}}\n  - {id: b, run: [b], every: 1s, fields: {v: {type: text}}}\n",
    );
    assert_eq!(two.collectors.len(), 2);
    check(
        "p",
        "pack: p\ncollectors:\n  - {id: a, run: [a], every: 1s, fields: {v: {type: text}}}\n  - {id: a, run: [b], every: 1s, fields: {w: {type: text}}}\n",
        &[Says::Ends("collector a is declared twice")],
    );
}

#[test]
fn limits_are_the_fixed_grammar_as_the_fields_type_has_them() {
    let pack = read_pack(
        "p",
        &collector(
            "run: [a]
every: 1s
fields:
  n: {type: number, warn: {above: 3, notEquals: 7}, fail: {below: -1.5e2, missing: true}}
  t: {type: text, fail: {equals: 200, notEquals: \"x\"}}
  b: {type: boolean, warn: {equals: false}}
  s: {type: status}",
        ),
    );
    let fields = &pack.collectors[0].fields;
    assert_eq!(fields[0].warn.above, Some(3.0));
    assert_eq!(
        compared(&fields[0].warn.not_equals),
        Some(scalar::Value::Number(7.0))
    );
    assert_eq!(fields[0].fail.below, Some(-150.0));
    assert!(fields[0].fail.missing);
    // Text compares as text, as written: 200 is "200".
    assert_eq!(
        compared(&fields[1].fail.equals),
        Some(scalar::Value::Text("200".into()))
    );
    assert_eq!(
        compared(&fields[1].fail.not_equals),
        Some(scalar::Value::Text("x".into()))
    );
    assert_eq!(
        compared(&fields[2].warn.equals),
        Some(scalar::Value::Flag(false))
    );
    assert_eq!(fields[3].warn, Limit::default());

    collectors_say(&[
        (
            "run: [a]\nevery: 1s\nfields: {t: {type: text, warn: {above: 3}}}",
            Says::Ends("above is for numbers"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {n: {type: number, warn: {equals: high}}}",
            Says::Ends("equals is a number, not high"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {n: {type: number, warn: {above: \"3\"}}}",
            Says::Ends("above is a number, not 3"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {b: {type: boolean, warn: {equals: yes}}}",
            Says::Ends("equals is true or false"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {n: {type: number, warn: {over: 3}}}",
            Says::Holds("unknown key \"over\" in a limit"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {n: {type: number, warn: 3}}",
            Says::Ends("a limit is a mapping: {below: 5, missing: true}"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {n: {type: number, name: x}}",
            Says::Ends("name is the file a file field downloads as"),
        ),
    ]);
}

#[test]
fn a_collectors_named_parts_are_its_commands_and_typed() {
    let pack = read_pack(
        "p",
        "pack: p
collectors:
  - id: web
    http: {get: \"http://localhost:5800/\"}
    every: 1s
    fields:
      outcome: {type: text}
      outcomeMessage: {type: text}
      status: {type: number, fail: {notEquals: 200}}
      body: {type: text}
  - id: script
    run: [./check]
    every: 1s
    fields:
      exit: {type: number}
      output: {type: text}
      # Not a run command's part: a JSON key.
      body2: {type: text}
  - id: file
    file: /proc/uptime
    every: 1s
    fields:
      # A file read's parts are only its outcome: exit is a JSON key here.
      uptime: {type: number}
",
    );
    assert_eq!(
        names(&pack.collectors[0].fields),
        ["outcome", "outcomeMessage", "status", "body"]
    );
    collectors_say(&[
        (
            "http: {get: \"http://localhost/\"}\nevery: 1s\nfields: {status: {type: text}}",
            Says::Ends("status's type is number, not text"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {output: {type: json}}",
            Says::Ends("output's type is text, not json"),
        ),
        (
            "run: [a]\nevery: 1s\nfields: {outcome: {type: status}}",
            Says::Ends("outcome's type is text, not status"),
        ),
    ]);
    // A run command's exit is a number, but a file read has none: there, exit is any value's name.
    let read = read_pack(
        "p",
        &collector("file: /x\nevery: 1s\nfields: {exit: {type: text}}"),
    );
    assert_eq!(read.collectors.len(), 1);
    // Each collector may declare its parts: their ids are pack.collector.part.
    let two = read_pack(
        "p",
        "pack: p\ncollectors:\n  - {id: a, run: [a], every: 1s, fields: {exit: {type: number}}}\n  - {id: b, run: [b], every: 1s, fields: {exit: {type: number}}}\n",
    );
    assert_eq!(two.collectors.len(), 2);
}

#[test]
fn names_are_unique_where_theyre_declared_and_a_duplicate_ignores_the_pack() {
    // A field twice in one collector, or one response: loading refuses the duplicate key.
    let rows: &[(&str, &str)] = &[
        (
            "pack: p
collectors:
  - id: health
    run: [./health]
    every: 1s
    fields:
      fps: {type: number}
      fps: {type: text}
",
            "8: found duplicate key fps",
        ),
        (
            "pack: p\nactions:\n  - id: go\n    run: [./go]\n    response:\n      answer: {type: number}\n      answer: {type: text}\n",
            "7: found duplicate key answer",
        ),
        // Collectors', logs' and actions' ids, in the pack's lists, checked one by one.
        (
            "pack: p\nactions:\n  - {id: go, run: [a]}\n  - {id: stop, run: [b]}\n  - {id: go, run: [c]}\n",
            "5: action go is declared twice",
        ),
    ];
    for (yaml, problem) in rows {
        let only = format!("{INSTALLED}/p/pack.yaml:{problem}");
        check("p", yaml, &[Says::Only(vec![only])]);
    }
    // The same name in different places is no clash: a collector, a log and an action.
    let pack = read_pack(
        "p",
        "pack: p\ncollectors:\n  - {id: same, run: [a], every: 1s, fields: {same: {type: text}}}\nlogs:\n  - {id: same, run: [b]}\nactions:\n  - {id: same, run: [c]}\n",
    );
    assert_eq!(pack.collectors[0].id, "same");
    assert_eq!(pack.logs[0].id, "same");
    assert_eq!(pack.actions[0].id, "same");
}

#[test]
fn a_log_runs_a_command_and_maps_its_keys() {
    let pack = read_pack(
        "p",
        "pack: p\nlogs:\n  - id: one\n    run: [./log]\n    map: {time: ts, level: severity}\n",
    );
    assert_eq!(
        pack.logs[0].map,
        LogMap {
            time: "ts".into(),
            time_unit: String::new(),
            level: "severity".into(),
            ..LogMap::default()
        }
    );
    check(
        "p",
        "pack: p\nlogs:\n  - {id: one, http: {get: \"http://localhost/\"}}\n",
        &[
            Says::Holds("a log runs a command (run:)"),
            Says::Ends("log one needs run: the command that prints its entries"),
        ],
    );
    check(
        "p",
        "pack: p\nlogs:\n  - {id: one, run: [a], map: {time: {from: t, unit: min}}}\n",
        &[Says::Ends("a time's unit is ns, us, ms or s, not min")],
    );
    check(
        "p",
        "pack: p\nlogs:\n  - {id: one, run: [a]}\n  - {id: one, run: [b]}\n",
        &[Says::Ends("log one is declared twice")],
    );
}

#[test]
fn an_actions_response_starts_with_its_kinds_own_fields() {
    let pack = read_pack(
        "p",
        "pack: p
actions:
  - id: run
    run: [./go]
    input: text
    whileEnabled: true
    timeout: 1h
    response:
      output: {type: json}
      answer: {type: number, fail: {below: 1}}
",
    );
    let action = &pack.actions[0];
    assert_eq!(
        names(&action.response),
        ["outcome", "outcomeMessage", "exit", "output", "answer"]
    );
    assert_eq!(action.response[3].kind, FieldType::Json);
    assert_eq!(action.input, Input::Text);
    assert!(action.while_enabled);
    assert_eq!(action.timeout, Duration::from_secs(3600));

    let bad = "pack: p
actions:
  - id: a
    http: {get: \"http://localhost/\", form: data}
    input: file
    timeout: 2h
    response:
      outcome: {type: text}
      status: {type: text}
      body: {type: status}
      report: {type: file}
  - id: b
    file: /x
  - id: c
    run: [x]
    input: picture
    whileEnabled: maybe
";
    check(
        "p",
        bad,
        &[
            Says::Ends("a GET sends no form"),
            Says::Ends("action a sends a GET, which takes no input"),
            Says::Ends("2h is out of range: 1ms to 1h"),
            Says::Ends("outcome is every response's own, and can't be declared"),
            Says::Ends("status's type is number, not text"),
            Says::Ends("body's type, the whole output's is file, json, text, not status"),
            Says::Ends("a response field's type is boolean, json, number, status, text, not file"),
            Says::Ends("an action names one of run: or http:"),
            Says::Ends("input is none, text or file, not picture"),
            Says::Ends("whileEnabled is true or false"),
        ],
    );
}

#[test]
fn only_the_board_itself_is_asked_over_http() {
    for (url, own) in [
        ("http://localhost:5800/api", true),
        ("http://127.0.0.1/", true),
        ("http://[::1]:8080/", true),
        ("http://LOCALHOST/", true),
        ("https://localhost/", false),
        ("http://user@localhost/", false),
        ("http://localhost.example.com/", false),
        ("not a url", false),
    ] {
        assert_eq!(local(url), own, "{url}");
    }
}

#[test]
fn a_pack_nested_deeper_than_any_needs_is_refused_before_its_built() {
    let deep = format!(
        "{}nested deeper than 32 (a pack needs five or six)",
        at("p", 2)
    );
    let nested = format!("pack: p\nversion: {}{}\n", "[".repeat(33), "]".repeat(33));
    // Deep enough to overflow the agent's stack, were it composed: refused all the same, at once.
    let overflowing = format!("pack: p\nversion: {}", "[".repeat(100_000));
    for yaml in [nested, overflowing] {
        check("p", &yaml, &[Says::Only(vec![deep.clone()])]);
    }
}

#[test]
fn a_packs_lists_fields_and_texts_are_bounded() {
    let mut many = "pack: p\ncollectors:\n".to_string();
    for i in 0..=MAX_COLLECTORS {
        many.push_str(&format!(
            "  - {{id: c{i}, file: /proc/uptime, every: 1s, fields: {{s: {{type: number}}}}}}\n"
        ));
    }
    let mut fields =
        "pack: p\ncollectors:\n  - id: c\n    file: /proc/uptime\n    every: 1s\n    fields:\n"
            .to_string();
    for i in 0..=MAX_FIELDS {
        fields.push_str(&format!("      f{i}: {{type: number}}\n"));
    }
    let label = format!(
        "pack: p\ncollectors:\n  - id: c\n    file: /proc/uptime\n    every: 1s\n    fields:\n      s: {{type: number, label: {}}}\n",
        "x".repeat(MAX_TEXT + 1)
    );
    for (yaml, problem) in [
        (many, "2: a pack has at most 64 collectors, not 65"),
        (fields, "6: collector c has at most 64 fields, not 65"),
        (label, "7: label is 1025 characters, more than 1024"),
    ] {
        let only = format!("{INSTALLED}/p/pack.yaml:{problem}");
        check("p", &yaml, &[Says::Only(vec![only])]);
    }
}

#[test]
fn nothing_but_a_mapping_is_a_pack() {
    assert!(read("", &format!("{INSTALLED}/p"), false).is_err());
    check(
        "p",
        "pack: p\ncollectors: x\n",
        &[Says::Ends("collectors is a list: - id: ...")],
    );
    check(
        "p",
        "pack: p\ncollectors:\n  - x\n",
        &[Says::Ends("each collector is a mapping: - id: ...")],
    );
    assert!(
        read_pack("p", "pack: p\ncollectors:\nlogs: ~\n")
            .collectors
            .is_empty()
    );
}
