package dev.jharness.llm;

/** A model call failed: HTTP error, provider error event, cut-off stream or missing key. */
public final class ModelException extends Exception {

    private final boolean retryable;

    public ModelException(String message) {
        this(message, null, false);
    }

    public ModelException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /** @param retryable the provider says the request may succeed if sent again (an overloaded or rate-limit error event) */
    ModelException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}
