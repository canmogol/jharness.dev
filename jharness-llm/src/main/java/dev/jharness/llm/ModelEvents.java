package dev.jharness.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.config.ModelPrice;
import dev.jharness.events.Json;

/** The {@code model.completed} body every provider publishes (D-046): reply and thinking, each capped. */
final class ModelEvents {

    /** Most characters of {@code text} or {@code thinking} put on the event. */
    static final int MAX_CHARS = 64 * 1024;

    private ModelEvents() {}

    static ObjectNode completed(String provider, String model, ChatReply r) {
        return completed(provider, model, r, null);
    }

    /** @param price the profile's price, or null; with one, {@code costUsd} is the request's cost (spec 049) */
    static ObjectNode completed(String provider, String model, ChatReply r, ModelPrice price) {
        ObjectNode b = Json.object().put("provider", provider).put("model", model).put("stopReason", r.stopReason())
                .put("inputTokens", r.inputTokens()).put("outputTokens", r.outputTokens());
        if (r.cacheReadTokens() > 0) {
            b.put("cacheReadTokens", r.cacheReadTokens());
        }
        if (r.cacheWriteTokens() > 0) {
            b.put("cacheWriteTokens", r.cacheWriteTokens());
        }
        if (price != null) {
            double c = price.cost(r.inputTokens(), r.outputTokens(), r.cacheReadTokens(), r.cacheWriteTokens());
            b.put("costUsd", Math.round(c * 1e8) / 1e8);
        }
        put(b, "text", r.text());
        if (!r.thinking().isEmpty()) {
            put(b, "thinking", r.thinking());
        }
        return b;
    }

    private static void put(ObjectNode b, String key, String value) {
        if (value.length() <= MAX_CHARS) {
            b.put(key, value);
        } else {
            b.put(key, value.substring(0, MAX_CHARS));
            b.put(key + "Cut", value.length() - MAX_CHARS);
        }
    }
}
