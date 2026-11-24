package dev.jharness.security;

import java.util.function.Supplier;

/**
 * Which agent a permission decision is for (spec 038). The gate is shared by every agent, so a subagent's
 * gate marks its decisions here; unmarked decisions are the main agent's.
 */
public final class Caller {

    private static final ThreadLocal<String> AGENT = new ThreadLocal<>();

    private Caller() {
    }

    /** Runs {@code body} on this thread on behalf of {@code agent}. */
    public static <T> T as(String agent, Supplier<T> body) {
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

    public static String current() {
        String a = AGENT.get();
        return a == null ? "main" : a;
    }
}
