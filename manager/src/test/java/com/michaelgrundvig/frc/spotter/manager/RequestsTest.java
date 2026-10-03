package com.michaelgrundvig.frc.spotter.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.michaelgrundvig.frc.spotter.protocol.Protocol;
import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A board's requests are bounded: a few at once and a few waiting, one more refused at once; each
 * within a deadline, however slowly an agent answers; none while the board isn't connected; and
 * every one finished, one way or another, when the manager closes.
 */
class RequestsTest {
  /** A port that takes connections and never answers: an agent that hangs. */
  private static ServerSocket silent() throws IOException {
    return new ServerSocket(0, 64, java.net.InetAddress.getLoopbackAddress());
  }

  @Test
  void aBoardsRequestsAreBoundedAndOneTooManyIsRefusedAtOnce() throws Exception {
    try (ServerSocket hung = silent()) {
      String address = "127.0.0.1:" + hung.getLocalPort();
      Manager manager = new Manager(Boards.robot(), List.of(address));
      List<CompletableFuture<Spotter.LogPage>> asked = new ArrayList<>();
      try {
        Link link = manager.link(0);
        for (int i = 0; i < Link.REQUEST_THREADS + Link.REQUESTS_WAITING; i++) {
          asked.add(link.log("vision.log", LogQuery.latest(10)));
        }
        CompletableFuture<Spotter.LogPage> tooMany = link.log("vision.log", LogQuery.latest(10));
        assertThat(tooMany).isCompletedExceptionally();
        assertThatThrownBy(tooMany::get)
            .hasMessageContaining(
                "too many requests to "
                    + address
                    + " at once: "
                    + Link.REQUEST_THREADS
                    + " under way and "
                    + Link.REQUESTS_WAITING
                    + " waiting");
        assertThat(asked).noneMatch(CompletableFuture::isDone);
      } finally {
        manager.close();
      }
      // Closing it finishes every one: those under way dropped, those waiting refused.
      for (CompletableFuture<Spotter.LogPage> each : asked) {
        assertThatThrownBy(() -> each.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class);
      }
    }
  }

  @Test
  void aRequestPastItsDeadlineIsDroppedHoweverSlowlyItsAnswered() throws Exception {
    ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    try (ServerSocket dripping = silent()) {
      Thread server =
          new Thread(
              () -> {
                try (Socket socket = dripping.accept()) {
                  OutputStream out = socket.getOutputStream();
                  out.write(
                      ("HTTP/1.1 200 OK\r\n"
                              + Protocol.HEADER
                              + ": "
                              + Protocol.VERSION
                              + "\r\nContent-Length: 1000\r\n\r\n")
                          .getBytes(StandardCharsets.US_ASCII));
                  // A byte at a time, each well within the read timeout.
                  for (int i = 0; i < 1000; i++) {
                    out.write(0);
                    out.flush();
                    Thread.sleep(100);
                  }
                } catch (IOException | InterruptedException e) {
                  // Dropped by the client, as it should be.
                }
              });
      server.setDaemon(true);
      server.start();
      AgentClient client =
          new AgentClient(
              Link.address("127.0.0.1:" + dripping.getLocalPort()),
              1000,
              null,
              new AgentClient.Challenges() {
                @Override
                public String challenge() {
                  return "";
                }

                @Override
                public long next() {
                  return 1;
                }
              },
              deadlines,
              500);
      long started = System.nanoTime();
      assertThatThrownBy(() -> client.get(Protocol.DESCRIBE))
          .isInstanceOf(AgentClient.Refused.class)
          .hasMessage("no complete answer within 500 ms");
      assertThat((System.nanoTime() - started) / 1e9).isLessThan(5);
    } finally {
      deadlines.shutdownNow();
    }
  }

  @Test
  void aBoardThatIsntConnectedIsAskedForNoLog() throws Exception {
    try (ServerSocket hung = silent()) {
      AtomicLong clock = new AtomicLong(1_000_000_000_000L);
      Manager manager =
          new Manager(
              Boards.robot(clock),
              List.of("127.0.0.1:" + hung.getLocalPort()),
              Settings.DEFAULTS,
              Recorder.NONE);
      try {
        Board board = manager.boards().get(0);
        clock.addAndGet(5_000_000_000L);
        manager.update();
        assertThat(board.connection()).isEqualTo(Connection.MISSING);
        CompletableFuture<Spotter.LogPage> page = board.log("vision.log", LogQuery.latest(10));
        assertThat(page).isCompletedExceptionally();
        assertThatThrownBy(page::get)
            .hasMessageContaining(board.name() + "'s logs can't be read: it isn't connected");
      } finally {
        manager.close();
      }
    }
  }
}
