package dev.jharness.config;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** The {@code agent} section: {@code maxSteps}, the most RunJS steps in one turn (1 to 200, default 25). */
public record AgentSettings(int maxSteps) {

    public static final int MAX_STEPS_LIMIT = 200;

    public static AgentSettings defaults() {
        return new AgentSettings(25);
    }

    static AgentSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"agent\" must be an object");
        }
        int maxSteps = defaults().maxSteps();
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            if (!e.getKey().equals("maxSteps")) {
                throw new ConfigException(source + ": agent." + e.getKey() + " is not a known setting (known: maxSteps)");
            }
            JsonNode v = e.getValue();
            if (!v.canConvertToInt() || v.asInt() < 1 || v.asInt() > MAX_STEPS_LIMIT) {
                throw new ConfigException(source + ": agent.maxSteps=" + v.asText() + ": expected a whole number from 1 to " + MAX_STEPS_LIMIT);
            }
            maxSteps = v.asInt();
        }
        return new AgentSettings(maxSteps);
    }
}
