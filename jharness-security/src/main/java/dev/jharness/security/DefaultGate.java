package dev.jharness.security;

/**
 * The gate until the permissions feature lands: reads, read-only git and events are allowed;
 * state-changing calls are denied with a message saying approval is needed (D-010, AC-06).
 */
public final class DefaultGate implements PermissionGate {

    @Override
    public Decision decide(Operation op) {
        if (op.kind().changesState()) {
            return Decision.deny(op.api() + " needs approval, and approvals are not available yet: " + op.summary());
        }
        return Decision.allow("read-only");
    }
}
