package dev.jharness.testkit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jharness.config.Settings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatReply;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ModelProviders;

/**
 * Live checks against real servers; skipped unless {@code OLLAMA_URL} (e.g. {@code http://localhost:11434/v1},
 * model from {@code OLLAMA_MODEL}, default {@code qwen2.5-coder:7b}) or {@code ANTHROPIC_API_KEY} (model from
 * {@code ANTHROPIC_MODEL}) is set. They cost money or need a local server, so CI doesn't run them.
 */
class LiveProvidersTest {

    private static final ChatRequest REQ = new ChatRequest("Reply with one word.", List.of(ChatMessage.user("Say: ready")), 20);

    @Test
    void ollama() throws Exception {
        String url = System.getenv("OLLAMA_URL");
        assumeTrue(url != null && !url.isBlank(), "OLLAMA_URL not set");
        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "qwen2.5-coder:7b");
        var s = Settings.parse("{\"models\":{\"o\":{\"provider\":\"openai-compatible\",\"baseUrl\":\"" + url + "\",\"model\":\"" + model + "\"}}}", "live");
        ChatReply r = ModelProviders.create(s.models().resolve("o"), new EventBus("live"), () -> Origin.main(1)).stream(REQ, t -> {});
        assertFalse(r.text().isBlank());
    }

    @Test
    void claude() throws Exception {
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null, "ANTHROPIC_API_KEY not set");
        String model = System.getenv().getOrDefault("ANTHROPIC_MODEL", "claude-haiku-5-5");
        var s = Settings.parse("{\"models\":{\"c\":{\"provider\":\"anthropic\",\"model\":\"" + model + "\"}}}", "live");
        ChatReply r = ModelProviders.create(s.models().resolve("c"), new EventBus("live"), () -> Origin.main(1)).stream(REQ, t -> {});
        assertFalse(r.text().isBlank());
        assertTrue(r.outputTokens() > 0);
    }
}
