package dev.jharness.web;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The JSON event lines a viewer shows (spec 048, D-083): the lines so far, then each new one, in order. The same
 * lines as {@code jharness -p --output stream-json} (D-082). Readers get the backlog and then live lines through
 * their own listener, which must not block.
 */
public final class EventFeed {

    /** Most lines kept for a viewer that connects later; older ones are dropped. */
    public static final int MAX_BACKLOG = 100_000;

    private final ArrayDeque<String> backlog = new ArrayDeque<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private int dropped;

    /** Adds one JSON line. */
    public synchronized void add(String jsonLine) {
        if (backlog.size() == MAX_BACKLOG) {
            backlog.removeFirst();
            dropped++;
        }
        backlog.addLast(jsonLine);
        listeners.forEach(l -> l.accept(jsonLine));
    }

    /** Sends the backlog to the listener, then every new line, until {@link #detach}. */
    public synchronized void attach(Consumer<String> listener) {
        backlog.forEach(listener);
        listeners.add(listener);
    }

    /** Stops sending lines to the listener. */
    public void detach(Consumer<String> listener) {
        listeners.remove(listener);
    }

    synchronized int size() {
        return backlog.size();
    }

    synchronized int dropped() {
        return dropped;
    }
}
