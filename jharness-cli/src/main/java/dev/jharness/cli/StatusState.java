package dev.jharness.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Event;
import dev.jharness.events.Json;

/**
 * The status line's values (spec 042, D-080), kept on the host from events so a refresh reads only these
 * fields: no lock on the agent loop, no process, no file. {@link #apply} says whether an event changed them.
 */
final class StatusState {

    String profile;
    String provider;
    String model;
    int contextTokens;
    int contextWindow;
    boolean contextEstimated = true;
    int compactAt;
    String mode = "ask";
    boolean plan;
    int turn;
    boolean busy;
    int cacheRead;
    int cacheWrite;
    int inputTokens;
    long cacheReadTotal;
    /** Every agent's cost so far (spec 049), and whether any request had a price. */
    double cost;
    boolean priced;
    String workspace = "";
    /** Background subagents and whether each is held on a question. */
    final Map<String, Boolean> agents = new LinkedHashMap<>();

    /** Updates the values from one event; true when the line may look different. */
    synchronized boolean apply(Event e) {
        var b = e.body();
        boolean main = e.agent().equals("main");
        switch (e.type()) {
            case "turn.started" -> {
                if (!main) {
                    return false;
                }
                turn = e.turn();
                busy = true;
            }
            case "turn.completed" -> {
                if (!main) {
                    return false;
                }
                busy = false;
                if (b.has("contextTokens")) {
                    contextTokens = b.get("contextTokens").asInt();
                    contextEstimated = false;
                }
                if (b.has("contextWindow")) {
                    contextWindow = b.get("contextWindow").asInt();
                }
            }
            case "model.completed" -> {
                boolean costed = b.has("costUsd");
                if (costed) {
                    cost += b.get("costUsd").asDouble();
                    priced = true;
                }
                if (!main) {
                    return costed;
                }
                int in = b.path("inputTokens").asInt();
                if (in > 0) {
                    inputTokens = in;
                    contextTokens = in + b.path("outputTokens").asInt();
                    contextEstimated = false;
                }
                cacheRead = b.path("cacheReadTokens").asInt();
                cacheWrite = b.path("cacheWriteTokens").asInt();
                cacheReadTotal += cacheRead;
            }
            case "context.compacted" -> {
                if (!main) {
                    return false;
                }
                contextTokens = b.path("tokensAfter").asInt(contextTokens);
                contextEstimated = true;
            }
            case "session.model_changed" -> {
                profile = b.path("profile").asText(profile);
                provider = b.path("provider").asText(provider);
                model = b.path("model").asText(model);
            }
            case "plan.mode" -> {
                // The mode itself is read from the session at each refresh.
                plan = b.path("plan").asBoolean();
            }
            case "subagent.started" -> {
                if (!b.path("background").asBoolean(false)) {
                    return false;
                }
                agents.put(e.agent(), false);
            }
            case "subagent.waiting" -> {
                if (!agents.containsKey(e.agent())) {
                    return false;
                }
                agents.put(e.agent(), true);
            }
            case "subagent.wait_ended" -> {
                if (!agents.containsKey(e.agent())) {
                    return false;
                }
                agents.put(e.agent(), false);
            }
            case "subagent.completed" -> {
                return agents.remove(e.agent()) != null;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The JSON a {@code statusline.js} gets (D-080). */
    synchronized ObjectNode snapshot(List<String> items) {
        ObjectNode o = Json.object();
        o.put("profile", profile).put("provider", provider).put("model", model);
        int percent = contextWindow > 0 ? (int) Math.round(100.0 * contextTokens / contextWindow) : 0;
        o.putObject("context").put("tokens", contextTokens).put("window", contextWindow).put("percent", percent)
                .put("estimated", contextEstimated).put("compactAt", compactAt);
        o.put("mode", mode).put("plan", plan);
        long held = agents.values().stream().filter(x -> x).count();
        var a = o.putObject("agents").put("running", agents.size() - held).put("held", held);
        var names = a.putArray("names");
        agents.keySet().forEach(names::add);
        o.putObject("cache").put("readTokens", cacheRead).put("writeTokens", cacheWrite).put("inputTokens", inputTokens)
                .put("readTotal", cacheReadTotal);
        if (priced) {
            o.put("costUsd", Math.round(cost * 1e6) / 1e6);
        } else {
            o.putNull("costUsd");
        }
        o.put("turn", turn).put("busy", busy).put("workspace", workspace);
        o.put("line", line(items));
        return o;
    }

    /** The built-in line: the chosen items, those with something to say, joined by " · ". */
    synchronized String line(List<String> items) {
        List<String> parts = new ArrayList<>();
        for (String i : items) {
            String p = switch (i) {
                case "model" -> profile == null || profile.equals(model) ? model : profile + " (" + model + ")";
                case "context" -> contextWindow <= 0 ? null
                        : (contextEstimated ? "~" : "") + Math.round(100.0 * contextTokens / contextWindow) + "% of " + kilo(contextWindow);
                case "mode" -> plan ? "[plan]" : mode;
                case "agents" -> {
                    long held = agents.values().stream().filter(x -> x).count();
                    long running = agents.size() - held;
                    if (agents.isEmpty()) {
                        yield null;
                    }
                    yield (running > 0 ? running + " running" : "") + (running > 0 && held > 0 ? ", " : "") + (held > 0 ? held + " held" : "");
                }
                case "cache" -> cacheRead > 0 ? "cache " + kilo(cacheRead) + " read" : null;
                case "cost" -> priced ? Usage.dollars(cost) : null;
                case "turn" -> turn > 0 ? "turn " + turn : null;
                case "workspace" -> workspace.isEmpty() ? null : workspace;
                default -> null;
            };
            if (p != null && !p.isEmpty()) {
                parts.add(p);
            }
        }
        return String.join(" · ", parts);
    }

    /** 200,000 → 200K, 1,500 → 1.5K, 1,000,000 → 1M. */
    static String kilo(long n) {
        if (n >= 1_000_000) {
            return trim(n / 1_000_000.0) + "M";
        }
        if (n >= 1_000) {
            return trim(n / 1_000.0) + "K";
        }
        return Long.toString(n);
    }

    private static String trim(double v) {
        return v >= 10 || Math.abs(v - Math.round(v)) < 0.05 ? Long.toString(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
