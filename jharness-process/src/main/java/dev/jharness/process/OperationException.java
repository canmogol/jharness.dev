package dev.jharness.process;

/** A typed operation was rejected before anything ran: unknown op, bad field or unsafe argument. */
public final class OperationException extends RuntimeException {
    public OperationException(String message) {
        super(message);
    }
}
