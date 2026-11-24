package dev.jharness.cli;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.events.Event;
import dev.jharness.events.Json;

/** One logged event as a line of text for {@code jharness log SESSION} (spec 021 AC-04, AC-05). */
final class LogFormat {

    /** Characters of a field value shown without {@code --full}. */
    static final int CUT = 120;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The main fields of each type, in order; other types show their whole body. */
    private static final Map<String, List<String>> FIELDS = Map.ofEntries(
            Map.entry("session.started", List.of("workspace", "provider", "model", "trusted", "offline")),
            Map.entry("session.crashed", List.of("exception", "report")),
            Map.entry("session.model_changed", List.of("profile", "provider", "model")),
            Map.entry("subagent.started", List.of("customAgent", "model", "maxSteps", "background", "task")),
            Map.entry("subagent.delivered", List.of("agents")),
            Map.entry("subagent.waiting", List.of("summary")),
            Map.entry("subagent.wait_ended", List.of("outcome")),
            Map.entry("subagent.completed", List.of("stopReason", "steps", "durationMs", "error")),
            Map.entry("turn.started", List.of("prompt")),
            Map.entry("turn.completed", List.of("steps", "stopReason", "error")),
            Map.entry("model.request", List.of("profile", "model", "attempt", "messages")),
            Map.entry("model.response", List.of("status", "attempt", "durationMs", "requestId")),
            Map.entry("model.completed", List.of("stopReason", "inputTokens", "outputTokens", "text")),
            Map.entry("model.error", List.of("message")),
            Map.entry("model.retry", List.of("reason", "attempt", "attempts", "waitMs")),
            Map.entry("loop.code", List.of("step", "lines")),
            Map.entry("loop.result", List.of("step", "status", "limit", "error")),
            Map.entry("api.call", List.of("api", "status", "summary")),
            Map.entry("permission.asked", List.of("api", "kind", "summary")),
            Map.entry("permission.decided", List.of("api", "allowed", "reason", "summary")),
            Map.entry("permission.rule_added", List.of("kind", "path", "op")),
            Map.entry("process.started", List.of("executable", "args")),
            Map.entry("process.completed", List.of("executable", "exitCode", "durationMs", "timedOut")),
            Map.entry("process.refused", List.of("executable", "reason")),
            Map.entry("process.retried", List.of("executable", "attempt", "reason")),
            Map.entry("file.changed", List.of("path")),
            Map.entry("checkpoint.restored", List.of("files", "turns")),
            Map.entry("network.allowed", List.of("host", "port", "reason")),
            Map.entry("network.blocked", List.of("host", "port", "reason")),
            Map.entry("network.asked", List.of("host", "port", "reason")),
            Map.entry("http.request", List.of("method", "url")),
            Map.entry("http.response", List.of("method", "url", "status", "durationMs")),
            Map.entry("sandbox.run_started", List.of("profile")),
            Map.entry("sandbox.run_completed", List.of("status", "durationMs")),
            Map.entry("sandbox.limit_exceeded", List.of("limit", "message")),
            Map.entry("sandbox.os", List.of("mechanism", "available", "reason")));

    private final ZoneId zone;
    private final boolean full;
    private LocalDate day;

    LogFormat(ZoneId zone, boolean full) {
        this.zone = zone;
        this.full = full;
    }

    /** A date line when the day changes, then {@code HH:mm:ss  t1  type  fields}. */
    String line(JsonNode e) {
        StringBuilder b = new StringBuilder();
        Instant ts = null;
        try {
            ts = Instant.parse(e.path("ts").asText());
        } catch (RuntimeException ignored) {
            // A line without a time still prints.
        }
        if (ts != null) {
            LocalDate d = ts.atZone(zone).toLocalDate();
            if (!d.equals(day)) {
                day = d;
                b.append("── ").append(d).append(" ──\n");
            }
            b.append(TIME.format(ts.atZone(zone)));
        } else {
            b.append("--:--:--");
        }
        String type = e.path("type").asText("?");
        b.append("  t").append(e.path("turn").asInt());
        String agent = e.path("agent").asText("main");
        if (!agent.equals("main")) {
            b.append(" [").append(TerminalSafe.escape(agent)).append(']');
        }
        b.append("  ").append(TerminalSafe.escape(type));
        String body = fields(type, e);
        if (!body.isEmpty()) {
            b.append("  ").append(body);
        }
        return b.toString();
    }

    private String fields(String type, JsonNode e) {
        var body = Json.object();
        e.properties().forEach(f -> {
            if (!Event.ENVELOPE.contains(f.getKey())) {
                body.set(f.getKey(), f.getValue());
            }
        });
        List<String> names = full ? null : FIELDS.get(type);
        if (names == null) {
            if (body.isEmpty()) {
                return "";
            }
            String all = Json.write(body);
            return TerminalSafe.escape(full ? all : cut(all, CUT * 2));
        }
        StringBuilder b = new StringBuilder();
        for (String n : names) {
            JsonNode v = body.get(n);
            if (v == null || v.isNull()) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(n).append('=').append(TerminalSafe.escape(cut(v.isTextual() ? v.asText() : Json.write(v), CUT)));
        }
        return b.toString();
    }

    static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Types that count as errors in the session list. */
    static boolean error(JsonNode e) {
        String t = e.path("type").asText();
        return ERRORS.contains(t) || t.equals("turn.completed") && e.path("stopReason").asText().equals("error");
    }

    private static final Set<String> ERRORS = Set.of("model.error", "session.crashed");
}
