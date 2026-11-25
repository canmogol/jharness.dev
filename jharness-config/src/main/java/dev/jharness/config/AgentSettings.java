package dev.jharness.config;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code agent} section: {@code maxSteps}, the most RunJS steps in one turn (1 to 500, default 100; D-051);
 * {@code subagentsPerTurn}, the most subagents one turn of the main agent may start (1 to 1000, default 10); and
 * {@code subagentMaxSteps}, a subagent's step limit when its request and custom agent set none (1 to 500,
 * default 100; D-064); and {@code subagentsRunning}, the most background subagents running at once (1 to 16,
 * default 4; D-065). A subagent never runs more steps than {@code maxSteps}. {@code backgroundQuestions}
 * ({@code wait}, the default, or {@code deny}) and {@code backgroundQuestionWait} (10s to 1h, default 10m)
 * say what a background subagent's approval question does (spec 033, D-072). {@code autoCompact} (default true) and
 * {@code compactAt} (0.5 to 0.95, default 0.8) say when a conversation is summarized to free its context (spec 039).
 * {@code autoTurns} (0 to 100, default 10) is how many turns in a row the main agent may take on its own when background
 * subagents finish, before it waits for a message from the user; 0 never starts one (spec 054, D-087).
 */
public record AgentSettings(int maxSteps, int subagentsPerTurn, int subagentMaxSteps, int subagentsRunning, String backgroundQuestions,
        java.time.Duration backgroundQuestionWait, boolean autoCompact, double compactAt, int autoTurns) {

    public static final int DEFAULT_AUTO_TURNS = 10;
    public static final int AUTO_TURNS_LIMIT = 100;

    /** The settings before spec 054, with {@code autoTurns} at its default. */
    public AgentSettings(int maxSteps, int subagentsPerTurn, int subagentMaxSteps, int subagentsRunning, String backgroundQuestions,
            java.time.Duration backgroundQuestionWait, boolean autoCompact, double compactAt) {
        this(maxSteps, subagentsPerTurn, subagentMaxSteps, subagentsRunning, backgroundQuestions, backgroundQuestionWait, autoCompact, compactAt,
                DEFAULT_AUTO_TURNS);
    }

    public static final double DEFAULT_COMPACT_AT = 0.80;

    /** The settings before spec 039, with compaction at its defaults. */
    public AgentSettings(int maxSteps, int subagentsPerTurn, int subagentMaxSteps, int subagentsRunning, String backgroundQuestions,
            java.time.Duration backgroundQuestionWait) {
        this(maxSteps, subagentsPerTurn, subagentMaxSteps, subagentsRunning, backgroundQuestions, backgroundQuestionWait, true, DEFAULT_COMPACT_AT);
    }

    public static final java.time.Duration DEFAULT_QUESTION_WAIT = java.time.Duration.ofMinutes(10);

    public static final int MAX_STEPS_LIMIT = 500;
    public static final int SUBAGENTS_PER_TURN_LIMIT = 1000;
    public static final int SUBAGENTS_RUNNING_LIMIT = 16;

    public static AgentSettings defaults() {
        return new AgentSettings(100, 10, 100, 4, "wait", DEFAULT_QUESTION_WAIT);
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
        String questions = d.backgroundQuestions();
        java.time.Duration wait = d.backgroundQuestionWait();
        boolean autoCompact = d.autoCompact();
        double compactAt = d.compactAt();
        int autoTurns = d.autoTurns();
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            switch (e.getKey()) {
                case "maxSteps" -> maxSteps = number(e, source, MAX_STEPS_LIMIT);
                case "subagentsPerTurn" -> perTurn = number(e, source, SUBAGENTS_PER_TURN_LIMIT);
                case "subagentMaxSteps" -> subSteps = number(e, source, MAX_STEPS_LIMIT);
                case "subagentsRunning" -> running = number(e, source, SUBAGENTS_RUNNING_LIMIT);
                case "backgroundQuestions" -> {
                    String v = e.getValue().asText();
                    if (!e.getValue().isTextual() || !v.equals("wait") && !v.equals("deny")) {
                        throw new ConfigException(source + ": agent.backgroundQuestions=" + e.getValue() + ": expected \"wait\" or \"deny\"");
                    }
                    questions = v;
                }
                case "backgroundQuestionWait" -> {
                    String c = e.getValue().isTextual() ? Units.duration(e.getValue().asText()) : null;
                    java.time.Duration dd = c == null ? null : Units.parseDuration(c);
                    if (dd == null || dd.compareTo(java.time.Duration.ofSeconds(10)) < 0 || dd.compareTo(java.time.Duration.ofHours(1)) > 0) {
                        throw new ConfigException(source + ": agent.backgroundQuestionWait=" + e.getValue().asText()
                                + ": expected a duration from 10s to 1h, such as 10m");
                    }
                    wait = dd;
                }
                case "autoCompact" -> {
                    if (!e.getValue().isBoolean()) {
                        throw new ConfigException(source + ": agent.autoCompact must be true or false");
                    }
                    autoCompact = e.getValue().asBoolean();
                }
                case "compactAt" -> {
                    JsonNode v = e.getValue();
                    if (!v.isNumber() || v.asDouble() < 0.5 || v.asDouble() > 0.95) {
                        throw new ConfigException(source + ": agent.compactAt=" + v.asText() + ": expected a share of the context window from 0.5 to 0.95");
                    }
                    compactAt = v.asDouble();
                }
                case "autoTurns" -> {
                    JsonNode v = e.getValue();
                    if (!v.isIntegralNumber() || v.asInt() < 0 || v.asInt() > AUTO_TURNS_LIMIT) {
                        throw new ConfigException(source + ": agent.autoTurns=" + v.asText() + ": expected a whole number from 0 to " + AUTO_TURNS_LIMIT);
                    }
                    autoTurns = v.asInt();
                }
                default -> throw new ConfigException(source + ": agent." + e.getKey()
                        + " is not a known setting (known: autoCompact, autoTurns, backgroundQuestionWait, backgroundQuestions, compactAt, maxSteps, subagentMaxSteps, subagentsPerTurn, subagentsRunning)");
            }
        }
        return new AgentSettings(maxSteps, perTurn, subSteps, running, questions, wait, autoCompact, compactAt, autoTurns);
    }

    private static int number(Map.Entry<String, JsonNode> e, String source, int max) throws ConfigException {
        JsonNode v = e.getValue();
        if (!v.isIntegralNumber() || v.asInt() < 1 || v.asInt() > max) {
            throw new ConfigException(source + ": agent." + e.getKey() + "=" + v.asText() + ": expected a whole number from 1 to " + max);
        }
        return v.asInt();
    }
}
