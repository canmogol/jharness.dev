package dev.jharness.agent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import dev.jharness.events.Origin;

/** The current turn number for event envelopes; turn 0 is session setup. */
public final class TurnCounter implements Supplier<Origin> {

    private final String agent;
    private final AtomicInteger turn = new AtomicInteger();

    public TurnCounter(String agent) {
        this.agent = agent;
    }

    int next() {
        return turn.incrementAndGet();
    }

    public int current() {
        return turn.get();
    }

    @Override
    public Origin get() {
        return new Origin(agent, turn.get());
    }
}
