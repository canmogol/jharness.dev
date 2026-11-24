package dev.jharness.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.jharness.sandbox.AgentIsolate;

/** Builds the system prompt from {@code system-prompt.md}, {@code api.d.ts}, the examples and the workspace context (D-007). */
public final class SystemPrompt {

    private SystemPrompt() {}

    static final int MEMORY_CHARS = 32 * 1024;

    /** @param contextJson the {@code api.readContext} result at session start */
    public static String build(String contextJson) {
        return build(contextJson, null, null);
    }

    /**
     * @param projectMd the workspace's {@code JHARNESS.md}, or null
     * @param userMd {@code ~/.jharness/JHARNESS.md}, or null
     */
    public static String build(String contextJson, String projectMd, String userMd) {
        return build(contextJson, projectMd, userMd, null);
    }

    /** @param agent the custom agent (spec 025): its description, prompt, tools, plan mode and helpers go last */
    public static String build(String contextJson, String projectMd, String userMd, AgentDefinition agent) {
        StringBuilder memory = new StringBuilder();
        if (userMd != null && !userMd.isBlank()) {
            memory.append("\n# Your user's instructions (~/.jharness/JHARNESS.md)\n\n").append(cut(userMd)).append('\n');
        }
        if (projectMd != null && !projectMd.isBlank()) {
            memory.append("\n# Project instructions (JHARNESS.md)\n\n").append(cut(projectMd)).append('\n');
        }
        return resource("system-prompt.md")
                .replace("{{API}}", AgentIsolate.apiDeclaration().strip())
                .replace("{{EXAMPLES}}", resource("examples.md").strip())
                .replace("{{CONTEXT}}", contextJson == null ? "{}" : contextJson)
                + memory + agent(agent);
    }

    static String agent(AgentDefinition a) {
        if (a == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("\n# You are the agent \"").append(a.name()).append("\"\n\n").append(a.description()).append('\n');
        if (a.prompt() != null) {
            b.append('\n').append(a.prompt().strip()).append('\n');
        }
        if (a.tools() != null) {
            b.append("\nYou may use only these api methods: ").append(String.join(", ", new java.util.TreeSet<>(a.tools())))
                    .append(". Calls to the others are refused.\n");
        }
        if (a.plan()) {
            b.append("\nYou run in plan mode: every change (writes, commits, builds, POST requests) is refused. Investigate and report.\n");
        }
        if (a.helpers() != null) {
            b.append("\nThese helper functions are defined in every block, from the agent's helpers.js:\n\n```js\n")
                    .append(a.helpers().strip()).append("\n```\n");
        }
        return b.toString();
    }

    /**
     * The tools section (spec 028): each defined tool, its operations with the fields each takes, and
     * the fields' types, from the {@code api.tools()} list.
     */
    public static String tools(String listJson) {
        com.fasterxml.jackson.databind.JsonNode list = dev.jharness.events.Json.parse(listJson);
        StringBuilder b = new StringBuilder("\n# Tools\n\nRun these with api.tool(name, {op, ...fields}); api.tools() returns the same list as JSON. "
                + "Each run needs a trusted project and the user's approval.\n");
        if (list.isEmpty()) {
            return b.append("\nNo tools are defined.\n").toString();
        }
        for (var t : list) {
            b.append("\n- ").append(t.path("name").asText());
            if (!t.path("available").asBoolean()) {
                b.append(" (not available: ").append(t.path("reason").asText()).append(')');
            }
            if (!t.path("description").asText().isEmpty()) {
                b.append(": ").append(t.path("description").asText());
            }
            if (t.path("source").asText().equals("project")) {
                b.append(" [defined by the project]");
            }
            b.append('\n');
            var ops = t.path("operations");
            if (ops.isObject() && !ops.isEmpty()) {
                List<String> parts = new java.util.ArrayList<>();
                ops.properties().forEach(e -> {
                    List<String> fs = new java.util.ArrayList<>();
                    e.getValue().forEach(x -> fs.add(x.asText()));
                    parts.add(e.getKey() + (fs.isEmpty() ? "" : " (" + String.join(", ", fs) + ")"));
                });
                b.append("  operations: ").append(String.join("; ", parts)).append('\n');
            }
            var fields = t.path("fields");
            if (fields.isObject() && !fields.isEmpty()) {
                List<String> parts = new java.util.ArrayList<>();
                fields.properties().forEach(e -> {
                    var f = e.getValue();
                    String type = f.path("type").asText();
                    if (type.equals("enum")) {
                        List<String> vs = new java.util.ArrayList<>();
                        f.path("values").forEach(x -> vs.add(x.asText()));
                        type = String.join("|", vs);
                    } else if (type.equals("integer")) {
                        type = "integer " + f.path("min").asLong() + ".." + f.path("max").asLong();
                    }
                    String d = f.path("description").asText();
                    parts.add(e.getKey() + " (" + type + (f.path("required").asBoolean() ? ", required" : "") + ")" + (d.isEmpty() ? "" : ": " + d));
                });
                b.append("  fields: ").append(String.join("; ", parts)).append('\n');
            }
        }
        return b.toString();
    }

    static String cut(String text) {
        String t = text.strip();
        return t.length() <= MEMORY_CHARS ? t : t.substring(0, MEMORY_CHARS) + "\n[cut at 32 KB]";
    }

    /** Reads a {@code JHARNESS.md} file as UTF-8, or null if it is missing or unreadable. */
    public static String readMemory(java.nio.file.Path file) {
        try {
            if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > 1L << 20) {
                return null;
            }
            return java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | java.io.UncheckedIOException e) {
            return null;
        }
    }

    static String resource(String name) {
        try (InputStream in = SystemPrompt.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
