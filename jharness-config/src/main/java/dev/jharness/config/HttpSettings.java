package dev.jharness.config;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code http} section (spec 016, D-054): how much of a response {@code api.http} and
 * {@code api.fetch} read, how many characters they return to the model, and, for debugging, whether
 * the {@code http.*} events carry headers and bodies. Logging is off by default; when headers are
 * logged, secret ones are redacted unless {@code redactSecrets} is false.
 */
public record HttpSettings(long maxResponseBytes, int maxChars, boolean logHeaders, boolean logBodies, boolean redactSecrets) {

    public static final long MIN_BYTES = 64L * 1024, MAX_BYTES = 50L * 1024 * 1024;
    public static final int MIN_CHARS = 1_000, MAX_CHARS = 1_000_000;

    public static HttpSettings defaults() {
        return new HttpSettings(10L * 1024 * 1024, 200_000, false, false, true);
    }

    static HttpSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"http\" must be an object");
        }
        long bytes = defaults().maxResponseBytes();
        int chars = defaults().maxChars();
        boolean headers = false, bodies = false, redact = true;
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            String where = source + ": http." + e.getKey();
            JsonNode v = e.getValue();
            switch (e.getKey()) {
                case "maxResponseBytes" -> {
                    if (!v.canConvertToLong() || !v.isIntegralNumber() || v.asLong() < MIN_BYTES || v.asLong() > MAX_BYTES) {
                        throw new ConfigException(where + "=" + v + ": expected a whole number of bytes from " + MIN_BYTES + " to " + MAX_BYTES);
                    }
                    bytes = v.asLong();
                }
                case "maxChars" -> {
                    if (!v.canConvertToInt() || !v.isIntegralNumber() || v.asInt() < MIN_CHARS || v.asInt() > MAX_CHARS) {
                        throw new ConfigException(where + "=" + v + ": expected a whole number from " + MIN_CHARS + " to " + MAX_CHARS);
                    }
                    chars = v.asInt();
                }
                case "logHeaders" -> headers = bool(v, where);
                case "logBodies" -> bodies = bool(v, where);
                case "redactSecrets" -> redact = bool(v, where);
                default -> throw new ConfigException(where
                        + " is not a known setting (known: maxResponseBytes, maxChars, logHeaders, logBodies, redactSecrets)");
            }
        }
        return new HttpSettings(bytes, chars, headers, bodies, redact);
    }

    private static boolean bool(JsonNode v, String where) throws ConfigException {
        if (!v.isBoolean()) {
            throw new ConfigException(where + "=" + v + ": expected true or false");
        }
        return v.asBoolean();
    }
}
