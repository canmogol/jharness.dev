package dev.jharness.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/**
 * Token use and cost added up from {@code model.completed} events (spec 049, D-084): in total and for each agent.
 * The cost is the sum of the events' {@code costUsd}; a request without one, from a profile with no price, is
 * counted as unpriced.
 */
final class Usage {

    /** One agent's, or the total's, numbers. */
    static final class Tally {
        int requests;
        long input;
        long output;
        long cacheRead;
        long cacheWrite;
        double cost;
        int unpriced;

        void add(JsonNode b) {
            requests++;
            input += b.path("inputTokens").asLong();
            output += b.path("outputTokens").asLong();
            cacheRead += b.path("cacheReadTokens").asLong();
            cacheWrite += b.path("cacheWriteTokens").asLong();
            if (b.has("costUsd")) {
                cost += b.get("costUsd").asDouble();
            } else {
                unpriced++;
            }
        }

        void add(Tally t) {
            requests += t.requests;
            input += t.input;
            output += t.output;
            cacheRead += t.cacheRead;
            cacheWrite += t.cacheWrite;
            cost += t.cost;
            unpriced += t.unpriced;
        }

        /** Whether any request had a price. */
        boolean priced() {
            return requests > unpriced;
        }

        /** {@code $0.0123}, {@code $4.56}; {@code -} with no price; a {@code +} when some requests had none. */
        String costText() {
            if (!priced()) {
                return "-";
            }
            return dollars(cost) + (unpriced > 0 ? "+" : "");
        }

        ObjectNode json() {
            ObjectNode o = Json.object().put("requests", requests).put("inputTokens", input).put("outputTokens", output)
                    .put("cacheReadTokens", cacheRead).put("cacheWriteTokens", cacheWrite);
            if (priced()) {
                o.put("costUsd", Math.round(cost * 1e6) / 1e6);
            }
            o.put("unpricedRequests", unpriced);
            return o;
        }
    }

    final Tally total = new Tally();
    /** By agent, in the order they first made a request: main, sub-1, … */
    final Map<String, Tally> agents = new LinkedHashMap<>();

    /** Counts one event; anything other than {@code model.completed} is ignored. */
    synchronized void add(String type, String agent, JsonNode body) {
        if (!type.equals("model.completed")) {
            return;
        }
        total.add(body);
        agents.computeIfAbsent(agent == null || agent.isEmpty() ? "main" : agent, a -> new Tally()).add(body);
    }

    /** One logged event (a parsed log line). */
    void add(JsonNode event) {
        add(event.path("type").asText(), event.path("agent").asText("main"), event);
    }

    /** A session log's usage; lines that aren't JSON are skipped. */
    static Usage ofLog(Path log) throws IOException {
        Usage u = new Usage();
        for (LogCommand.Line l : LogCommand.read(log)) {
            if (l.event() != null) {
                u.add(l.event());
            }
        }
        return u;
    }

    /** A table with one row per key, then a total row when there is more than one. */
    static List<String> table(String first, Map<String, Tally> rows, Tally total) {
        TextTable t = new TextTable(first, "REQUESTS", "INPUT", "OUTPUT", "CACHE-READ", "CACHE-WRITE", "COST");
        rows.forEach((k, v) -> t.row(row(k, v)));
        if (rows.size() > 1) {
            t.row(row("total", total));
        }
        return t.lines();
    }

    /** The number columns of a row, after its leading ones. */
    static String[] row(String name, Tally v) {
        return new String[] {name, String.format(Locale.ROOT, "%,d", v.requests), String.format(Locale.ROOT, "%,d", v.input),
                String.format(Locale.ROOT, "%,d", v.output), String.format(Locale.ROOT, "%,d", v.cacheRead),
                String.format(Locale.ROOT, "%,d", v.cacheWrite), v.costText()};
    }

    /** The notes printed under a table: what INPUT counts, and how to price unpriced requests. */
    static List<String> notes(Tally total) {
        List<String> out = new ArrayList<>();
        out.add("INPUT is the whole prompt, cached tokens included.");
        if (total.unpriced > 0) {
            out.add(total.unpriced == 1 ? "1 request has no price: set \"price\" in its model profile, in US dollars per million tokens."
                    : total.unpriced + " requests have no price: set \"price\" in their model profiles, in US dollars per million tokens.");
        }
        return out;
    }

    static String dollars(double d) {
        return "$" + String.format(Locale.ROOT, d < 1 ? "%.4f" : "%,.2f", d);
    }
}
