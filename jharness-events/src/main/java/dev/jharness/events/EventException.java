package dev.jharness.events;

/** An event was rejected: too large, not JSON, an unknown or forbidden type, or a missing field. */
public final class EventException extends RuntimeException {
    public EventException(String message) {
        super(message);
    }
}
