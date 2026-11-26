package dev.jharness.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;
import dev.jharness.sandbox.Planning;
import dev.jharness.security.Operation;
import dev.jharness.security.PermissionEngine;
import dev.jharness.security.PermissionMode;
import dev.jharness.security.Prompter;

/**
 * Todo lists and plan mode (spec 036, D-075). Each agent has its own todo list. Plan mode is the
 * session's permission mode {@code plan}: {@code /plan} turns it on and off, and the main agent's
 * {@code api.proposePlan} asks the user to leave it and carry out the plan.
 */
final class PlanState implements Planning {

    static final int MAX_ITEMS = 50;
    static final int MAX_TEXT = 200;
    static final int MAX_PLAN = 20_000;

    /** Put before each message while plan mode is on, so the model knows. */
    static final String NOTE = "[Plan mode is on: you can read and investigate, but every change is refused. When you have a plan, "
            + "call api.proposePlan(plan) with it as Markdown; the user decides whether to carry it out.]";

    private final EventBus bus;
    private final PermissionEngine engine;
    private final Prompter prompter;
    private final boolean locked;
    private final Map<String, ArrayNode> lists = new ConcurrentHashMap<>();
    private volatile PermissionMode before = PermissionMode.ASK;

    /**
     * @param engine the session's permission engine, or null when the session has a fixed gate (tests)
     * @param locked the custom agent runs in plan mode ({@code plan: true}), so plan mode can't be left
     */
    PlanState(EventBus bus, PermissionEngine engine, Prompter prompter, boolean locked) {
        this.bus = bus;
        this.engine = engine;
        this.prompter = prompter;
        this.locked = locked;
    }

    boolean planMode() {
        return engine != null && engine.mode() == PermissionMode.PLAN;
    }

    /**
     * {@code /plan}: turns plan mode on, or off back to the mode before it.
     *
     * @return the new state
     */
    boolean toggle() {
        if (engine == null) {
            throw new IllegalStateException("plan mode needs the permission engine");
        }
        if (locked) {
            throw new IllegalStateException("this agent always runs in plan mode");
        }
        if (planMode()) {
            set(before, "user");
        } else {
            before = engine.mode();
            set(PermissionMode.PLAN, "user");
        }
        return planMode();
    }

    private void set(PermissionMode m, String by) {
        engine.mode(m);
        bus.publish(Origin.main(0), "plan.mode", Json.object().put("plan", m == PermissionMode.PLAN).put("mode", m.name().toLowerCase().replace('_', '-'))
                .put("by", by));
    }

    String note() {
        return planMode() ? NOTE : "";
    }

    ArrayNode list(String agent) {
        return lists.getOrDefault(agent, Json.array());
    }

    @Override
    public String todos(String agent, String json) {
        if (json == null || json.isBlank() || json.equals("undefined")) {
            return Json.write(list(agent));
        }
        JsonNode in;
        try {
            in = Json.parse(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("todos takes a list such as [{text: \"read the code\", status: \"done\"}]");
        }
        if (!in.isArray()) {
            throw new IllegalArgumentException("todos takes a list such as [{text: \"read the code\", status: \"done\"}]");
        }
        if (in.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("at most " + MAX_ITEMS + " todo items");
        }
        ArrayNode out = Json.array();
        for (JsonNode i : in) {
            String text = i.isTextual() ? i.asText() : i.path("text").asText("");
            String status = i.isTextual() ? "pending" : i.path("status").asText("pending");
            if (text.isBlank() || text.length() > MAX_TEXT) {
                throw new IllegalArgumentException("each todo needs a text of 1 to " + MAX_TEXT + " characters");
            }
            if (!status.equals("pending") && !status.equals("in_progress") && !status.equals("done")) {
                throw new IllegalArgumentException("a todo's status is pending, in_progress or done, not " + status);
            }
            out.addObject().put("text", text.strip()).put("status", status);
        }
        lists.put(agent, out);
        var e = Json.object();
        e.set("items", out.deepCopy());
        bus.publish(new Origin(agent, 0), "todo.updated", e);
        return Json.write(out);
    }

    /** {@code /clear} (spec 064): the agent's todo list belonged to the cleared conversation. */
    void clearTodos(String agent) {
        if (lists.remove(agent) != null) {
            var e = Json.object();
            e.set("items", Json.array());
            bus.publish(new Origin(agent, 0), "todo.updated", e);
        }
    }

    @Override
    public String proposePlan(String agent, String plan) {
        if (!agent.equals("main")) {
            throw new IllegalArgumentException("only the main agent can propose a plan");
        }
        if (locked) {
            throw new IllegalArgumentException("this agent always runs in plan mode; report the plan in your reply instead");
        }
        if (!planMode()) {
            throw new IllegalArgumentException("plan mode is off; carry out the work instead of proposing it");
        }
        String text = plan.length() > MAX_PLAN ? plan.substring(0, MAX_PLAN) + "\n[cut]" : plan;
        bus.publish(Origin.main(0), "plan.proposed", Json.object().put("plan", text));
        Prompter.Answer a = prompter == null ? Prompter.Answer.DENY
                : prompter.ask(new Operation("proposePlan", Operation.Kind.SESSION, "leave plan mode and carry out this plan",
                        Json.object().put("plan", text)));
        boolean yes = a == Prompter.Answer.ONCE || a == Prompter.Answer.ALWAYS;
        bus.publish(Origin.main(0), "plan.decided", Json.object().put("approved", yes));
        if (yes) {
            set(before, "plan");
        }
        return Json.write(Json.object().put("approved", yes));
    }
}
