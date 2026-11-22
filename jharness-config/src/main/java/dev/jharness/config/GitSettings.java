package dev.jharness.config;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code git} section (spec 017, D-055): {@code credentials} maps a host to the name of a secret
 * (D-042) whose token git sends to that host only. Only the user's settings may set it.
 */
public record GitSettings(Map<String, String> credentials) {

    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*(:[0-9]{1,5})?");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public GitSettings {
        credentials = Map.copyOf(credentials);
    }

    public static GitSettings defaults() {
        return new GitSettings(Map.of());
    }

    static GitSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"git\" must be an object");
        }
        Map<String, String> creds = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : section.properties()) {
            String where = source + ": git." + e.getKey();
            if (!e.getKey().equals("credentials")) {
                throw new ConfigException(where + " is not a known setting (known: credentials)");
            }
            if (!e.getValue().isObject()) {
                throw new ConfigException(where + " must be an object of host to secret name");
            }
            for (Map.Entry<String, JsonNode> c : e.getValue().properties()) {
                String host = c.getKey().toLowerCase(Locale.ROOT);
                if (!HOST.matcher(host).matches()) {
                    throw new ConfigException(where + "." + c.getKey() + ": expected a host name such as github.com");
                }
                if (!c.getValue().isTextual() || !SECRET.matcher(c.getValue().asText()).matches()) {
                    throw new ConfigException(where + "." + c.getKey() + ": expected the name of a secret, such as GITHUB_TOKEN");
                }
                creds.put(host, c.getValue().asText());
            }
        }
        return new GitSettings(creds);
    }
}
