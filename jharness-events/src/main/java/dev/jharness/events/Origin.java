package dev.jharness.events;

/** Who an event is about: the agent name and the turn number. */
public record Origin(String agent, int turn) {
    public static Origin main(int turn) {
        return new Origin("main", turn);
    }
}
