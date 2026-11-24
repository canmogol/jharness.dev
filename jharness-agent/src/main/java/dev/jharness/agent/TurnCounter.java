package dev.jharness.agent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import dev.jharness.events.Origin;

/** The current turn number for event envelopes; turn 0 is session setup. */
public final class TurnCounter implements Supplier<Origin> {

    private final String agent;
    private final AtomicInteger turn = new AtomicInteger();
    /** For a subagent: the parent's counter, whose turn its events carry (spec 026). */
    private final TurnCounter follows;

    public TurnCounter(String agent) {
        this(agent, null);
    }

    private TurnCounter(String agent, TurnCounter follows) {
        this.agent = agent;
        this.follows = follows;
    }

    /** A subagent's counter: its own agent name and always the parent's turn, so checkpoints and undo line up. */
    static TurnCounter following(String agent, TurnCounter parent) {
        return new TurnCounter(agent, parent);
    }

    int next() {
        return follows != null ? follows.current() : turn.incrementAndGet();
    }

    /** A resumed session goes on from its last turn (spec 043). */
    void startAt(int last) {
        turn.set(last);
    }

    public int current() {
        return follows != null ? follows.current() : turn.get();
    }

    public String agent() {
        return agent;
    }

    @Override
    public Origin get() {
        return new Origin(agent, current());
    }
}
