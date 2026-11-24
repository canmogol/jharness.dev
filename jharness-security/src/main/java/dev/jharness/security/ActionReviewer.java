package dev.jharness.security;

/**
 * The optional action reviewer (spec 038, D-031, constitution Article VII). The permission engine asks it
 * about each state-changing operation that the deterministic checks and the mode did not deny. It may
 * only tighten: {@link Outcome#ASK} turns an allow into a question for the user, {@link Outcome#DENY}
 * refuses. It never allows something the rules would not, and a failing reviewer never allows.
 */
@FunctionalInterface
public interface ActionReviewer {

    enum Outcome { KEEP, ASK, DENY }

    /**
     * @param confidence the reviewer's confidence that the action fits, from 0 to 1, or -1 when it failed
     * @param reason one line for the user and the model
     */
    record Verdict(Outcome outcome, double confidence, String reason) {}

    /** @param agent the agent whose action this is ({@link Caller#current()}) */
    Verdict review(Operation op, String agent);
}
