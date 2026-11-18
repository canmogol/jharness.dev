package dev.jharness.security;

/**
 * Every {@code api} call passes this gate before it runs (constitution Article II). Implementations
 * may block while asking the user; the watchdog does not count that time (D-003).
 */
@FunctionalInterface
public interface PermissionGate {

    Decision decide(Operation op);

    /** Allows everything; for tests only. */
    static PermissionGate allowAll() {
        return op -> Decision.allow("test gate");
    }
}
