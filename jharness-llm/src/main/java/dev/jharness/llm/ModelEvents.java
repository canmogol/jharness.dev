package dev.jharness.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/** The {@code model.completed} body every provider publishes (D-046): reply and thinking, each capped. */
final class ModelEvents {

    /** Most characters of {@code text} or {@code thinking} put on the event. */
    static final int MAX_CHARS = 64 * 1024;

    private ModelEvents() {}

    static ObjectNode completed(String provider, String model, ChatReply r) {
        ObjectNode b = Json.object().put("provider", provider).put("model", model).put("stopReason", r.stopReason())
                .put("inputTokens", r.inputTokens()).put("outputTokens", r.outputTokens());
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
