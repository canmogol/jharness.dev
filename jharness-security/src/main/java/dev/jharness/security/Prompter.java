package dev.jharness.security;

/**
 * Asks the user about one operation. The TUI implements it; it may block for as long as the user
 * takes, and the run's wall clock is paused meanwhile (D-003).
 */
@FunctionalInterface
public interface Prompter {

    enum Answer { ONCE, ALWAYS, DENY }

    Answer ask(Operation op);

    /** No one to ask (headless): every question is denied. */
    static Prompter none() {
        return op -> Answer.DENY;
    }
}
