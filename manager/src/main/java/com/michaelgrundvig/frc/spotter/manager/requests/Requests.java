package com.michaelgrundvig.frc.spotter.manager.requests;

import com.michaelgrundvig.frc.spotter.manager.Board;
import com.michaelgrundvig.frc.spotter.manager.LogQuery;
import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import com.michaelgrundvig.frc.spotter.protocol.WireCheck;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * One board's requests besides its stream (a log page, an action started, a run cancelled, a file
 * fetched, packs pushed), on request threads of its own, never the robot loop's: at most {@link
 * #THREADS} at once and {@link #WAITING} waiting, any more refused at once, so robot code asking
 * every loop can't pile threads up on the robot. Closing them finishes every one there and then.
 */
public final class Requests {
  /** How many of a board's requests run at once. */
  public static final int THREADS = 3;

  /** How many of a board's requests may wait for a thread; one more is refused at once. */
  public static final int WAITING = 8;

  /** How long a request thread waits for another request before it ends. */
  private static final long IDLE_SECONDS = 30;

  /** A request that answers something. */
  interface Call<T> {
    T call() throws IOException;
  }

  /** A request that answers nothing. */
  public interface Task {
    void run() throws IOException;
  }

  private final Board board;
  private final AgentClient client;
  private final Executor executor;
  private final @Nullable ExecutorService pool;
  private final Set<Request> outstanding = new HashSet<>();
  private volatile boolean closed;

  /**
   * @param board the board they're to, for what a refusal says
   * @param executor where they run: a {@link #pool}, or the caller's thread (a test's)
   * @param pool the pool, when they run on one, to shut down as they close
   */
  public Requests(
      Board board, AgentClient client, Executor executor, @Nullable ExecutorService pool) {
    this.board = board;
    this.client = client;
    this.executor = executor;
    this.pool = pool;
  }

  /**
   * A board's request threads: {@link #THREADS} at most, made as they're needed and ended when
   * idle; {@link #WAITING} requests may wait, and one more is refused at once.
   */
  public static ThreadPoolExecutor pool(String address) {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            THREADS,
            THREADS,
            IDLE_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WAITING),
            work -> {
              Thread thread = new Thread(work, "Spotter requests " + address);
              thread.setDaemon(true);
              return thread;
            });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  /** What sends them. */
  public AgentClient client() {
    return client;
  }

  /** Cancels a run. */
  public CompletableFuture<Void> cancel(String run) {
    CompletableFuture<Void> taken = new CompletableFuture<>();
    execute(
        why -> taken.completeExceptionally(new IOException(why)),
        () -> {
          client.write("DELETE", Protocol.RUNS + run, null, "");
          taken.complete(null);
        });
    return taken;
  }

  /** A run's file field, as its bytes. */
  public CompletableFuture<byte[]> file(String run, String field) {
    return call(() -> client.get(Protocol.RUNS + run + "/files/" + field));
  }

  /** A page of one of the board's logs. */
  public CompletableFuture<Spotter.LogPage> log(String id, LogQuery query) {
    return call(
        () ->
            AgentClient.parse(
                WireCheck.LOG_PAGE,
                Spotter.LogPage.newInstance(),
                client.get(Protocol.LOGS + id + query(query))));
  }

  /** A log page's query, as the agent takes it: {@code ?from=latest&limit=100}. */
  public static String query(LogQuery query) {
    StringBuilder text =
        new StringBuilder("?from=").append(query.from()).append("&limit=").append(query.limit());
    if (!query.cursor().isEmpty()) {
      text.append("&cursor=").append(URLEncoder.encode(query.cursor(), StandardCharsets.UTF_8));
    }
    if (!query.level().isEmpty()) {
      text.append("&level=").append(URLEncoder.encode(query.level(), StandardCharsets.UTF_8));
    }
    return text.toString();
  }

  /** A request that answers: completes with its answer, or exceptionally, saying why not. */
  <T> CompletableFuture<T> call(Call<T> call) {
    CompletableFuture<T> answer = new CompletableFuture<>();
    execute(
        why -> answer.completeExceptionally(new IOException(why)),
        () -> answer.complete(call.call()));
    return answer;
  }

  /**
   * Runs a request on one of the board's request threads: what it throws goes to {@code failed}, as
   * why; when they're all busy and as many wait as may, it's refused at once.
   */
  public void execute(Consumer<String> failed, Task task) {
    Request request = new Request(failed, task);
    boolean refused;
    synchronized (outstanding) {
      refused = closed;
      if (!refused) {
        outstanding.add(request);
      }
    }
    if (refused) {
      failed.accept("the manager is closed");
      return;
    }
    try {
      executor.execute(request);
    } catch (RejectedExecutionException e) {
      synchronized (outstanding) {
        outstanding.remove(request);
      }
      failed.accept(busy());
    }
  }

  /**
   * Closes them: every request asked for is refused now, waiting or under way, whatever its thread
   * is doing (one connecting as the manager closes would otherwise wait out its read timeout
   * first), and the connections under way are dropped.
   */
  public void close() {
    closed = true;
    if (pool != null) {
      pool.shutdownNow();
    }
    List<Request> refused;
    synchronized (outstanding) {
      refused = new ArrayList<>(outstanding);
      outstanding.clear();
    }
    for (Request each : refused) {
      each.refuse("the manager is closed");
    }
    client.close();
  }

  /**
   * A request, waiting for a thread or under way: what it throws goes to its failure, as why. Each
   * is kept in {@link #outstanding} from when it's asked until it's done, so closing refuses every
   * one at once, rather than waiting on its thread: one waiting never runs, and what one under way
   * does after that changes nothing robot code sees, as every failure and answer after the first is
   * ignored.
   */
  private final class Request implements Runnable {
    private final Consumer<String> failed;
    private final Task task;

    Request(Consumer<String> failed, Task task) {
      this.failed = failed;
      this.task = task;
    }

    @Override
    public void run() {
      synchronized (outstanding) {
        if (!outstanding.contains(this)) {
          // Refused already: they closed.
          return;
        }
      }
      try {
        task.run();
      } catch (IOException | RuntimeException e) {
        failed.accept(AgentClient.why(e));
      } finally {
        synchronized (outstanding) {
          outstanding.remove(this);
        }
      }
    }

    /** It never ran, and why. */
    void refuse(String why) {
      failed.accept(why);
    }
  }

  /** Why a request was refused before it was sent: they're closed, or too many are at once. */
  private String busy() {
    return closed || (pool != null && pool.isShutdown())
        ? "the manager is closed"
        : "too many requests to "
            + board.name()
            + " at once: "
            + THREADS
            + " under way and "
            + WAITING
            + " waiting";
  }
}
