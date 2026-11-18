package dev.jharness.llm;

/** A model call failed: HTTP error, provider error event, cut-off stream or missing key. */
public final class ModelException extends Exception {
    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
