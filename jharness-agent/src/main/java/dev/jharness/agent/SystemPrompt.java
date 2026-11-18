package dev.jharness.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import dev.jharness.sandbox.AgentIsolate;

/** Builds the system prompt from {@code system-prompt.md}, {@code api.d.ts}, the examples and the workspace context (D-007). */
public final class SystemPrompt {

    private SystemPrompt() {}

    /** @param contextJson the {@code api.readContext} result at session start */
    public static String build(String contextJson) {
        return resource("system-prompt.md")
                .replace("{{API}}", AgentIsolate.apiDeclaration().strip())
                .replace("{{EXAMPLES}}", resource("examples.md").strip())
                .replace("{{CONTEXT}}", contextJson == null ? "{}" : contextJson);
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
