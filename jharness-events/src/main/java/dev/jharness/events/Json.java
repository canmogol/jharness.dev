package dev.jharness.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared Jackson tree-model helpers. No DTO binding, so no reflection config is needed (D-006). */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    /** Parses JSON text; throws {@link IllegalArgumentException} with a short message on bad input. */
    public static JsonNode parse(String text) {
        try {
            JsonNode n = MAPPER.readTree(text);
            if (n == null || n.isMissingNode()) {
                throw new IllegalArgumentException("empty JSON");
            }
            return n;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage());
        }
    }

    /** Parses JSON text that must be an object. */
    public static ObjectNode parseObject(String text) {
        JsonNode n = parse(text);
        if (!n.isObject()) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (ObjectNode) n;
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
