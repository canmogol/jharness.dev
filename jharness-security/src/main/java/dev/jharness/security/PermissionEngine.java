package dev.jharness.security;

import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * The permission gate (D-010, D-041). Reads and events are allowed. For a state-changing operation:
 * {@code plan} denies; {@code bypass} allows; {@code accept-edits} allows file writes; a saved project
 * rule allows; otherwise the {@link Prompter} is asked. The file policy is checked before the gate,
 * so no mode can reach a denied or read-only path.
 */
public final class PermissionEngine implements PermissionGate {

    private final PermissionMode mode;
    private final ProjectRules rules;
    private final Prompter prompter;
    private final EventBus bus;
    private final Supplier<Origin> origin;

    public PermissionEngine(PermissionMode mode, ProjectRules rules, Prompter prompter, EventBus bus, Supplier<Origin> origin) {
        this.mode = mode;
        this.rules = rules;
        this.prompter = prompter;
        this.bus = bus;
        this.origin = origin;
    }

    public PermissionMode mode() {
        return mode;
    }

    @Override
    public Decision decide(Operation op) {
        if (!op.kind().changesState()) {
            return Decision.allow("read-only");
        }
        switch (mode) {
            case PLAN:
                return Decision.deny("plan mode is read-only: " + op.summary());
            case BYPASS:
                return Decision.allow("bypass mode");
            case ACCEPT_EDITS:
                if (op.kind() == Operation.Kind.WRITE) {
                    return Decision.allow("accept-edits mode");
                }
                break;
            default:
                break;
        }
        if (rules.allows(op)) {
            return Decision.allow("saved project rule");
        }
        bus.publish(origin.get(), "permission.asked", Json.object().put("api", op.api()).put("kind", op.kind().wire())
                .put("summary", op.summary()));
        Prompter.Answer a = prompter.ask(op);
        return switch (a) {
            case ONCE -> Decision.allow("approved by the user");
            case ALWAYS -> {
                ProjectRules.Rule r = ProjectRules.ruleFor(op);
                if (r != null) {
                    rules.add(r);
                    var e = Json.object().put("kind", r.kind().wire());
                    e.put(r.kind() == Operation.Kind.WRITE ? "path" : "op", r.key());
                    bus.publish(origin.get(), "permission.rule_added", e);
                }
                yield Decision.allow("approved by the user for this project");
            }
            case DENY -> Decision.deny("denied by the user: " + op.summary());
        };
    }
}
