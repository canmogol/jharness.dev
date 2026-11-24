package dev.jharness.config;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code reviewer} section (spec 038, D-031): an optional decision model behind the System One API
 * ({@code POST /v1/systemone}) that reviews each state-changing operation and may only tighten the decision.
 * Only the user's own settings may set it: a project could point it at its own server or turn it off.
 *
 * <pre>
 * "reviewer": {"enabled": true, "url": "https://api.typesafe.ai/v1/systemone", "model": "jev-latest",
 *              "apiKeyEnv": "SYSTEMONE_API_KEY", "askBelow": 0.90, "denyBelow": 0.20, "timeoutMs": 3000, "onError": "ask"}
 * </pre>
 *
 * @param apiKeyEnv the secret (D-042) with the bearer token, or null for a local server without one
 * @param onError {@code ask} or {@code deny}: what an unreachable, slow or unreadable reviewer means
 */
public record ReviewerSettings(boolean enabled, String url, String model, String apiKeyEnv, double askBelow, double denyBelow,
        Duration timeout, String onError) {

    static final Set<String> KEYS = Set.of("enabled", "url", "model", "apiKeyEnv", "askBelow", "denyBelow", "timeoutMs", "onError");
    static final Pattern SECRET = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final double DEFAULT_ASK_BELOW = 0.90;
    public static final double DEFAULT_DENY_BELOW = 0.20;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    public static final long MIN_TIMEOUT_MS = 500;
    public static final long MAX_TIMEOUT_MS = 60_000;

    public static ReviewerSettings defaults() {
        return new ReviewerSettings(false, null, DEFAULT_MODEL, null, DEFAULT_ASK_BELOW, DEFAULT_DENY_BELOW, DEFAULT_TIMEOUT, "ask");
    }

    static ReviewerSettings parse(JsonNode n, String source) throws ConfigException {
        if (n == null) {
            return defaults();
        }
        String where = source + ": reviewer";
        if (!n.isObject()) {
            throw new ConfigException(where + " must be an object");
        }
        for (var it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!KEYS.contains(k)) {
                throw new ConfigException(where + "." + k + " is not a known setting (known: " + String.join(", ", new TreeSet<>(KEYS)) + ")");
            }
        }
        boolean enabled = false;
        if (n.has("enabled")) {
            if (!n.get("enabled").isBoolean()) {
                throw new ConfigException(where + ".enabled must be true or false");
            }
            enabled = n.get("enabled").asBoolean();
        }
        String url = text(n, "url", where);
        if (url != null && !url.matches("https?://[^\\s/?#]+(/[^\\s?#]*)?")) {
            throw new ConfigException(where + ".url must be an http or https URL, such as https://api.typesafe.ai/v1/systemone");
        }
        if (enabled && url == null) {
            throw new ConfigException(where + ".url is needed when the reviewer is enabled (the System One endpoint, ending in /v1/systemone)");
        }
        String model = text(n, "model", where);
        if (model == null) {
            model = DEFAULT_MODEL;
        } else if (!MODEL.matcher(model).matches()) {
            throw new ConfigException(where + ".model must be a model name, such as jev-latest");
        }
        String key = text(n, "apiKeyEnv", where);
        if (key != null && !SECRET.matcher(key).matches()) {
            throw new ConfigException(where + ".apiKeyEnv must be the name of a secret, such as SYSTEMONE_API_KEY");
        }
        double ask = number(n, "askBelow", DEFAULT_ASK_BELOW, where);
        double deny = number(n, "denyBelow", DEFAULT_DENY_BELOW, where);
        if (deny > ask) {
            throw new ConfigException(where + ".denyBelow (" + deny + ") must not be above askBelow (" + ask + ")");
        }
        Duration timeout = DEFAULT_TIMEOUT;
        if (n.has("timeoutMs")) {
            JsonNode t = n.get("timeoutMs");
            if (!t.canConvertToLong() || !t.isIntegralNumber() || t.asLong() < MIN_TIMEOUT_MS || t.asLong() > MAX_TIMEOUT_MS) {
                throw new ConfigException(where + ".timeoutMs must be a whole number from " + MIN_TIMEOUT_MS + " to " + MAX_TIMEOUT_MS);
            }
            timeout = Duration.ofMillis(t.asLong());
        }
        String onError = text(n, "onError", where);
        if (onError == null) {
            onError = "ask";
        } else if (!onError.equals("ask") && !onError.equals("deny")) {
            throw new ConfigException(where + ".onError must be \"ask\" or \"deny\" (a failing reviewer never allows)");
        }
        return new ReviewerSettings(enabled, url, model, key, ask, deny, timeout, onError);
    }

    private static String text(JsonNode n, String k, String where) throws ConfigException {
        JsonNode v = n.get(k);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new ConfigException(where + "." + k + " must be a non-empty string");
        }
        return v.asText().strip();
    }

    private static double number(JsonNode n, String k, double dflt, String where) throws ConfigException {
        JsonNode v = n.get(k);
        if (v == null) {
            return dflt;
        }
        if (!v.isNumber() || v.asDouble() < 0 || v.asDouble() > 1) {
            throw new ConfigException(where + "." + k + " must be a number from 0 to 1");
        }
        return v.asDouble();
    }
}
