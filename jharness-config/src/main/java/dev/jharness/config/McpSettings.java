package dev.jharness.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The {@code mcp} section (spec 037, D-023, D-076): the MCP servers the agent may call through
 * {@code api.mcp}. Only the user's own settings may set it; a project's {@code mcp} is always ignored.
 *
 * <pre>
 * "mcp": {"servers": {
 *   "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."], "writable": ["~/.npm"]},
 *   "docs":  {"url": "https://mcp.example.com/mcp", "tokenSecret": "DOCS_TOKEN"}
 * }}
 * </pre>
 */
public record McpSettings(Map<String, Server> servers) {

    /**
     * One server: {@code command} (a program on {@code PATH}) with {@code args}, {@code env}, {@code secretEnv}
     * (variable to secret name) and {@code writable} home folders; or {@code url} with {@code tokenSecret}.
     */
    public record Server(String name, String command, List<String> args, Map<String, String> env, Map<String, String> secretEnv,
            List<String> writable, String url, String tokenSecret, Duration timeout) {

        public boolean stdio() {
            return command != null;
        }
    }

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    static final Pattern SECRET = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    static final Set<String> KEYS = Set.of("command", "args", "env", "secretEnv", "writable", "url", "tokenSecret", "timeout");
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    public McpSettings {
        servers = Collections.unmodifiableMap(new LinkedHashMap<>(servers));
    }

    public static McpSettings defaults() {
        return new McpSettings(Map.of());
    }

    static McpSettings parse(JsonNode section, String source) throws ConfigException {
        if (section == null) {
            return defaults();
        }
        if (!section.isObject()) {
            throw new ConfigException(source + ": \"mcp\" must be an object with \"servers\"");
        }
        Map<String, Server> out = new LinkedHashMap<>();
        for (var e : section.properties()) {
            if (!e.getKey().equals("servers")) {
                throw new ConfigException(source + ": mcp." + e.getKey() + " is not a known setting (known: servers)");
            }
            if (!e.getValue().isObject()) {
                throw new ConfigException(source + ": mcp.servers must be an object of server name to server");
            }
            for (var s : e.getValue().properties()) {
                out.put(s.getKey(), server(s.getKey(), s.getValue(), source + ": mcp.servers." + s.getKey()));
            }
        }
        return new McpSettings(out);
    }

    private static Server server(String name, JsonNode n, String where) throws ConfigException {
        if (!NAME.matcher(name).matches()) {
            throw new ConfigException(where + ": server names use lower-case letters, digits and '-', such as github");
        }
        if (!n.isObject()) {
            throw new ConfigException(where + " must be an object with \"command\" or \"url\"");
        }
        for (var it = n.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!KEYS.contains(k)) {
                throw new ConfigException(where + "." + k + " is not a known setting (known: " + String.join(", ", new TreeSet<>(KEYS)) + ")");
            }
        }
        String command = text(n, "command", where);
        String url = text(n, "url", where);
        if ((command == null) == (url == null)) {
            throw new ConfigException(where + " needs exactly one of \"command\" (a stdio server) or \"url\" (an HTTP server)");
        }
        if (command != null && (command.contains("/") || command.isBlank())) {
            throw new ConfigException(where + ".command must be a program name looked up on PATH, such as npx, not a path");
        }
        if (url != null && !url.matches("https?://[^\\s/]+(/\\S*)?")) {
            throw new ConfigException(where + ".url must be an http or https URL");
        }
        if (url != null && (n.has("args") || n.has("env") || n.has("secretEnv") || n.has("writable"))) {
            throw new ConfigException(where + ": args, env, secretEnv and writable are for stdio servers (\"command\")");
        }
        if (command != null && n.has("tokenSecret")) {
            throw new ConfigException(where + ".tokenSecret is for HTTP servers (\"url\"); use secretEnv for a stdio server");
        }
        List<String> args = strings(n.get("args"), where + ".args");
        Map<String, String> env = map(n.get("env"), where + ".env", false);
        Map<String, String> secretEnv = map(n.get("secretEnv"), where + ".secretEnv", true);
        List<String> writable = strings(n.get("writable"), where + ".writable");
        for (String w : writable) {
            if (!w.startsWith("~/") || w.contains("..") || w.length() < 3) {
                throw new ConfigException(where + ".writable entries are folders below your home, written as ~/…");
            }
        }
        String token = text(n, "tokenSecret", where);
        if (token != null && !SECRET.matcher(token).matches()) {
            throw new ConfigException(where + ".tokenSecret must be the name of a secret, such as DOCS_TOKEN");
        }
        Duration timeout = DEFAULT_TIMEOUT;
        if (n.has("timeout")) {
            String c = n.get("timeout").isTextual() ? Units.duration(n.get("timeout").asText()) : null;
            Duration d = c == null ? null : Units.parseDuration(c);
            if (d == null || d.compareTo(Duration.ofSeconds(1)) < 0 || d.compareTo(MAX_TIMEOUT) > 0) {
                throw new ConfigException(where + ".timeout: expected a duration from 1s to 10m, such as 60s");
            }
            timeout = d;
        }
        return new Server(name, command, args, env, secretEnv, writable, url, token, timeout);
    }

    private static String text(JsonNode n, String key, String where) throws ConfigException {
        JsonNode v = n.get(key);
        if (v == null) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ConfigException(where + "." + key + " must be a string");
        }
        return v.asText();
    }

    private static List<String> strings(JsonNode n, String where) throws ConfigException {
        List<String> out = new ArrayList<>();
        if (n == null) {
            return out;
        }
        if (!n.isArray()) {
            throw new ConfigException(where + " must be a list of strings");
        }
        for (JsonNode x : n) {
            if (!x.isTextual() || x.asText().indexOf('\0') >= 0) {
                throw new ConfigException(where + " must be a list of strings");
            }
            out.add(x.asText());
        }
        return List.copyOf(out);
    }

    private static Map<String, String> map(JsonNode n, String where, boolean secrets) throws ConfigException {
        Map<String, String> out = new LinkedHashMap<>();
        if (n == null) {
            return out;
        }
        if (!n.isObject()) {
            throw new ConfigException(where + " must map variable names to " + (secrets ? "secret names" : "strings"));
        }
        for (var e : n.properties()) {
            if (!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !e.getValue().isTextual()
                    || secrets && !SECRET.matcher(e.getValue().asText()).matches()) {
                throw new ConfigException(where + "." + e.getKey() + ": expected " + (secrets ? "a secret name" : "a string value"));
            }
            out.put(e.getKey(), e.getValue().asText());
        }
        return Collections.unmodifiableMap(out);
    }
}
