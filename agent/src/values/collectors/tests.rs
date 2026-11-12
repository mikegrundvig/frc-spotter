//! Collectors on their own schedules: at most four at once, and a slow one delays nobody.

use std::collections::{HashMap, VecDeque};
use std::future::Future;
use std::sync::atomic::{AtomicUsize, Ordering};

use super::*;
use crate::commands::{Commands, Ran, Running};
use crate::packs::command::{Command, Kind};
use crate::packs::load::{self, INSTALLED};
use crate::proto::{FieldType, FieldValue, Outcome, field_value};
use crate::testing::Fixture;

/// A pack of collectors, each running `[<id>]` on its interval, filling one number.
fn pack(name: &str, every: &[(&str, Duration)]) -> Pack {
    Pack {
        name: name.into(),
        version: "1".into(),
        folder: format!("{INSTALLED}/{name}"),
        pushed: false,
        collectors: every
            .iter()
            .map(|(id, interval)| Collector {
                id: id.to_string(),
                command: Command::Run {
                    argv: vec![id.to_string()],
                },
                every: *interval,
                timeout: Collector::TIMEOUT,
                fields: vec![Field::of(id, FieldType::Number)],
            })
            .collect(),
        logs: Vec::new(),
        actions: Vec::new(),
    }
}

fn printed(output: &str) -> Ran {
    Ran {
        output: output.as_bytes().to_vec(),
        ..Ran::failed(Kind::Run, Outcome::Completed, "")
    }
}

/// A runner that runs nothing: each command's program is handed to the test, which says how it
/// went.
struct Fake<F>(F);

impl<F, R> Runner for Fake<F>
where
    F: Fn(String) -> R + Send + Sync,
    R: Future<Output = Ran> + Send + 'static,
{
    fn collect<'a>(
        &'a self,
        _: &'a str,
        command: &'a Command,
        _: Duration,
        _: usize,
    ) -> Running<'a> {
        let Command::Run { argv } = command else {
            panic!("a run command");
        };
        Box::pin((self.0)(argv[0].clone()))
    }
}

fn store(size: usize) -> Arc<Store> {
    Arc::new(Store::new(size, Box::new(|| {})))
}

fn collectors(
    fixture: &Fixture,
    packs: &[Pack],
    runner: Arc<dyn Runner>,
    store: &Arc<Store>,
) -> Arc<Collectors> {
    Arc::new(Collectors::new(
        fixture.host.clone(),
        packs,
        runner,
        store.clone(),
    ))
}

fn held(value: FieldValue) -> field_value::Value {
    value.value.expect("a value")
}

