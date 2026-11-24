package dev.jharness.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Known host event types and the body fields each must carry. Guests may emit only {@code agent.*} and {@code custom.*}. */
public final class EventTypes {

    /** Maximum size of a guest event as received, in bytes of UTF-8 (D-006). */
    public static final int MAX_GUEST_EVENT_BYTES = 64 * 1024;

    private static final Map<String, List<String>> HOST = new LinkedHashMap<>();

    static {
        HOST.put("session.started", List.of("workspace"));
        HOST.put("session.crashed", List.of("exception", "report"));
        HOST.put("session.model_changed", List.of("profile", "provider", "model"));
        HOST.put("project.trusted", List.of("trusted"));
        HOST.put("checkpoint.saved", List.of("path"));
        HOST.put("checkpoint.restored", List.of("files", "turns"));
        HOST.put("file.changed", List.of("path", "diff"));
        HOST.put("settings.ignored", List.of("keys"));
        HOST.put("api.call", List.of("api", "status"));
        HOST.put("permission.decided", List.of("api", "kind", "allowed"));
        HOST.put("permission.asked", List.of("api", "kind", "summary"));
        HOST.put("permission.rule_added", List.of("kind"));
        HOST.put("process.started", List.of("executable", "args"));
        HOST.put("process.completed", List.of("executable", "exitCode", "durationMs", "output"));
        HOST.put("process.refused", List.of("executable", "path", "reason"));
        HOST.put("process.retried", List.of("executable", "attempt", "attempts", "reason"));
        HOST.put("http.request", List.of("method", "url"));
        HOST.put("http.response", List.of("method", "url", "status", "durationMs"));
        HOST.put("sandbox.run_started", List.of("profile"));
        HOST.put("sandbox.run_completed", List.of("status", "durationMs"));
        HOST.put("sandbox.limit_exceeded", List.of("limit", "message"));
        HOST.put("sandbox.watchdog", List.of("step"));
        HOST.put("sandbox.memory_protection", List.of("available"));
        HOST.put("model.request", List.of("provider", "model"));
        HOST.put("model.delta", List.of("text"));
        HOST.put("model.thinking_delta", List.of("text"));
        HOST.put("model.response", List.of("provider", "model", "attempt", "status", "durationMs"));
        HOST.put("model.completed", List.of("provider", "model", "stopReason"));
        HOST.put("model.error", List.of("provider", "message"));
        HOST.put("model.retry", List.of("provider", "model", "attempt", "attempts", "waitMs", "reason"));
        HOST.put("subagent.started", List.of("task", "customAgent", "maxSteps", "background"));
        HOST.put("subagent.completed", List.of("stopReason", "steps", "durationMs"));
        HOST.put("subagent.delivered", List.of("agents"));
        HOST.put("subagent.waiting", List.of("api", "summary"));
        HOST.put("subagent.wait_ended", List.of("outcome"));
        HOST.put("todo.updated", List.of("items"));
        HOST.put("plan.mode", List.of("plan", "mode"));
        HOST.put("plan.proposed", List.of("plan"));
        HOST.put("plan.decided", List.of("approved"));
        HOST.put("commands.loaded", List.of("commands"));
        HOST.put("command.ran", List.of("name", "from", "kind", "outcome"));
        HOST.put("hooks.loaded", List.of("hooks"));
        HOST.put("hook.ran", List.of("hook", "event", "outcome"));
        HOST.put("tools.loaded", List.of("tools", "projectTools"));
        HOST.put("tools.project_ignored", List.of("tools", "reason"));
        HOST.put("turn.started", List.of("prompt"));
        HOST.put("turn.completed", List.of("steps", "stopReason"));
        HOST.put("loop.code", List.of("step", "lines"));
        HOST.put("loop.result", List.of("step", "status"));
        HOST.put("network.allowed", List.of("host", "port", "reason"));
        HOST.put("network.blocked", List.of("host", "port", "reason"));
        HOST.put("network.asked", List.of("host", "port", "reason"));
        HOST.put("sandbox.os", List.of("mechanism", "available"));
    }

    private EventTypes() {}

    public static boolean isHostType(String type) {
        return HOST.containsKey(type);
    }

    /** {@code agent.*} or {@code custom.*} with a non-empty name of letters, digits, '.', '_' or '-'. */
    public static boolean isGuestType(String type) {
        return type != null && type.matches("(agent|custom)\\.[A-Za-z0-9_.-]+");
    }

    /** Throws if a host event of this type lacks a required field. */
    static void validateHost(String type, ObjectNode body) {
        List<String> required = HOST.get(type);
        if (required == null) {
            throw new EventException("unknown host event type " + type);
        }
        for (String f : required) {
            if (!body.has(f)) {
                throw new EventException(type + " is missing field " + f);
            }
        }
    }
}
