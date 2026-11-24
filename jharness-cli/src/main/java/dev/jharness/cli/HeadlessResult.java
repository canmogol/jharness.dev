package dev.jharness.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.agent.TurnResult;
import dev.jharness.events.Event;
import dev.jharness.events.Json;

/**
 * The result object of {@code jharness -p --output json|stream-json} (spec 047, D-082), gathered from the session's
 * events: token use for every agent, the files changed, and the actions denied.
 */
final class HeadlessResult {

    private final String session;
    private final long started = System.nanoTime();
    private long input;
    private long outputTokens;
    private long cacheRead;
    private long cacheWrite;
    private int requests;
    private final Set<String> files = new LinkedHashSet<>();
    private final List<ObjectNode> denied = new ArrayList<>();
    private final Set<String> subagents = new LinkedHashSet<>();

    HeadlessResult(String session) {
        this.session = session;
    }

    synchronized void onEvent(Event e) {
        var b = e.body();
        switch (e.type()) {
            case "model.completed" -> {
                requests++;
                input += b.path("inputTokens").asLong();
                outputTokens += b.path("outputTokens").asLong();
                cacheRead += b.path("cacheReadTokens").asLong();
                cacheWrite += b.path("cacheWriteTokens").asLong();
            }
            case "file.changed" -> files.add(b.path("path").asText());
            case "permission.decided" -> {
                if (!b.path("allowed").asBoolean(true)) {
                    denied.add(Json.object().put("agent", e.agent()).put("api", b.path("api").asText()).put("summary", b.path("summary").asText())
                            .put("reason", b.path("reason").asText()));
                }
            }
            case "subagent.started" -> subagents.add(e.agent());
            default -> {
                // Only these count.
            }
        }
    }

    synchronized ObjectNode result(TurnResult r, String reply, int exitCode) {
        ObjectNode o = Json.object().put("type", "result").put("session", session).put("stopReason", r.stopReason())
                .put("reply", reply == null ? "" : reply).put("steps", r.steps());
        if (r.error() != null) {
            o.put("error", r.error());
        }
        o.put("exitCode", exitCode).put("durationMs", (System.nanoTime() - started) / 1_000_000);
        o.putObject("usage").put("requests", requests).put("inputTokens", input).put("outputTokens", outputTokens)
                .put("cacheReadTokens", cacheRead).put("cacheWriteTokens", cacheWrite);
        var f = o.putArray("filesChanged");
        files.forEach(f::add);
        var d = o.putArray("denied");
        denied.forEach(d::add);
        var s = o.putArray("subagents");
        subagents.forEach(s::add);
        return o;
    }
}
