package dev.jharness.config;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code agent} section: {@code maxSteps}, the most RunJS steps in one turn (1 to 500, default 100; D-051);
 * {@code subagentsPerTurn}, the most subagents one turn of the main agent may start (1 to 1000, default 10); and
 * {@code subagentMaxSteps}, a subagent's step limit when its request and custom agent set none (1 to 500,
 * default 100; D-064); and {@code subagentsRunning}, the most background subagents running at once (1 to 16,
 * default 4; D-065). A subagent never runs more steps than {@code maxSteps}.
 */
public record AgentSettings(int maxSteps, int subagentsPerTurn, int subagentMaxSteps, int subagentsRunning) {

    public static final int MAX_STEPS_LIMIT = 500;
    public static final int SUBAGENTS_PER_TURN_LIMIT = 1000;
    public static final int SUBAGENTS_RUNNING_LIMIT = 16;

    public static AgentSettings defaults() {
        return new AgentSettings(100, 10, 100, 4);
    }

    static AgentSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"agent\" must be an object");
        }
        AgentSettings d = defaults();
        int maxSteps = d.maxSteps();
        int perTurn = d.subagentsPerTurn();
        int subSteps = d.subagentMaxSteps();
        int running = d.subagentsRunning();
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            switch (e.getKey()) {
                case "maxSteps" -> maxSteps = number(e, source, MAX_STEPS_LIMIT);
                case "subagentsPerTurn" -> perTurn = number(e, source, SUBAGENTS_PER_TURN_LIMIT);
                case "subagentMaxSteps" -> subSteps = number(e, source, MAX_STEPS_LIMIT);
                case "subagentsRunning" -> running = number(e, source, SUBAGENTS_RUNNING_LIMIT);
                default -> throw new ConfigException(source + ": agent." + e.getKey()
                        + " is not a known setting (known: maxSteps, subagentMaxSteps, subagentsPerTurn, subagentsRunning)");
            }
        }
        return new AgentSettings(maxSteps, perTurn, subSteps, running);
    }

    private static int number(Map.Entry<String, JsonNode> e, String source, int max) throws ConfigException {
        JsonNode v = e.getValue();
        if (!v.isIntegralNumber() || v.asInt() < 1 || v.asInt() > max) {
            throw new ConfigException(source + ": agent." + e.getKey() + "=" + v.asText() + ": expected a whole number from 1 to " + max);
        }
        return v.asInt();
    }
}
