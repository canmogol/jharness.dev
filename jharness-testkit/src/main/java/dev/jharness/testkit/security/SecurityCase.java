package dev.jharness.testkit.security;

import java.util.List;

/**
 * One attack (spec 018 AC-01): an ID, its suite, what the attack does, what must contain it and
 * which layer does, and the body that runs it in a {@link World} and returns its failures.
 */
public record SecurityCase(String id, Suite suite, String attack, String containment, String layer, Body body) {

    public enum Suite {
        INJECTION("Prompt injection"),
        REPO("Malicious repository"),
        ESCAPE("Sandbox escape"),
        RUNAWAY("Runaway code");

        public final String title;

        Suite(String title) {
            this.title = title;
        }
    }

    @FunctionalInterface
    public interface Body {
        /** Runs the attack; returns the case's own failures (the shared checks run after it). */
        List<String> run(World w) throws Exception;
    }

    /** Thrown by a body that can't run here (no npm, no OS sandbox). */
    public static final class Skip extends Exception {
        public Skip(String reason) {
            super(reason);
        }
    }

    /** A case's result: {@code pass}, {@code fail} or {@code skip}, with the details. */
    public record Outcome(String id, String status, String detail) {
        public boolean passed() {
            return status.equals("pass");
        }
    }
}
