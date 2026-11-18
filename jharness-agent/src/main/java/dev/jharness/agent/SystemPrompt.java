package dev.jharness.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

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
                + memory;
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
