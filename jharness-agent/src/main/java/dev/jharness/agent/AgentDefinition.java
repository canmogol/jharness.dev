package dev.jharness.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.ConfigException;
import dev.jharness.events.Json;

/**
 * A custom agent (spec 025, D-017, D-063): a folder {@code .jharness/agents/NAME/} in the project or
 * {@code ~/.jharness/agents/NAME/} with {@code agent.json}, an optional {@code prompt.md} added to the
 * system prompt and an optional {@code helpers.js} defined before every block. An agent can only narrow
 * what the session may do: fewer {@code api} methods, plan mode, fewer steps.
 *
 * @param tools the {@code api} methods it may call, or null for all
 * @param maxSteps its step limit, or null for the session's
 */
public record AgentDefinition(String name, String source, Path dir, String description, String model, Set<String> tools,
        Integer maxSteps, boolean plan, String prompt, String helpers) {

    /** Agent folder names. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /** The {@code api} methods an agent may list in {@code tools}; {@code readContext} is always allowed. */
    public static final Set<String> API = Set.of("readContext", "readFile", "writeFile", "editFile", "listFiles", "grep", "git",
            "tool", "tools", "http", "fetch", "emit", "spawnAgent", "models", "waitAgent", "agents", "stopAgent");

    static final Set<String> KEYS = Set.of("description", "model", "tools", "maxSteps", "plan");

    /** Largest {@code prompt.md} or {@code helpers.js}. */
    public static final int MAX_FILE_BYTES = 32 * 1024;

    /** An agent folder found on disk; {@code ignored} when it is in a project that isn't trusted. */
    public record Found(String name, String source, Path dir, boolean ignored) {}

    /**
     * The agents in {@code ~/.jharness/agents} and the project's {@code .jharness/agents}, by name. A
     * project agent replaces a user agent of the same name. Project agents of an untrusted project are
     * listed as ignored: their prompt and helpers come from the repository, and their model could send
     * the code to another server (D-011).
     */
    public static List<Found> find(Path home, Path workspace, boolean trusted) {
        Map<String, Found> m = new java.util.TreeMap<>();
        for (Found f : folders(home.resolve(".jharness").resolve("agents"), "user", false)) {
            m.put(f.name(), f);
        }
        for (Found f : folders(workspace.resolve(".jharness").resolve("agents"), "project", !trusted)) {
            if (!f.ignored() || !m.containsKey(f.name())) {
                m.put(f.name(), f);
            }
        }
        return List.copyOf(m.values());
    }

    private static List<Found> folders(Path root, String source, boolean ignored) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Found> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.sorted(Comparator.comparing(Path::toString)).toList()) {
                String n = d.getFileName().toString();
                if (NAME.matcher(n).matches() && Files.isDirectory(d) && Files.isRegularFile(d.resolve("agent.json"))) {
                    out.add(new Found(n, source, d, ignored));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /** The agent named {@code name}, read and checked; unknown, ignored or invalid agents stop start-up. */
    public static AgentDefinition resolve(Path home, Path workspace, boolean trusted, String name) throws ConfigException {
        List<Found> all = find(home, workspace, trusted);
        for (Found f : all) {
            if (f.name().equals(name)) {
                if (f.ignored()) {
                    throw new ConfigException("agent \"" + name + "\" is in .jharness/agents of a project you haven't trusted;"
                            + " trust the project to use it");
                }
                return read(f);
            }
        }
        List<String> known = all.stream().filter(f -> !f.ignored()).map(Found::name).toList();
        throw new ConfigException("unknown agent \"" + name + "\"" + (known.isEmpty()
                ? " (no agents in ~/.jharness/agents or .jharness/agents)" : " (agents: " + String.join(", ", known) + ")"));
    }

    /** Reads and checks one agent folder. */
    public static AgentDefinition read(Found f) throws ConfigException {
        Path file = f.dir().resolve("agent.json");
        String where = f.source().equals("user") ? "~/.jharness/agents/" + f.name() + "/agent.json" : ".jharness/agents/" + f.name() + "/agent.json";
        JsonNode root;
        try {
            root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigException(where + ": " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new ConfigException(where + ": top level must be an object");
        }
        for (var e : root.properties()) {
            if (!KEYS.contains(e.getKey())) {
                throw new ConfigException(where + ": unknown key \"" + e.getKey() + "\" (known: " + String.join(", ", new TreeSet<>(KEYS)) + ")");
            }
        }
        String description = text(root, "description", where);
        if (description == null || description.isBlank() || description.length() > 200) {
            throw new ConfigException(where + ": \"description\" must be a string of 1 to 200 characters");
        }
        String model = text(root, "model", where);
        Set<String> tools = null;
        JsonNode t = root.get("tools");
        if (t != null) {
            if (!t.isArray()) {
                throw new ConfigException(where + ": \"tools\" must be a list of api methods");
            }
            tools = new LinkedHashSet<>();
            tools.add("readContext");
            for (JsonNode x : t) {
                if (!x.isTextual() || !API.contains(x.asText()) && !x.asText().matches("tool:[a-z][a-z0-9-]{0,31}")) {
                    throw new ConfigException(where + ": \"tools\" entry " + x + " is not an api method (" + String.join(", ", new TreeSet<>(API))
                            + ") or tool:NAME, such as tool:maven");
                }
                tools.add(x.asText());
            }
            tools = Set.copyOf(tools);
        }
        Integer maxSteps = null;
        JsonNode ms = root.get("maxSteps");
        if (ms != null) {
            if (!ms.isInt() || ms.asInt() < 1 || ms.asInt() > 500) {
                throw new ConfigException(where + ": \"maxSteps\" must be a whole number from 1 to 500");
            }
            maxSteps = ms.asInt();
        }
        JsonNode p = root.get("plan");
        if (p != null && !p.isBoolean()) {
            throw new ConfigException(where + ": \"plan\" must be true or false");
        }
        return new AgentDefinition(f.name(), f.source(), f.dir(), description.strip(), model, tools, maxSteps, p != null && p.asBoolean(),
                optional(f.dir().resolve("prompt.md"), where), optional(f.dir().resolve("helpers.js"), where));
    }

    private static String text(JsonNode root, String key, String where) throws ConfigException {
        JsonNode n = root.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isTextual()) {
            throw new ConfigException(where + ": \"" + key + "\" must be a string");
        }
        return n.asText();
    }

    /** A regular file next to {@code agent.json}, at most {@value #MAX_FILE_BYTES} bytes, or null. Symlinks are not followed. */
    private static String optional(Path f, String where) throws ConfigException {
        if (!Files.exists(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String name = f.getFileName().toString();
        try {
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new ConfigException(where.replace("agent.json", name) + ": must be a regular file, not a link or folder");
            }
            if (Files.size(f) > MAX_FILE_BYTES) {
                throw new ConfigException(where.replace("agent.json", name) + ": larger than " + MAX_FILE_BYTES / 1024 + " KB");
            }
            String s = Files.readString(f, StandardCharsets.UTF_8);
            return s.isBlank() ? null : s;
        } catch (IOException e) {
            throw new ConfigException(where.replace("agent.json", name) + ": " + e.getMessage());
        }
    }

    /** {@code /agent}: one line per agent, the current one marked. */
    public static List<String> describe(Path home, Path workspace, boolean trusted, String current) {
        List<String> out = new ArrayList<>();
        out.add((current == null ? "* " : "  ") + "default  the built-in agent");
        for (Found f : find(home, workspace, trusted)) {
            String mark = f.name().equals(current) ? "* " : "  ";
            if (f.ignored()) {
                out.add(mark + f.name() + "  (project, ignored: the project isn't trusted)");
                continue;
            }
            try {
                AgentDefinition d = read(f);
                out.add(mark + f.name() + "  (" + f.source() + ") " + d.description());
            } catch (ConfigException e) {
                out.add(mark + f.name() + "  (" + f.source() + ", invalid) " + e.getMessage());
            }
        }
        return out;
    }
}
