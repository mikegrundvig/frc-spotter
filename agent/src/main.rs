//! The `frc-spotter` program: the agent, or with a command first (`check`, `status`, `help`),
//! Spotter's command line, whose exit code is the program's.

use std::io::Write;
use std::process::ExitCode;

use frc_spotter::{serve, tools};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if tools::names(&args) {
        let code = tools::run(
            &args,
            &mut std::io::stdout().lock(),
            &mut std::io::stderr().lock(),
        );
        return ExitCode::from(code);
    }
    let options = match serve::options(&args) {
        Ok(options) => options,
        Err(usage) => {
            let _ = writeln!(std::io::stderr(), "frc-spotter: {usage}");
            return ExitCode::from(tools::USAGE);
        }
    };
    // Its log is its standard error, the journal on a board, one line a message as it's written.
    env_logger::Builder::new()
        .filter_level(log::LevelFilter::Info)
        .format(|out, record| writeln!(out, "{}", record.args()))
        .target(env_logger::Target::Stderr)
        .init();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .max_blocking_threads(serve::FILE_THREADS)
        .build();
    match runtime {
        Ok(runtime) => ExitCode::from(runtime.block_on(serve::serve(options))),
        Err(e) => {
            let _ = writeln!(std::io::stderr(), "frc-spotter: {e}");
            ExitCode::FAILURE
        }
    }
}
