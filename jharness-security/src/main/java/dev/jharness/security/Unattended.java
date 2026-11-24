package dev.jharness.security;

import java.util.function.Supplier;

/**
 * Marks a thread while it decides for a background subagent (spec 027, D-065): no one watches it, so its questions are
 * answered {@link Prompter.Answer#UNATTENDED} without asking.
 */
public final class Unattended {

    public static final String REASON = "no one answered while this ran in the background "
            + "(the user can /switch to the subagent to answer; or allow it with a saved rule or a permission mode, or run the subagent in the foreground)";

    /**
     * Holds a background subagent's question until the user switches to it (spec 033, D-072), then asks
     * with the real prompter; or gives up, answering {@code UNATTENDED}.
     */
    @FunctionalInterface
    public interface Waiter {
        Prompter.Answer await(String agent, Operation op, Prompter real);
    }

    private record Mark(String agent, Waiter waiter) {}

    private static final ThreadLocal<Mark> AGENT = new ThreadLocal<>();

    private Unattended() {
    }

    /** Runs {@code body} on this thread as the unattended agent {@code agent}. */
    public static <T> T run(String agent, Supplier<T> body) {
        return run(agent, null, body);
    }

    /** As {@link #run(String, Supplier)}, with a waiter that may hold the question instead of refusing it at once. */
    public static <T> T run(String agent, Waiter waiter, Supplier<T> body) {
        Mark before = AGENT.get();
        AGENT.set(new Mark(agent, waiter));
        try {
            return body.get();
        } finally {
            if (before == null) {
                AGENT.remove();
            } else {
                AGENT.set(before);
            }
        }
    }

    /** Whether this thread runs an unattended agent. */
    public static boolean active() {
        return AGENT.get() != null;
    }

    /** {@code p}, except that unattended threads get {@code UNATTENDED} without asking. */
    public static Prompter guard(Prompter p) {
        return op -> {
            Mark m = AGENT.get();
            if (m == null) {
                return p.ask(op);
            }
            return m.waiter() == null ? Prompter.Answer.UNATTENDED : m.waiter().await(m.agent(), op, p);
        };
    }
}
