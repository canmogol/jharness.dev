package dev.jharness.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelEventsTest {

    @Test
    void textAndThinkingAreCapped() {
        String big = "x".repeat(ModelEvents.MAX_CHARS + 10);
        var b = ModelEvents.completed("p", "m", new ChatReply(big, big + "yy", "stop", 1, 2));
        assertEquals(ModelEvents.MAX_CHARS, b.get("text").asText().length());
        assertEquals(10, b.get("textCut").asInt());
        assertEquals(12, b.get("thinkingCut").asInt());
        assertEquals("stop", b.get("stopReason").asText());
        assertEquals(2, b.get("outputTokens").asInt());
    }

    @Test
    void smallTextIsWhole() {
        var b = ModelEvents.completed("p", "m", new ChatReply("hi", "", "stop", 0, 0));
        assertEquals("hi", b.get("text").asText());
        assertFalse(b.has("textCut") || b.has("thinking") || b.has("thinkingCut"));
    }
}