/// Waits for a condition, checking every 20 ms, for at most ten seconds: whether it held.
async fn until(condition: impl Fn() -> bool) -> bool {
    let deadline = Instant::now() + Duration::from_secs(10);
    while !condition() {
        if Instant::now() > deadline {
            return false;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    true
}

fn stop(tasks: Vec<JoinHandle<()>>) {
    for task in tasks {
        task.abort();
    }
}

#[tokio::test]
async fn a_collector_that_leaves_a_child_holding_its_output_isnt_stuck() {
    let fixture = Fixture::new();
    let runs = fixture.path("/runs");
    fixture.pack(
        "lingering",
        &format!(
            "pack: lingering\ncollectors:\n  - id: c\n    run: [sh, -c, \"echo run >> {}; sleep 30 &\"]\n    every: 100ms\n    timeout: 300ms\n    fields:\n      n: {{type: text}}\n",
            runs.display()
        ),
    );
    let values = store(1);
    let commands = Arc::new(Commands::new(fixture.host.clone()));
    let packs = load::load(&fixture.host, "", false).packs;
    let running = collectors(&fixture, &packs, commands.clone(), &values).start();
    // It runs, and runs again: what it left is stopped as it ends, and never holds it up.
    let lines = || {
        std::fs::read_to_string(&runs)
            .map(|text| text.lines().count())
            .unwrap_or(0)
    };
    let ran = until(|| lines() >= 3).await;
    stop(running);
    commands.stop_all();
    assert!(ran, "it ran {} times", lines());
    assert!(matches!(held(values.get(0)), field_value::Value::Text(_)));
}

#[tokio::test]
async fn a_collector_whose_command_wont_go_is_unavailable_and_isnt_run_again_until_it_has() {
    let fixture = Fixture::new();
    // Beside it, due as often, one that counts its turns: the schedule's turns, seen as they come.
    let every = Duration::from_millis(100);
    let pack = pack("p", &[("stuck", every), ("ticks", every)]);
    let values = store(2);
    let ran = Arc::new(AtomicUsize::new(0));
    let ticks = Arc::new(AtomicUsize::new(0));
    let (end, ended) = tokio::sync::watch::channel(false);
    // As Commands answers a command it stopped that won't go: stuck in the kernel, say.
    let runner = {
        let ran = ran.clone();
        let ticks = ticks.clone();
        Fake(move |program: String| {
            let ran = ran.clone();
            let ticks = ticks.clone();
            let mut ended = ended.clone();
            async move {
                if program == "ticks" {
                    ticks.fetch_add(1, Ordering::SeqCst);
                    return printed("1");
                }
                ran.fetch_add(1, Ordering::SeqCst);
                let gone = tokio::spawn(async move {
                    let _ = ended.wait_for(|ended| *ended).await;
                });
                Ran {
                    ended: Some(gone),
                    ..Ran::failed(
                        Kind::Run,
                        Outcome::TimedOut,
                        "timed out after 5s; its command is still running",
                    )
                }
            }
        })
    };
    let running = collectors(&fixture, &[pack], Arc::new(runner), &values).start();
    // Six turns of the other, which starts half a second after it: it was due ten times or so.
    assert!(until(|| ticks.load(Ordering::SeqCst) >= 6).await);
    // Due every 100 ms, it isn't run again while its command is still running.
    assert_eq!(ran.load(Ordering::SeqCst), 1);
    assert_eq!(
        held(values.get(0)),
        field_value::Value::Unavailable("timed out after 5s; its command is still running".into())
    );
    // Once it has ended, its turns come again.
    end.send_replace(true);
    let again = until(|| ran.load(Ordering::SeqCst) > 1).await;
    stop(running);
    assert!(again);
}

#[tokio::test]
async fn values_follow_their_packs_and_collectors_in_order() {
    let fixture = Fixture::new();
    let a = pack("a", &[("one", Duration::from_secs(1))]);
    let b = Pack {
        name: "b".into(),
        folder: "/b".into(),
        collectors: vec![Collector {
            id: "two".into(),
            command: Command::Run {
                argv: vec!["two".into()],
            },
            every: Duration::from_secs(1),
            timeout: Collector::TIMEOUT,
            fields: vec![
                Field::of("x", FieldType::Number),
                Field::of("y", FieldType::Text),
            ],
        }],
        ..pack("b", &[])
    };
    let both = [a, b];
    let firsts: Vec<usize> = slots(&both).iter().map(|slot| slot.first).collect();
    assert_eq!(firsts, [0, 1]);
    assert_eq!(crate::packs::pack::values(&both), 3);
    let values = store(3);
    assert_eq!(
        held(values.get(2)),
        field_value::Value::Unavailable(super::super::store::NOT_YET.into())
    );
    let runner = Fake(|program: String| async move {
        printed(if program == "one" {
            "7"
        } else {
            "{\"x\": 1.5, \"y\": \"up\"}"
        })
    });
    collectors(&fixture, &both, Arc::new(runner), &values)
        .run_all()
        .await;
    assert_eq!(held(values.get(0)), field_value::Value::Number(7.0));
    assert_eq!(values.get(0).index, 0);
    assert_eq!(held(values.get(1)), field_value::Value::Number(1.5));
    assert_eq!(held(values.get(2)), field_value::Value::Text("up".into()));
    assert_eq!(values.get(2).index, 2);
}

#[tokio::test]
async fn at_most_four_run_at_once() {
    let fixture = Fixture::new();
    let ids: Vec<String> = (0..10).map(|i| format!("c{i}")).collect();
    let every: Vec<(&str, Duration)> = ids
        .iter()
        .map(|id| (id.as_str(), Duration::from_millis(100)))
        .collect();
    let now = Arc::new(AtomicUsize::new(0));
    let most = Arc::new(AtomicUsize::new(0));
    let runs = Arc::new(AtomicUsize::new(0));
    let runner = {
        let (now, most, runs) = (now.clone(), most.clone(), runs.clone());
        Fake(move |_: String| {
            let (now, most, runs) = (now.clone(), most.clone(), runs.clone());
            async move {
                let running = now.fetch_add(1, Ordering::SeqCst) + 1;
                most.fetch_max(running, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(150)).await;
                now.fetch_sub(1, Ordering::SeqCst);
                runs.fetch_add(1, Ordering::SeqCst);
                printed("1")
            }
        })
    };
    let running = collectors(&fixture, &[pack("p", &every)], Arc::new(runner), &store(10)).start();
    // Ten due every 100 ms, each taking 150 ms: as many as may run, running.
    let busy =
        until(|| most.load(Ordering::SeqCst) >= RUNNING && runs.load(Ordering::SeqCst) > 10).await;
    stop(running);
    assert!(busy, "{} runs", runs.load(Ordering::SeqCst));
    assert_eq!(most.load(Ordering::SeqCst), RUNNING);
}

#[tokio::test]
async fn a_slow_one_skips_its_turns_and_never_delays_the_others() {
    let fixture = Fixture::new();
    let runs: Arc<Mutex<HashMap<String, usize>>> = Arc::default();
    let runner = {
        let runs = runs.clone();
        Fake(move |program: String| {
            *runs
                .lock()
                .expect("the runs")
                .entry(program.clone())
                .or_default() += 1;
            async move {
                if program == "slow" {
                    tokio::time::sleep(Duration::from_secs(3)).await;
                }
                printed("1")
            }
        })
    };
    let every = Duration::from_millis(100);
    let p = pack("p", &[("slow", every), ("fast", every)]);
    let running = collectors(&fixture, &[p], Arc::new(runner), &store(2)).start();
    let count = |id: &str| runs.lock().expect("the runs").get(id).copied().unwrap_or(0);
    // The fast one, its first turn at 500 ms (the first runs are spread over a second), runs every
    // 100 ms meanwhile; the slow one ran once, skipping its turns.
    let fast = until(|| count("fast") >= 5).await;
    stop(running);
    assert!(fast);
    assert_eq!(count("slow"), 1);
}

#[tokio::test]
async fn a_collector_that_stops_filling_its_fields_is_logged_once_and_again_when_it_recovers() {
    let fixture = Fixture::new();
    let results = Arc::new(Mutex::new(VecDeque::from([
        printed("1"),
        Ran::failed(Kind::Run, Outcome::TimedOut, "timed out after 5s"),
        Ran::failed(Kind::Run, Outcome::TimedOut, "timed out after 5s"),
        printed("2"),
    ])));
    let runner = {
        let results = results.clone();
        Fake(move |_: String| {
            let next = results
                .lock()
                .expect("the results")
                .pop_front()
                .expect("a result");
            async move { next }
        })
    };
    let values = store(1);
    let p = pack("p", &[("c", Duration::from_secs(1))]);
    let collectors = collectors(&fixture, &[p], Arc::new(runner), &values);
    collectors.run(0).await;
    collectors.run(0).await;
    assert_eq!(
        held(values.get(0)),
        field_value::Value::Unavailable("timed out after 5s".into())
    );
    collectors.run(0).await;
    collectors.run(0).await;
    assert_eq!(
        *fixture.log.lock().expect("the log"),
        [
            "Collector p.c fills nothing from its output: timed out after 5s",
            "Collector p.c fills its fields again",
        ]
    );
}
