package dev.jharness.agent;

import java.util.Set;
import java.util.TreeSet;

import dev.jharness.security.Decision;
import dev.jharness.security.Operation;
import dev.jharness.security.PermissionGate;

/** Refuses the {@code api} methods a custom agent doesn't list in {@code tools} (spec 025), before the usual gate. */
final class ToolsGate implements PermissionGate {

    private final PermissionGate next;
    private final String agent;
    private final Set<String> tools;

    ToolsGate(PermissionGate next, String agent, Set<String> tools) {
        this.next = next;
        this.agent = agent;
        this.tools = tools;
    }

    @Override
    public Decision decide(Operation op) {
        if (!tools.contains(op.api())) {
            return Decision.deny("agent " + agent + " can't use api." + op.api() + " (its tools: "
                    + String.join(", ", new TreeSet<>(tools)) + ")");
        }
        return next.decide(op);
    }
}
