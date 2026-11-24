package dev.jharness.security;

import java.util.function.Supplier;

/**
 * Marks a thread while it decides for a background subagent (spec 027, D-065): no one watches it, so its questions are
 * answered {@link Prompter.Answer#UNATTENDED} without asking.
 */
public final class Unattended {

    public static final String REASON = "no one can answer while this runs in the background "
            + "(allow it with a saved rule or a permission mode, or run the subagent in the foreground)";

    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

    private Unattended() {
    }

    /** Runs {@code body} on this thread as the unattended agent {@code agent}. */
    public static <T> T run(String agent, Supplier<T> body) {
        String before = AGENT.get();
        AGENT.set(agent);
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
        return op -> active() ? Prompter.Answer.UNATTENDED : p.ask(op);
    }
}
