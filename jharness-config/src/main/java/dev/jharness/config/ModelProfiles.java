package dev.jharness.config;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** The {@code model} and {@code models} sections: named profiles and the default (D-032). */
public final class ModelProfiles {

    private static final Set<String> KEYS = Set.of("provider", "baseUrl", "model", "apiKeyEnv", "maxTokens", "notes", "retries", "retryMaxWait", "responseTimeout", "contextWindow", "promptCache");

    private final String defaultName;
    private final Map<String, ModelProfile> profiles;

    private ModelProfiles(String defaultName, Map<String, ModelProfile> profiles) {
        this.defaultName = defaultName;
        this.profiles = profiles;
    }

    public static ModelProfiles empty() {
        return new ModelProfiles(null, Map.of());
    }

    public Map<String, ModelProfile> all() {
        return profiles;
    }

    public String defaultName() {
        return defaultName;
    }

    /**
     * The profile with this name, or the default when {@code name} is null ({@code --model} unset).
     *
     * @throws ConfigException if the name is unknown or no default is set
     */
    public ModelProfile resolve(String name) throws ConfigException {
        String n = name != null ? name : defaultName;
        if (n == null) {
            throw new ConfigException("no model selected: set \"model\" in settings.json or pass a profile name"
                    + known());
        }
        ModelProfile p = profiles.get(n);
        if (p == null) {
            throw new ConfigException("unknown model profile \"" + n + "\"" + known());
        }
        return p;
    }

    private String known() {
        return profiles.isEmpty() ? " (no profiles are defined in \"models\")"
                : " (known: " + String.join(", ", profiles.keySet()) + ")";
    }

    static ModelProfiles parse(JsonNode model, JsonNode models, String source) throws ConfigException {
        return parse(model, models, source, true);
    }

    /** @param requireDefault false when checking one layer alone: its "model" may name a profile from another layer */
    static ModelProfiles parse(JsonNode model, JsonNode models, String source, boolean requireDefault) throws ConfigException {
        Map<String, ModelProfile> out = new LinkedHashMap<>();
        if (models != null) {
            if (!models.isObject()) {
                throw new ConfigException(source + ": \"models\" must be an object");
            }
            for (Map.Entry<String, JsonNode> e : models.properties()) {
                out.put(e.getKey(), profile(e.getKey(), e.getValue(), source));
            }
        }
        String def = null;
        if (model != null) {
            if (!model.isTextual()) {
                throw new ConfigException(source + ": \"model\" must be a profile name");
            }
            def = model.asText();
            if (requireDefault && !out.containsKey(def)) {
                throw new ConfigException(source + ": \"model\" names \"" + def + "\", which is not in \"models\"");
            }
        }
        return new ModelProfiles(def, Collections.unmodifiableMap(out));
    }

    /** True for key names that look like a secret value rather than a reference to one. */
    static boolean looksLikeSecret(String key) {
        String k = key.toLowerCase();
        if (k.equals("apikeyenv") || k.equals("maxtokens")) {
            return false;
        }
        return k.contains("key") || k.contains("token") || k.contains("secret") || k.contains("password");
    }

