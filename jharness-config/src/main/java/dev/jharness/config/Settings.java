package dev.jharness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;

/**
 * User settings from {@code ~/.jharness/settings.json}: {@code model}, {@code models}, {@code sandbox},
 * {@code timeouts}, {@code agent},
 * {@code permissions}, {@code files}, {@code ui}, {@code http}, {@code git} and {@code offline}. Project and session layers come with the layered-settings feature. Unknown
 * keys and bad values throw {@link ConfigException}, which stops start-up.
 */
public record Settings(ModelProfiles models, SandboxSettings sandbox, Timeouts timeouts, AgentSettings agent,
        PermissionSettings permissions, FileSettings files, UiSettings ui, HttpSettings http, GitSettings git, boolean offline) {

    private static final Set<String> TOP = Set.of("model", "models", "sandbox", "timeouts", "agent", "permissions", "files", "ui", "http", "git", "offline");

    public static Settings defaults() {
        return new Settings(ModelProfiles.empty(), SandboxSettings.defaults(), Timeouts.defaults(), AgentSettings.defaults(),
                PermissionSettings.defaults(), FileSettings.defaults(), UiSettings.defaults(), HttpSettings.defaults(),
                GitSettings.defaults(), false);
    }

    public static Path userFile(Path home) {
        return home.resolve(".jharness").resolve("settings.json");
    }

    /** Loads {@code <home>/.jharness/settings.json}; a missing file means defaults. */
    public static Settings load(Path home) throws ConfigException {
        Path f = userFile(home);
        String text;
        try {
            text = Files.readString(f, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return defaults();
        } catch (IOException e) {
            throw new ConfigException("cannot read " + f + ": " + e.getMessage());
        }
        return parse(text, f.toString());
    }

    public static Settings parse(String text, String source) throws ConfigException {
        return parse(object(text, source), source, true);
    }

    private static ObjectNode object(String text, String source) throws ConfigException {
        JsonNode root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(source + ": " + e.getMessage());
        }
        if (!root.isObject()) {
            throw new ConfigException(source + ": top level must be an object");
        }
        return (ObjectNode) root;
    }

    /** @param complete false to check one layer alone (its default model may come from another layer) */
    static Settings parse(ObjectNode root, String source, boolean complete) throws ConfigException {
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!TOP.contains(e.getKey())) {
                throw new ConfigException(source + ": unknown key \"" + e.getKey() + "\" (known: model, models, sandbox, timeouts, agent, permissions, files, ui, http, git, offline)");
            }
        }
        JsonNode offline = root.get("offline");
        if (offline != null && !offline.isBoolean()) {
            throw new ConfigException(source + ": \"offline\" must be true or false");
        }
        return new Settings(
                ModelProfiles.parse(root.get("model"), root.get("models"), source, complete),
                SandboxSettings.parse(root.get("sandbox"), source),
                Timeouts.parse(root.get("timeouts"), source),
                AgentSettings.parse(root.get("agent"), source),
                PermissionSettings.parse(root.get("permissions"), source),
                FileSettings.parse(root.get("files"), source),
                UiSettings.parse(root.get("ui"), source),
                HttpSettings.parse(root.get("http"), source),
                GitSettings.parse(root.get("git"), source),
                offline != null && offline.asBoolean());
    }

    // --- Layers (D-043) ------------------------------------------------------------------------

    /** Merged settings and the project keys ignored because the project is not trusted. */
    public record Layered(Settings settings, List<String> ignored) {}

    public static Path projectFile(Path workspace) {
        return workspace.resolve(".jharness").resolve("settings.json");
    }

    /**
     * User, then project, then session settings, merged key by key with later layers winning;
     * {@code files.deny} lists are joined. For an untrusted project only {@code files.deny} and
     * {@code permissions.mode: plan} are taken from its file (D-011, D-043), and {@code offline: true},
     * which only tightens (D-055). A project never sets {@code git}: tokens are mapped in the user's
     * settings only.
     */
    public static Layered layered(Path home, Path workspace, boolean trusted, ObjectNode session) throws ConfigException {
        Path uf = userFile(home);
        Path pf = projectFile(workspace);
        ObjectNode user = read(uf);
        ObjectNode project = read(pf);
        parse(user, uf.toString(), false);
        List<String> ignored = new ArrayList<>();
        if (project.has("git")) {
            project = project.deepCopy();
            project.remove("git");
            ignored.add("git");
        }
        if (!trusted) {
            project = untrusted(project, ignored);
        }
        parse(project, pf.toString(), false);
        ObjectNode merged = user.deepCopy();
        merge(merged, project, "");
        if (session != null) {
            merge(merged, session, "");
        }
        return new Layered(parse(merged, uf + " + " + pf + (session == null || session.isEmpty() ? "" : " + command line"), true),
                List.copyOf(ignored));
    }

    private static ObjectNode read(Path f) throws ConfigException {
        try {
            return object(Files.readString(f, StandardCharsets.UTF_8), f.toString());
        } catch (NoSuchFileException e) {
            return Json.object();
        } catch (IOException e) {
            throw new ConfigException("cannot read " + f + ": " + e.getMessage());
        }
    }

    /** Keeps what only tightens: {@code files.deny} and {@code permissions.mode} when it is {@code plan}. */
    static ObjectNode untrusted(ObjectNode project, List<String> ignored) {
        ObjectNode kept = Json.object();
        for (Map.Entry<String, JsonNode> top : project.properties()) {
            String k = top.getKey();
            JsonNode v = top.getValue();
            if (k.equals("offline") && v.isBoolean() && v.asBoolean()) {
                kept.set(k, v);
                continue;
            }
            if (!v.isObject()) {
                ignored.add(k);
                continue;
            }
            for (Map.Entry<String, JsonNode> e : v.properties()) {
                String path = k + "." + e.getKey();
                boolean keep = path.equals("files.deny")
                        || path.equals("permissions.mode") && e.getValue().isTextual() && e.getValue().asText().equals("plan");
                if (keep) {
                    ((ObjectNode) kept.withObjectProperty(k)).set(e.getKey(), e.getValue());
                } else {
                    ignored.add(path);
                }
            }
        }
        return kept;
    }

    /** Merges {@code from} into {@code into}: objects key by key, arrays replaced, except {@code files.deny}, which is joined. */
    static void merge(ObjectNode into, ObjectNode from, String path) {
        for (Map.Entry<String, JsonNode> e : from.properties()) {
            String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
            JsonNode cur = into.get(e.getKey());
            JsonNode v = e.getValue();
            if (cur != null && cur.isObject() && v.isObject()) {
                merge((ObjectNode) cur, (ObjectNode) v, p);
            } else if (p.equals("files.deny") && cur != null && cur.isArray() && v.isArray()) {
                ArrayNode joined = ((ArrayNode) cur).deepCopy();
                for (JsonNode x : v) {
                    if (!joined.toString().contains(x.toString())) {
                        joined.add(x);
                    }
                }
                into.set(e.getKey(), joined);
            } else {
                into.set(e.getKey(), v.deepCopy());
            }
        }
    }
}
