package com.michaelgrundvig.frc.spotter.agent.server;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What happens on the board as it happens, for each stream: a value changed, or a run started,
 * logged a line, or finished. A subscriber is told at once and does its own sending, so a slow
 * stream never holds up a collector or a run.
 */
public final class Events {
  /** A stream, told of what happens. */
  interface Subscriber {
    /** Some value changed: the stream asks the store what, when it next sends. */
    void valuesChanged();

    /** A run started, logged a line, or finished. */
    void run(Spotter.RunEvent event);
  }

  private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

  public void subscribe(Subscriber subscriber) {
    subscribers.add(subscriber);
  }

  public void unsubscribe(Subscriber subscriber) {
    subscribers.remove(subscriber);
  }

  public void valuesChanged() {
    for (Subscriber subscriber : subscribers) {
      subscriber.valuesChanged();
    }
  }

  public void run(Spotter.RunEvent event) {
    for (Subscriber subscriber : subscribers) {
      subscriber.run(event.clone());
    }
  }
}
