package dev.jharness.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

import dev.jharness.events.Json;

/** Passes calls to a real provider and appends each reply or error to a JSONL file for {@link ReplayProvider}. */
public final class RecordingProvider implements ModelProvider {

    private final ModelProvider inner;
    private final Path file;

    public RecordingProvider(ModelProvider inner, Path file) {
        this.inner = inner;
        this.file = file;
    }

    @Override
    public String provider() {
        return inner.provider();
    }

    @Override
    public int contextWindow() {
        return inner.contextWindow();
    }

    @Override
    public int maxTokens() {
        return inner.maxTokens();
    }

    @Override
    public int summaryTokens() {
        return inner.summaryTokens();
    }

    @Override
    public String discoverContext() {
        return inner.discoverContext();
    }

    @Override
    public String model() {
        return inner.model();
    }

    @Override
    public ChatReply stream(ChatRequest request, Consumer<String> onDelta) throws ModelException {
        try {
            ChatReply r = inner.stream(request, onDelta);
            var line = Json.object().put("reply", r.text());
            if (!r.thinking().isEmpty()) {
                line.put("thinking", r.thinking());
            }
            append(Json.write(line));
            return r;
        } catch (ModelException e) {
            append(Json.write(Json.object().put("error", e.getMessage())));
            throw e;
        }
    }

    private synchronized void append(String line) {
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write recording " + file, e);
        }
    }
}
