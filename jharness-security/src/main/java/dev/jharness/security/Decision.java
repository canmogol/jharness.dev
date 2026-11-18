package dev.jharness.security;

/** The outcome of a permission check; {@code reason} is shown to the model when denied. */
public record Decision(boolean allowed, String reason) {

    public static Decision allow(String reason) {
        return new Decision(true, reason);
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason);
    }
}
