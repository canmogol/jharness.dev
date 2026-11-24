package dev.jharness.security;

import java.util.function.Supplier;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * The permission gate (D-010, D-041). Reads and events are allowed. For a state-changing operation:
 * {@code plan} denies; {@code bypass} allows; {@code accept-edits} allows file writes; a saved project
 * rule allows; otherwise the {@link Prompter} is asked. The file policy is checked before the gate,
 * so no mode can reach a denied or read-only path. An optional {@link ActionReviewer} (spec 038, D-031)
 * is asked after the plan check and before the mode and rules: it can deny, or make an allowed operation
 * ask, but never allows.
 */
public final class PermissionEngine implements PermissionGate {

    private volatile PermissionMode mode;
    private final ProjectRules rules;
    private final Prompter prompter;
    private final EventBus bus;
    private final Supplier<Origin> origin;
    private volatile ActionReviewer reviewer;

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

    /** {@code /plan} and an approved plan (spec 036) switch the mode during a session. */
    public void mode(PermissionMode m) {
        mode = m;
    }

    /** Sets the action reviewer (spec 038); null turns it off. */
    public void useReviewer(ActionReviewer r) {
        reviewer = r;
    }

    public ActionReviewer reviewer() {
        return reviewer;
    }

    @Override
    public Decision decide(Operation op) {
        if (!op.kind().changesState()) {
            return Decision.allow("read-only");
        }
        if (mode == PermissionMode.PLAN) {
            return Decision.deny("plan mode is read-only: " + op.summary());
        }
        ActionReviewer r = reviewer;
        if (r != null) {
            ActionReviewer.Verdict v = r.review(op, Caller.current());
            if (v.outcome() == ActionReviewer.Outcome.DENY) {
                return Decision.deny(v.reason() + ": " + op.summary());
            }
            if (v.outcome() == ActionReviewer.Outcome.ASK) {
                var d = op.detail() == null ? Json.object() : op.detail().deepCopy();
                d.putObject("review").put("reason", v.reason()).put("confidence", v.confidence());
                return ask(new Operation(op.api(), op.kind(), op.summary(), d));
            }
        }
        switch (mode) {
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
        return ask(op);
    }

    private Decision ask(Operation op) {
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
            case UNATTENDED -> Decision.deny(Unattended.REASON + ": " + op.summary());
        };
    }
}
