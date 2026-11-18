package dev.jharness.config;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code timeouts} section (D-028): per-operation timeouts and the output returned to the model.
 *
 * @param process default timeout for git (and later Maven, Gradle, npm)
 * @param processMax the most a single call may ask for
 * @param rg timeout for {@code rg}
 * @param outputChars characters of output returned to the model (at most 150,000)
 */
public record Timeouts(Duration process, Duration processMax, Duration rg, int outputChars) {

    public static final int MAX_OUTPUT_CHARS = 150_000;

    public static Timeouts defaults() {
        return new Timeouts(Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofSeconds(60), 30_000);
    }

    static Timeouts parse(JsonNode section, String source) throws ConfigException {
        Timeouts d = defaults();
        if (section == null) {
            return d;
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"timeouts\" must be an object");
        }
        Duration process = d.process;
        Duration processMax = d.processMax;
        Duration rg = d.rg;
        int chars = d.outputChars;
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            String where = source + ": timeouts." + e.getKey();
            String v = e.getValue().asText();
            switch (e.getKey()) {
                case "process" -> process = duration(where, v);
                case "processMax" -> processMax = duration(where, v);
                case "rg" -> rg = duration(where, v);
                case "outputChars" -> {
                    if (!e.getValue().canConvertToInt() || e.getValue().asInt() <= 0 || e.getValue().asInt() > MAX_OUTPUT_CHARS) {
                        throw new ConfigException(where + "=" + v + ": expected a whole number from 1 to " + MAX_OUTPUT_CHARS);
                    }
                    chars = e.getValue().asInt();
                }
                default -> throw new ConfigException(where + " is not a known setting (known: process, processMax, rg, outputChars)");
            }
        }
        if (process.compareTo(processMax) > 0) {
            throw new ConfigException(source + ": timeouts.process must not exceed timeouts.processMax");
        }
        return new Timeouts(process, processMax, rg, chars);
    }

    private static Duration duration(String where, String v) throws ConfigException {
        String c = Units.duration(v);
        if (c == null) {
            throw new ConfigException(where + "=" + v + ": expected a duration above zero such as 30s or 2m");
        }
        return Units.parseDuration(c);
    }
}
