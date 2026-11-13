package com.michaelgrundvig.frc.spotter.manager.actions;

import com.michaelgrundvig.frc.spotter.manager.LogQuery;
import com.michaelgrundvig.frc.spotter.manager.requests.AgentClient;
import com.michaelgrundvig.frc.spotter.manager.requests.Requests;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.michaelgrundvig.frc.spotter.protocol.WireCheck;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A board's runs, as the manager follows them from its stream: robot code's own, started through
 * here, and anyone else's, as the board tells of them. Each run's events go to its {@link
 * Progress}; on every connect the board lists the runs it keeps, so a result that came while it was
 * away reaches its run, and robot code's run the board no longer keeps is lost.
 */
public final class Runs {
  /** The most runs followed at once that robot code didn't start, or that are done. */
  static final int MAX = 64;

  private final Requests requests;
  private final LinkedHashMap<String, Progress> runs = new LinkedHashMap<>();

  public Runs(Requests requests) {
    this.requests = requests;
  }

  /** Starts an action's run, on a request thread; the run follows from the stream. */
  public void start(Progress run, String action, byte[] input) {
    requests.execute(
        run::refused,
        () -> {
          byte[] answer =
              requests
                  .client()
                  .write("POST", Protocol.ACTIONS + action, input, "application/octet-stream");
          String id =
              AgentClient.parse(WireCheck.STARTED, Spotter.Started.newInstance(), answer).getRun();
          boolean cancel = run.started(id);
          attach(id, run);
          if (cancel) {
            CompletableFuture<Void> later = run.cancelling();
            requests
                .cancel(id)
                .whenComplete((done, failure) -> {
                  if (later != null) {
                    if (failure != null) {
                      later.completeExceptionally(failure);
                    } else {
                      later.complete(null);
                    }
                  }
                });
          }
        });
  }

  /** Robot code's run, now that the board has given it an id: it takes what came before. */
  private void attach(String id, Progress run) {
    synchronized (runs) {
      Progress earlier = runs.put(id, run);
      if (earlier != null && earlier != run) {
        run.takeFrom(earlier);
      }
      trim();
    }
  }

  /** A run's event: to its run, or kept for robot code's that hasn't its id yet. */
  public void event(Spotter.RunEvent sent) {
    synchronized (runs) {
      Progress run = runs.get(sent.getRun());
      if (run == null) {
        run = orphan(sent.getAction());
        runs.put(sent.getRun(), run);
        trim();
      }
      run.event(sent);
    }
  }

  /** A run robot code didn't start (or hasn't its id for yet). */
  private Progress orphan(String action) {
    return new Progress(
        action, Spotter.ActionDeclaration.newInstance().setId(action), Map.of(), requests, false);
  }

  /** Drops the oldest runs nobody's following, past the most kept. */
  private void trim() {
    Iterator<Progress> each = runs.values().iterator();
    while (runs.size() > MAX && each.hasNext()) {
      Progress run = each.next();
      if (!run.ours() || run.done()) {
        each.remove();
      }
    }
  }

  /**
   * The runs the agent keeps, as it lists them on every connect: a result that came while the board
   * was away goes to its run, and its log is fetched again; robot code's run that was going and
   * isn't kept any more is lost.
   */
  public void listed(Spotter.Runs listed) {
    List<Progress> fetch = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    synchronized (runs) {
      Set<String> kept = new HashSet<>();
      for (Spotter.RunState state : listed.getRuns()) {
        kept.add(state.getRun());
        Progress run = runs.get(state.getRun());
        if (run == null) {
          continue;
        }
        if (run.ours()) {
          fetch.add(run);
          ids.add(state.getRun());
        }
        if (!state.getRunning() && state.hasResult()) {
          run.finish(state.getResult());
        }
      }
      for (Map.Entry<String, Progress> each : runs.entrySet()) {
        Progress run = each.getValue();
        if (run.ours() && !run.done() && !kept.contains(each.getKey())) {
          run.lost("the board no longer keeps it: it was restarted, or rebooted, while it ran");
        }
      }
    }
    for (int i = 0; i < fetch.size(); i++) {
      Progress run = fetch.get(i);
      String id = ids.get(i);
      requests.execute(
          why -> {},
          () -> {
            Spotter.LogPage page =
                AgentClient.parse(
                    WireCheck.LOG_PAGE,
                    Spotter.LogPage.newInstance(),
                    requests.client().get(Protocol.RUNS + id + "/log?limit=" + LogQuery.MAX_LIMIT));
            List<Spotter.LogEntry> entries = new ArrayList<>();
            for (Spotter.LogEntry entry : page.getEntries()) {
              entries.add(entry);
            }
            run.log(entries);
          });
    }
  }
}