    private static ModelProfile profile(String name, JsonNode n, String source) throws ConfigException {
        String where = source + ": models." + name;
        if (!name.matches("[A-Za-z0-9_.-]+")) {
            throw new ConfigException(where + ": profile names use letters, digits, '.', '_' and '-'");
        }
        if (!n.isObject()) {
            throw new ConfigException(where + " must be an object");
        }
        for (Map.Entry<String, JsonNode> kv : n.properties()) {
            if (looksLikeSecret(kv.getKey())) {
                throw new ConfigException(where + "." + kv.getKey()
                        + ": secrets don't go in settings files; set \"apiKeyEnv\" to the name of an environment variable");
            }
            if (!KEYS.contains(kv.getKey())) {
                throw new ConfigException(where + "." + kv.getKey() + " is not a known setting (known: " + String.join(", ", KEYS) + ")");
            }
        }
        String provider = text(n, "provider", where, true);
        String model = text(n, "model", where, true);
        String baseUrl = text(n, "baseUrl", where, false);
        String apiKeyEnv = text(n, "apiKeyEnv", where, false);
        String notes = text(n, "notes", where, false);
        int maxTokens = 8192;
        if (n.has("maxTokens")) {
            if (!n.get("maxTokens").canConvertToInt() || n.get("maxTokens").asInt() <= 0) {
                throw new ConfigException(where + ".maxTokens: expected a whole number above zero");
            }
            maxTokens = n.get("maxTokens").asInt();
        }
        int retries = ModelProfile.DEFAULT_RETRIES;
        if (n.has("retries")) {
            JsonNode r = n.get("retries");
            if (!r.isIntegralNumber() || r.asInt() < 0 || r.asInt() > ModelProfile.MAX_RETRIES) {
                throw new ConfigException(where + ".retries: expected a whole number from 0 to " + ModelProfile.MAX_RETRIES);
            }
            retries = r.asInt();
        }
        java.time.Duration retryMaxWait = duration(n, "retryMaxWait", where, ModelProfile.DEFAULT_RETRY_MAX_WAIT,
                java.time.Duration.ofSeconds(1), ModelProfile.MAX_RETRY_MAX_WAIT, "1s", "10m");
        java.time.Duration responseTimeout = duration(n, "responseTimeout", where, ModelProfile.DEFAULT_RESPONSE_TIMEOUT,
                ModelProfile.MIN_RESPONSE_TIMEOUT, ModelProfile.MAX_RESPONSE_TIMEOUT, "5s", "30m");
        switch (provider) {
            case ModelProfile.OPENAI_COMPATIBLE -> {
                if (baseUrl == null) {
                    throw new ConfigException(where + ".baseUrl is required for openai-compatible (e.g. http://localhost:11434/v1)");
                }
            }
            case ModelProfile.ANTHROPIC -> {
                if (baseUrl == null) {
                    baseUrl = "https://api.anthropic.com";
                }
                if (apiKeyEnv == null) {
                    apiKeyEnv = "ANTHROPIC_API_KEY";
                }
            }
            default -> throw new ConfigException(where + ".provider=" + provider + ": expected openai-compatible or anthropic");
        }
        try {
            URI u = URI.create(baseUrl);
            if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme()) || u.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ".baseUrl=" + baseUrl + ": expected an http or https URL");
        }
        if (apiKeyEnv != null && !apiKeyEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new ConfigException(where + ".apiKeyEnv must be an environment variable name, not a key");
        }
        int window = ModelProfile.defaultContextWindow(provider);
        if (n.has("contextWindow")) {
            JsonNode w = n.get("contextWindow");
            if (!w.isIntegralNumber() || w.asLong() < ModelProfile.MIN_CONTEXT_WINDOW || w.asLong() > ModelProfile.MAX_CONTEXT_WINDOW) {
                throw new ConfigException(where + ".contextWindow: expected a whole number of tokens from " + ModelProfile.MIN_CONTEXT_WINDOW
                        + " to " + ModelProfile.MAX_CONTEXT_WINDOW);
            }
            window = w.asInt();
        }
        if (maxTokens >= window) {
            throw new ConfigException(where + ".maxTokens (" + maxTokens + ") must be below contextWindow (" + window + ")");
        }
        boolean cache = ModelProfile.ANTHROPIC.equals(provider);
        if (n.has("promptCache")) {
            if (!n.get("promptCache").isBoolean()) {
                throw new ConfigException(where + ".promptCache: expected true or false");
            }
            if (!ModelProfile.ANTHROPIC.equals(provider)) {
                throw new ConfigException(where + ".promptCache applies only to anthropic profiles; OpenAI-compatible servers cache prompts on their own");
            }
            cache = n.get("promptCache").asBoolean();
        }
        return new ModelProfile(name, provider, baseUrl.replaceAll("/+$", ""), model, apiKeyEnv, maxTokens, notes,
                retries, retryMaxWait, responseTimeout, window, cache);
    }

    private static java.time.Duration duration(JsonNode n, String key, String where, java.time.Duration dflt,
            java.time.Duration min, java.time.Duration max, String minText, String maxText) throws ConfigException {
        if (!n.has(key)) {
            return dflt;
        }
        String c = n.get(key).isTextual() ? Units.duration(n.get(key).asText()) : null;
        java.time.Duration d = c == null ? null : Units.parseDuration(c);
        if (d == null || d.compareTo(min) < 0 || d.compareTo(max) > 0) {
            throw new ConfigException(where + "." + key + "=" + n.get(key).asText() + ": expected a duration from " + minText
                    + " to " + maxText + ", such as 30s or 2m");
        }
        return d;
    }

    private static String text(JsonNode n, String key, String where, boolean required) throws ConfigException {
        JsonNode v = n.get(key);
        if (v == null) {
            if (required) {
                throw new ConfigException(where + "." + key + " is required");
            }
            return null;
        }
        if (!v.isTextual() || v.asText().isBlank()) {
            throw new ConfigException(where + "." + key + " must be a non-empty string");
        }
        return v.asText();
    }
}
